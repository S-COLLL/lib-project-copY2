package com.smartlib.service;

import static com.smartlib.common.ApiException.details;
import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.money;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.round2;
import static com.smartlib.common.Js.str;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.library.Mappers.FINE_SQL;
import static com.smartlib.library.Mappers.MEMBER_SQL;
import static com.smartlib.library.Mappers.PAYMENT_SQL;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

/** Fines (late, damage, lost, manual charges) and payments with receipts. */
@Service
public class FinanceService {

    private static final List<String> METHODS = List.of("Cash", "UPI", "Card", "Bank Transfer");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final Library lib;

    public FinanceService(Db db, Library lib) {
        this.db = db;
        this.lib = lib;
    }

    private Map<String, Object> findFine(CurrentUser user, Object ref, boolean lock) {
        String r = Js.text(ref).strip();
        boolean pk = Js.isPk(r);
        Map<String, Object> f = db.row(FINE_SQL + " WHERE " + (pk ? "f.fine_id" : "f.fine_code") + " = ?" + (lock ? " FOR UPDATE" : ""),
            pk ? (Object) Long.parseLong(r) : r.toUpperCase(Locale.ROOT));
        if (f == null || user.isOtherStudent(f.get("member_id"))) fail(404, "Fine not found");
        return f;
    }

    private Map<String, Object> findPayment(CurrentUser user, String ref) {
        String r = Js.text(ref).strip();
        Map<String, Object> p = Js.isPk(r)
            ? db.row(PAYMENT_SQL + " WHERE p.payment_id = ?", Long.parseLong(r))
            : db.row(PAYMENT_SQL + " WHERE (p.payment_code = ? OR p.receipt_no = ?)", r.toUpperCase(Locale.ROOT), r.toUpperCase(Locale.ROOT));
        if (p == null || user.isOtherStudent(p.get("member_id"))) fail(404, "Payment not found");
        return p;
    }

    private Map<String, Object> fineById(Object fineId) {
        return Mappers.fineOut(db.row(FINE_SQL + " WHERE f.fine_id = ?", fineId));
    }

    private Map<String, Object> paymentById(Object paymentId) {
        return Mappers.paymentOut(db.row(PAYMENT_SQL + " WHERE p.payment_id = ?", paymentId));
    }

    /* =================================== Fines =================================== */

