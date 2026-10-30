package com.smartlib.service;

import static com.smartlib.common.ApiException.details;
import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.money;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.library.Mappers.FINE_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;
import static com.smartlib.library.Mappers.MEMBER_SQL;
import static com.smartlib.library.Mappers.PAYMENT_SQL;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.smartlib.common.ApiException;
import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Page;
import com.smartlib.library.Library.Settings;
import com.smartlib.library.Mappers;
import com.smartlib.security.CurrentUser;

/** Library members (memberships), their renewals and membership fees. */
@Service
public class MemberService {

    private static final List<String> MEMBER_TYPES = List.of("Student", "Faculty", "Staff", "Premium", "Guest");
    private static final Pattern PHONE = Pattern.compile("^[+\\d][\\d\\s-]{7,15}$");
    private static final Pattern NEPTUN = Pattern.compile("^[A-Za-z0-9]{6}$");
    private static final Map<String, String> SORTS = Map.of("name", "m.name ASC", "fine", "pending_fine DESC", "expiry", "m.membership_expiry ASC");

    private final Db db;
    private final Library lib;

    public MemberService(Db db, Library lib) {
        this.db = db;
        this.lib = lib;
    }

    private static void validateMember(Input body, boolean partial) {
        Map<String, String> e = new LinkedHashMap<>();
        if ((!partial || body.has("name")) && body.text("name").strip().length() < 2) e.put("name", "Name must be at least 2 characters");
        if ((!partial || body.has("email")) && !AuthService.validEmail(body.get("email"))) e.put("email", "Email is invalid");
        if (body.truthy("phone") && !PHONE.matcher(body.str("phone")).find()) e.put("phone", "Phone number is invalid");
        if (body.truthy("neptunCode") && !NEPTUN.matcher(body.str("neptunCode")).find()) e.put("neptunCode", "Neptun code is 6 letters or digits");
        if (body.truthy("membershipType") && !MEMBER_TYPES.contains(body.str("membershipType"))) e.put("membershipType", "Invalid membership type");
        for (String k : List.of("membershipStart", "membershipExpiry")) {
            if (body.truthy(k) && JsDate.parse(body.raw(k)) == null) e.put(k, "Invalid date");
        }
        ApiException.failIfAny(e);
    }