    public Map<String, Object> listFines(CurrentUser user, Input q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (user.isStudent()) {
            where.add("f.member_id = ?");
            params.add(user.memberIdOrNone());
        }
        if (q.truthy("status")) {
            if (q.str("status").equals("Outstanding")) where.add("f.status IN ('Pending','Partially Paid')");
            else {
                where.add("f.status = ?");
                params.add(q.str("status"));
            }
        }
        if (q.truthy("type")) {
            where.add("f.type = ?");
            params.add(q.str("type"));
        }
        if (q.truthy("member")) {
            where.add("f.member_id = ?");
            params.add(lib.findMemberRow(q.raw("member")).get("member_id"));
        }
        if (q.truthy("search")) {
            String like = "%" + q.str("search") + "%";
            where.add("(f.fine_code LIKE ? OR l.transaction_id LIKE ? OR f.transaction_ref LIKE ? OR f.description LIKE ? OR m.name LIKE ? OR m.member_code LIKE ?)");
            for (int i = 0; i < 6; i++) params.add(like);
        }
        String w = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Page pg = Library.paginate(q, 15, 500);
        String from = "FROM fines f LEFT JOIN loans l ON l.loan_id = f.loan_id JOIN members m ON m.member_id = f.member_id LEFT JOIN books b ON b.book_id = f.book_id";
        long total = (long) num(db.value("SELECT COUNT(*) AS total " + from + " " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows("""
            SELECT f.*, l.transaction_id AS loan_txn, m.member_code, m.name AS member_name, m.email AS member_email, b.book_code, b.title AS book_title, b.price AS book_price
            """ + from + " " + w + " ORDER BY f.created_at DESC, f.fine_id DESC LIMIT ? OFFSET ?", pageParams);
        List<Map<String, Object>> data = rows.stream().map(r -> Js.with(Mappers.fineOut(r),
            "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name"), "email", r.get("member_email")),
            "book", truthy(r.get("book_id")) ? obj("_id", id(r.get("book_id")), "bookId", r.get("book_code"), "title", r.get("book_title"), "price", r.get("book_price")) : null))
            .toList();
        return obj("success", true, "data", data, "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> fineSummary(CurrentUser user) {
        Settings s = lib.settings();
        boolean scoped = user.isStudent();
        String w = scoped ? "WHERE member_id = ?" : "";
        Object[] p = scoped ? new Object[] {user.memberIdOrNone()} : new Object[0];
        Map<String, Object> t = db.row("""
            SELECT COALESCE(SUM(original_amount),0) AS original, COALESCE(SUM(discount),0) AS discount, COALESCE(SUM(paid_amount),0) AS paid,
                   COALESCE(SUM(waived_amount),0) AS waived, COUNT(*) AS count, SUM(status IN ('Pending','Partially Paid')) AS pending_count
            FROM fines""" + " " + w, p);
        List<Map<String, Object>> byType = db.rows("SELECT type AS _id, SUM(original_amount) AS amount, COUNT(*) AS count FROM fines " + w
            + " GROUP BY type ORDER BY amount DESC", p);
        return obj("success", true, "data", obj(
            "original", round2(t.get("original")),
            "discount", round2(t.get("discount")),
            "paid", round2(t.get("paid")),
            "waived", round2(t.get("waived")),
            "count", Js.jsonNumber(num(t.get("count"))),
            "pendingCount", Js.jsonNumber(truthy(t.get("pending_count")) ? num(t.get("pending_count")) : 0),
            "remaining", round2(num(t.get("original")) - num(t.get("discount")) - num(t.get("paid")) - num(t.get("waived"))),
            "byType", byType.stream().map(x -> obj("_id", x.get("_id"), "amount", round2(x.get("amount")), "count", Js.jsonNumber(num(x.get("count"))))).toList(),
            "finePerDay", s.finePerDay(),
            "currency", "₹"));
    }

    /** POST /fines { member, type, amount, description, transactionId } — manual charge. */
    public Map<String, Object> createFine(Input body) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!body.truthy("member")) errors.put("member", "Member is required");
        if (!body.truthy("type")) errors.put("type", "Type is required");
        if (!(body.num("amount") >= 1)) errors.put("amount", "Amount must be at least 1");
        ApiException.failIfAny(errors);
        Object description = body.has("description") ? body.raw("description") : "";
        Map<String, Object> data = db.tx(() -> {
            Map<String, Object> m = lib.findMemberRow(body.raw("member"));
            long fineId = db.insert("INSERT INTO fines (member_id, type, description, transaction_ref, original_amount) VALUES (?, ?, ?, ?, ?)",
                m.get("member_id"), Js.cut(body.str("type"), 30), Js.cut(Js.str(description), 255),
                body.truthy("transactionId") ? body.raw("transactionId") : null, body.num("amount"));
            lib.assignCode("fines", "fine_id", "fine_code", "FIN", 5, fineId);
            lib.notifyMember(m.get("member_id"), "fine", "New charge added", body.str("type") + ": " + money(body.raw("amount")) + ". " + Js.str(description), "fines.html");
            return fineById(fineId);
        });
        return obj("success", true, "data", data);
    }

    /** PATCH /fines/:id/discount { discount } — replaces any previous discount. */
    public Map<String, Object> discountFine(CurrentUser user, Input body, String ref) {
        return db.tx(() -> {
            Map<String, Object> f = findFine(user, ref, true);
            double d = body.num("discount");
            if (Double.isNaN(d) || d < 0) fail(400, "Discount must be a positive number", details("discount", "Must be a positive number"));
            if (d > num(f.get("original_amount")) - num(f.get("paid_amount")) - num(f.get("waived_amount"))) {
                fail(400, "Discount cannot exceed the unpaid amount", details("discount", "Cannot exceed the unpaid amount"));
            }
            db.update("UPDATE fines SET discount = ? WHERE fine_id = ?", d, f.get("fine_id"));
            lib.refreshFineStatus(f.get("fine_id"));
            return obj("success", true, "data", fineById(f.get("fine_id")), "message", "Discount of " + money(d) + " applied");
        });
    }

    /** PATCH /fines/:id/waive { reason } — writes off the remaining balance. */
    public Map<String, Object> waiveFine(CurrentUser user, Input body, String ref) {
        return db.tx(() -> {
            Map<String, Object> f = findFine(user, ref, true);
            double remaining = Mappers.fineRemaining(f);
            if (remaining <= 0) fail(400, "Nothing left to waive on this fine");
            db.update("UPDATE fines SET waived_amount = waived_amount + ? WHERE fine_id = ?", remaining, f.get("fine_id"));
            lib.refreshFineStatus(f.get("fine_id"));
            String reason = Js.cut(body.text("reason"), 200);
            long paymentId = db.insert("""
                INSERT INTO fine_payments (member_id, fine_id, transaction_ref, reason, amount, payment_date, method, status, notes, received_by)
                VALUES (?, ?, ?, ?, ?, NOW(), 'Cash', 'Waived', ?, ?)""",
                f.get("member_id"), f.get("fine_id"), truthy(f.get("loan_txn")) ? f.get("loan_txn") : f.get("transaction_ref"),
                f.get("type") + " waived" + (reason.isEmpty() ? "" : " — " + reason), remaining, reason, user.userId());
            lib.assignCode("fine_payments", "payment_id", "payment_code", "PAY", 5, paymentId);
            return obj("success", true, "data", fineById(f.get("fine_id")), "payment", paymentById(paymentId), "message", money(remaining) + " waived");
        });
    }

    /* ================================= Payments ================================= */

    public Map<String, Object> listPayments(CurrentUser user, Input q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (user.isStudent()) {
            where.add("p.member_id = ?");
            params.add(user.memberIdOrNone());
        }
        if (q.truthy("status")) {
            where.add("p.status = ?");
            params.add(q.str("status"));
        }
        if (q.truthy("method")) {
            where.add("p.method = ?");
            params.add(q.str("method"));
        }
        if (q.truthy("member")) {
            where.add("p.member_id = ?");
            params.add(lib.findMemberRow(q.raw("member")).get("member_id"));
        }
        if (q.truthy("from")) {
            where.add("p.payment_date >= ?");
            params.add(JsDate.parse(q.raw("from")));
        }
        if (q.truthy("to")) {
            where.add("p.payment_date < ?");
            var to = JsDate.parse(q.raw("to"));
            params.add(to == null ? null : JsDate.addDays(to, 1));
        }
        if (q.truthy("search")) {
            String like = "%" + q.str("search") + "%";
            where.add("(p.payment_code LIKE ? OR p.receipt_no LIKE ? OR p.transaction_ref LIKE ? OR p.reason LIKE ? OR p.reference LIKE ? OR m.name LIKE ? OR m.member_code LIKE ?)");
            for (int i = 0; i < 7; i++) params.add(like);
        }
        String w = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        String from = "FROM fine_payments p JOIN members m ON m.member_id = p.member_id LEFT JOIN users u ON u.user_id = p.received_by LEFT JOIN fines f ON f.fine_id = p.fine_id";
        List<Map<String, Object>> totals = db.rows("SELECT p.status AS _id, SUM(p.amount) AS amount, COUNT(*) AS count " + from + " " + w + " GROUP BY p.status", params);
        Page pg = Library.paginate(q, 15, 500);
        long total = (long) num(db.value("SELECT COUNT(*) AS total " + from + " " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows("""
            SELECT p.*, u.name AS collected_by_name, m.member_code, m.name AS member_name, m.email AS member_email, f.fine_code, f.type AS fine_type
            """ + from + " " + w + " ORDER BY p.payment_date DESC, p.payment_id DESC LIMIT ? OFFSET ?", pageParams);
        return obj(
            "success", true,
            "data", rows.stream().map(r -> Js.with(Mappers.paymentOut(r),
                "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name"), "email", r.get("member_email")),
                "fine", truthy(r.get("fine_id")) ? obj("_id", id(r.get("fine_id")), "fineId", r.get("fine_code"), "type", r.get("fine_type")) : null)).toList(),
            "totals", totals.stream().map(t -> obj("_id", t.get("_id"), "amount", round2(t.get("amount")), "count", Js.jsonNumber(num(t.get("count"))))).toList(),
            "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> getPayment(CurrentUser user, String ref) {
        Settings s = lib.settings();
        Map<String, Object> p = findPayment(user, ref);
        Map<String, Object> m = db.row(MEMBER_SQL + " WHERE m.member_id = ?", p.get("member_id"));
        Object fine = null;
        if (truthy(p.get("fine_id"))) {
            Map<String, Object> f = db.row(FINE_SQL.replace("SELECT f.*,", "SELECT f.*, b.book_code, b.title AS book_title,")
                + " LEFT JOIN books b ON b.book_id = f.book_id WHERE f.fine_id = ?", p.get("fine_id"));
            fine = Js.with(Mappers.fineOut(f), "book", truthy(f.get("book_id")) ? obj("_id", id(f.get("book_id")), "bookId", f.get("book_code"), "title", f.get("book_title")) : null);
        }
        return obj("success", true, "data", Js.with(Mappers.paymentOut(p), "member", Mappers.memberOut(m), "fine", fine), "library", obj("name", s.text("libraryName")));
    }

    /** A payment reference in the style of the method (UPI…, AUTH-…, NEFT…, CASH-…). */
    private static String reference(String method) {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        String r = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        String now = Long.toString(System.currentTimeMillis());
        return switch (method) {
            case "UPI" -> "UPI" + MemberService.last(now, 8) + r.substring(0, 4);
            case "Card" -> "AUTH-" + r;
            case "Bank Transfer" -> "NEFT" + MemberService.last(now, 10);
            default -> "CASH-" + r.substring(0, 6);
        };
    }

    /** POST /payments { fine | member+reason, amount, method, status?: 'Pending', notes } */
    public Map<String, Object> createPayment(CurrentUser user, Input body) {
        double amount = round2(body.get("amount"));
        if (!(amount > 0)) fail(400, "Amount must be greater than zero", details("amount", "Amount must be greater than zero"));
        String method = body.truthy("method") ? body.str("method") : "Cash";
        if (!METHODS.contains(method)) fail(400, "Invalid payment method");
        return db.tx(() -> {
            Map<String, Object> fine = null;
            Object memberId;
            if (body.truthy("fine")) {
                fine = findFine(user, body.raw("fine"), true);
                double remaining = Mappers.fineRemaining(fine);
                if (remaining <= 0) fail(400, "This fine is already settled");
                if (amount > remaining) fail(400, "Amount exceeds the remaining balance of " + money(remaining), details("amount", "Cannot exceed " + money(remaining)));
                memberId = fine.get("member_id");
            } else {
                memberId = lib.findMemberRow(body.raw("member")).get("member_id");
                if (!body.truthy("reason")) fail(400, "Reason is required for payments not linked to a fine", details("reason", "Reason is required"));
            }
            boolean pending = "Pending".equals(body.raw("status"));
            String status = pending ? "Pending" : fine != null && amount < Mappers.fineRemaining(fine) ? "Partially Paid" : "Paid";
            String reason = body.truthy("reason") ? body.str("reason")
                : fine != null ? fine.get("type") + (truthy(fine.get("description")) ? " — " + fine.get("description") : "") : "";
            Object txn = fine != null && truthy(fine.get("loan_txn")) ? fine.get("loan_txn")
                : fine != null && truthy(fine.get("transaction_ref")) ? fine.get("transaction_ref")
                : body.truthy("transactionId") ? body.raw("transactionId") : null;
            long paymentId = db.insert("""
                INSERT INTO fine_payments (member_id, fine_id, transaction_ref, reason, amount, payment_date, method, status, reference, notes, received_by)
                VALUES (?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?)""",
                memberId, fine != null ? fine.get("fine_id") : null, txn, Js.cut(reason, 255), amount, method, status, reference(method),
                Js.cut(body.text("notes"), 255), user.userId());
            lib.assignCode("fine_payments", "payment_id", "payment_code", "PAY", 5, paymentId);
            if (!pending) lib.assignNextCode("fine_payments", "payment_id", "receipt_no", "RCPT-", 6, paymentId);
            if (fine != null && !pending) {
                db.update("UPDATE fines SET paid_amount = paid_amount + ? WHERE fine_id = ?", amount, fine.get("fine_id"));
                lib.refreshFineStatus(fine.get("fine_id"));
            }
            Map<String, Object> p = db.row(PAYMENT_SQL + " WHERE p.payment_id = ?", paymentId);
            if (!pending) {
                Object name = db.value("SELECT name FROM members WHERE member_id = ?", memberId);
                lib.notifyMember(memberId, "payment", "Payment received",
                    money(amount) + " received via " + method + " (" + p.get("receipt_no") + "). Thank you!", "payments.html");
                lib.notify("staff", null, "payment", "Payment completed", name + " paid " + money(amount) + " via " + method + " — " + reason + ".", "payments.html");
            }
            Object freshFine = fine != null ? fineById(fine.get("fine_id")) : null;
            return obj("success", true, "data", Mappers.paymentOut(p), "fine", freshFine,
                "message", pending ? "Payment recorded as pending" : "Payment of " + money(amount) + " successful");
        });
    }

    public Map<String, Object> confirmPayment(CurrentUser user, String ref) {
        Map<String, Object> out = db.tx(() -> {
            Map<String, Object> p = findPayment(user, ref);
            if (!"Pending".equals(p.get("status"))) fail(400, "Only pending payments can be confirmed");
            String status = "Paid";
            if (truthy(p.get("fine_id"))) {
                Map<String, Object> f = findFine(user, p.get("fine_id"), true);
                double remaining = Mappers.fineRemaining(f);
                if (num(p.get("amount")) > remaining) fail(400, "Fine balance is now " + money(remaining) + "; payment exceeds it");
                db.update("UPDATE fines SET paid_amount = paid_amount + ? WHERE fine_id = ?", p.get("amount"), f.get("fine_id"));
                lib.refreshFineStatus(f.get("fine_id"));
                Object fineStatus = db.value("SELECT status FROM fines WHERE fine_id = ?", f.get("fine_id"));
                status = "Paid".equals(str(fineStatus)) ? "Paid" : "Partially Paid";
            }
            db.update("UPDATE fine_payments SET status = ?, payment_date = NOW() WHERE payment_id = ?", status, p.get("payment_id"));
            String receipt = lib.assignNextCode("fine_payments", "payment_id", "receipt_no", "RCPT-", 6, ((Number) p.get("payment_id")).longValue());
            lib.notifyMember(p.get("member_id"), "payment", "Payment confirmed", money(p.get("amount")) + " confirmed (" + receipt + ").", "payments.html");
            return paymentById(p.get("payment_id"));
        });
        return obj("success", true, "data", out, "message", "Payment confirmed");
    }
}