    private static Map<String, Object> memberColumns(Input body) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", "name");
        map.put("phone", "phone");
        map.put("profilePhoto", "profile_photo");
        map.put("department", "department");
        map.put("course", "course");
        map.put("year", "year");
        map.put("membershipType", "membership_type");
        map.put("status", "status");
        Map<String, Object> cols = new LinkedHashMap<>();
        map.forEach((k, col) -> {
            if (body.has(k)) cols.put(col, body.raw(k) instanceof String s ? s.strip() : body.raw(k));
        });
        if (body.has("email")) cols.put("email", body.str("email").strip().toLowerCase(Locale.ROOT));
        if (body.has("neptunCode")) cols.put("neptun_code", body.truthy("neptunCode") ? body.str("neptunCode").strip().toUpperCase(Locale.ROOT) : null);
        if (body.truthy("membershipStart")) cols.put("membership_start", JsDate.parse(body.raw("membershipStart")));
        if (body.truthy("membershipExpiry")) cols.put("membership_expiry", JsDate.parse(body.raw("membershipExpiry")));
        if (truthy(cols.get("status")) && !List.of("Active", "Expired", "Suspended").contains(Js.str(cols.get("status")))) fail(400, "Invalid status");
        return cols;
    }

    private Map<String, Object> memberPayment(CurrentUser user, Object memberId, String reason, double amount, String method) {
        String reference = method.toUpperCase(Locale.ROOT).replaceAll("\\s", "") + "-" + last(Long.toString(System.currentTimeMillis()), 8);
        long paymentId = db.insert("INSERT INTO fine_payments (member_id, reason, amount, payment_date, method, status, reference, received_by) VALUES (?, ?, ?, NOW(), ?, 'Paid', ?, ?)",
            memberId, reason, amount, method, reference, user.userId());
        lib.assignCode("fine_payments", "payment_id", "payment_code", "PAY", 5, paymentId);
        lib.assignNextCode("fine_payments", "payment_id", "receipt_no", "RCPT-", 6, paymentId);
        return Mappers.paymentOut(db.row(PAYMENT_SQL + " WHERE p.payment_id = ?", paymentId));
    }

    static String last(String s, int n) {
        return s.length() > n ? s.substring(s.length() - n) : s;
    }

    private Map<String, Object> memberById(Object memberId) {
        return Mappers.memberOut(db.row(MEMBER_SQL + " WHERE m.member_id = ?", memberId));
    }

    public Map<String, Object> listMembers(Input q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.truthy("search")) {
            String like = "%" + q.str("search") + "%";
            where.add("(m.name LIKE ? OR m.email LIKE ? OR m.member_code LIKE ? OR m.phone LIKE ? OR m.department LIKE ? OR m.course LIKE ? OR m.neptun_code LIKE ?)");
            for (int i = 0; i < 7; i++) params.add(like);
        }
        if (q.truthy("status")) {
            where.add("m.status = ?");
            params.add(q.str("status"));
        }
        if (q.truthy("type")) {
            where.add("m.membership_type = ?");
            params.add(q.str("type"));
        }
        String w = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Page pg = Library.paginate(q, 12, 500);
        long total = (long) num(db.value("SELECT COUNT(*) AS total FROM members m " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        String order = SORTS.getOrDefault(q.text("sort"), "m.created_at DESC, m.member_id DESC");
        List<Map<String, Object>> rows = db.rows(MEMBER_SQL + " " + w + " ORDER BY " + order + " LIMIT ? OFFSET ?", pageParams);
        return obj("success", true, "data", rows.stream().map(Mappers::memberOut).toList(), "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> getMember(CurrentUser user, String ref) {
        Map<String, Object> m = lib.findMemberRow(ref);
        if (user.isOtherStudent(m.get("member_id"))) fail(403, "You can only view your own membership");
        Object memberId = m.get("member_id");
        List<Map<String, Object>> issues = db.rows(LOAN_SQL.replace("SELECT l.*,", "SELECT l.*, b.cover_image, b.isbn, a.name AS author_name,")
            .replace("JOIN books b ON b.book_id = l.book_id", "JOIN books b ON b.book_id = l.book_id JOIN authors a ON a.author_id = b.author_id")
            + " WHERE l.member_id = ? ORDER BY l.issue_date DESC", memberId);
        List<Map<String, Object>> fines = db.rows(FINE_SQL + " WHERE f.member_id = ? ORDER BY f.created_at DESC", memberId);
        List<Map<String, Object>> payments = db.rows(PAYMENT_SQL + " WHERE p.member_id = ? ORDER BY p.payment_date DESC", memberId);
        List<Map<String, Object>> reservations = db.rows(
            "SELECT r.*, b.book_code, b.title FROM reservations r JOIN books b ON b.book_id = r.book_id WHERE r.member_id = ? ORDER BY r.reservation_date DESC", memberId);
        return obj(
            "success", true,
            "data", Mappers.memberOut(m),
            "issues", issues.stream().map(i -> Js.with(Mappers.issueOut(i), "book", obj("_id", id(i.get("book_id")), "bookId", i.get("book_code"),
                "title", i.get("book_title"), "coverImage", truthy(i.get("cover_image")) ? i.get("cover_image") : "", "isbn", i.get("isbn"),
                "authorName", i.get("author_name")))).toList(),
            "fines", fines.stream().map(Mappers::fineOut).toList(),
            "payments", payments.stream().map(Mappers::paymentOut).toList(),
            "reservations", reservations.stream().map(r -> Js.with(Mappers.reservationOut(r),
                "book", obj("_id", id(r.get("book_id")), "bookId", r.get("book_code"), "title", r.get("title")))).toList());
    }

    public Map<String, Object> createMember(CurrentUser user, Input body) {
        validateMember(body, false);
        Map<String, Object> data = db.tx(() -> {
            Settings s = lib.settings();
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("membership_type", "Student");
            cols.put("status", "Active");
            cols.putAll(memberColumns(body));
            if (!truthy(cols.get("membership_start"))) cols.put("membership_start", JsDate.now());
            Instant start = JsDate.parse(cols.get("membership_start"));
            if (!truthy(cols.get("membership_expiry"))) cols.put("membership_expiry", JsDate.addDays(start, 365));
            Instant expiry = JsDate.parse(cols.get("membership_expiry"));
            if (!expiry.isAfter(start)) fail(400, "Expiry date must be after the start date", details("membershipExpiry", "Must be after the start date"));
            Map<String, Object> linkedUser = db.row("SELECT user_id FROM users WHERE email = ? AND role = 'student'", cols.get("email"));
            if (linkedUser != null) cols.put("user_id", linkedUser.get("user_id"));
            List<String> keys = new ArrayList<>(cols.keySet());
            long memberId = db.insert("INSERT INTO members (" + String.join(", ", keys) + ") VALUES (" + Js.placeholders(keys) + ")", new ArrayList<>(cols.values()));
            String memberCode = lib.assignCode("members", "member_id", "member_code", "MEM", 4, memberId);
            if (body.truthy("collectFee")) {
                double fee = body.num("feeAmount");
                memberPayment(user, memberId, "Membership Fee", truthy(Js.jsonNumber(fee)) ? fee : s.n("membershipFee"),
                    body.truthy("feeMethod") ? body.str("feeMethod") : "Cash");
            }
            lib.notifyStaff("New member added", cols.get("name") + " (" + memberCode + ") joined as " + cols.get("membership_type") + ".", "members.html");
            return memberById(memberId);
        });
        return obj("success", true, "data", data);
    }

    public Map<String, Object> updateMember(Input body, String ref) {
        validateMember(body, true);
        Map<String, Object> data = db.tx(() -> {
            Map<String, Object> m = lib.findMemberRow(ref);
            Map<String, Object> cols = memberColumns(body);
            Instant start = JsDate.parse(truthy(cols.get("membership_start")) ? cols.get("membership_start") : m.get("membership_start"));
            Instant expiry = JsDate.parse(truthy(cols.get("membership_expiry")) ? cols.get("membership_expiry") : m.get("membership_expiry"));
            if (!expiry.isAfter(start)) fail(400, "Expiry date must be after the start date", details("membershipExpiry", "Must be after the start date"));
            if (truthy(cols.get("membership_expiry")) && JsDate.parse(cols.get("membership_expiry")).isAfter(JsDate.now())) {
                cols.put("expiry_notified", 0);
                if ("Expired".equals(m.get("status")) && !body.truthy("status")) cols.put("status", "Active");
            }
            List<String> keys = new ArrayList<>(cols.keySet());
            if (!keys.isEmpty()) {
                List<Object> params = new ArrayList<>(cols.values());
                params.add(m.get("member_id"));
                db.update("UPDATE members SET " + String.join(", ", keys.stream().map(k -> k + " = ?").toList()) + " WHERE member_id = ?", params);
            }
            if (truthy(m.get("user_id")) && (truthy(cols.get("name")) || truthy(cols.get("email")))) {
                db.update("UPDATE users SET name = COALESCE(?, name), email = COALESCE(?, email) WHERE user_id = ?",
                    truthy(cols.get("name")) ? cols.get("name") : null, truthy(cols.get("email")) ? cols.get("email") : null, m.get("user_id"));
            }
            return memberById(m.get("member_id"));
        });
        return obj("success", true, "data", data);
    }

    /** PATCH /members/:id/renew { months, collectFee, amount, method } */
    public Map<String, Object> renewMember(CurrentUser user, Input body, String ref) {
        return db.tx(() -> {
            Settings s = lib.settings();
            Map<String, Object> m = lib.findMemberRow(ref);
            double monthsIn = body.num("months");
            int months = (int) Math.min(60, Math.max(1, truthy(Js.jsonNumber(monthsIn)) ? monthsIn : 12));
            Instant now = JsDate.now();
            Instant expiry = JsDate.parse(m.get("membership_expiry"));
            Instant base = JsDate.addMonths(expiry.isAfter(now) ? expiry : now, months);
            db.update("UPDATE members SET membership_expiry = ?, expiry_notified = 0, status = IF(status = 'Expired', 'Active', status) WHERE member_id = ?",
                base, m.get("member_id"));
            Object payment = null;
            if (!Boolean.FALSE.equals(body.raw("collectFee"))) {
                double amount = body.num("amount");
                payment = memberPayment(user, m.get("member_id"), "Membership Renewal (" + months + " months)",
                    truthy(Js.jsonNumber(amount)) ? amount : Js.round((s.n("membershipFee") * months) / 12),
                    body.truthy("method") ? body.str("method") : "Cash");
            }
            lib.notifyMember(m.get("member_id"), "membership", "Membership renewed", "Your membership is valid until " + JsDate.toDateString(base) + ".", "");
            return obj("success", true, "data", memberById(m.get("member_id")), "payment", payment);
        });
    }

    /** Members with loan/fine/payment history are kept for the audit trail and suspended instead of deleted. */
    public Map<String, Object> deleteMember(String ref) {
        String message = db.tx(() -> {
            Map<String, Object> m = lib.findMemberRow(ref);
            Object memberId = m.get("member_id");
            if (num(m.get("books_issued")) > 0) {
                fail(400, "Member has " + Js.numberToString(num(m.get("books_issued"))) + " book(s) still issued. Collect them before deleting.");
            }
            if (num(m.get("pending_fine")) > 0) fail(400, "Member has " + money(m.get("pending_fine")) + " pending fines. Settle or waive them first.");
            double history = num(db.value("""
                SELECT (SELECT COUNT(*) FROM loans WHERE member_id = ?) + (SELECT COUNT(*) FROM fines WHERE member_id = ?)
                     + (SELECT COUNT(*) FROM fine_payments WHERE member_id = ?) AS history""", memberId, memberId, memberId));
            if (truthy(m.get("user_id"))) db.update("UPDATE users SET is_active = 0 WHERE user_id = ?", m.get("user_id"));
            if (history > 0) {
                db.update("UPDATE members SET status = 'Suspended' WHERE member_id = ?", memberId);
                db.update("UPDATE reservations SET status = 'Cancelled' WHERE member_id = ? AND status IN ('Waiting','Available')", memberId);
                return m.get("name") + " has loan or payment history, so the membership was suspended instead of deleted";
            }
            db.update("DELETE FROM members WHERE member_id = ?", memberId);
            return "Member deleted";
        });
        return obj("success", true, "message", message);
    }
}
