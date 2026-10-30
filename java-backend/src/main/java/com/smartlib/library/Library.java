package com.smartlib.library;

import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.str;
import static com.smartlib.library.Mappers.BOOK_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;
import static com.smartlib.library.Mappers.MEMBER_SQL;
import static com.smartlib.library.Mappers.SHELF_SQL;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.security.CurrentUser;

/** Shared data access and library bookkeeping: settings, lookups, notifications, copy counters, queues. */
@Component
public class Library {

    public static final List<String> ACTIVE = List.of("Issued", "Overdue");

    /** Policy settings and their defaults (the settings table overrides them). */
    public static final Map<String, Object> SETTING_DEFAULTS = new LinkedHashMap<>();
    public static final List<String> TEXT_SETTINGS = List.of("libraryName", "backupPath");

    static {
        SETTING_DEFAULTS.put("libraryName", "SmartLib Central Library");
        SETTING_DEFAULTS.put("finePerDay", 10.0);
        SETTING_DEFAULTS.put("loanDays", 14.0);
        SETTING_DEFAULTS.put("maxRenewals", 2.0);
        SETTING_DEFAULTS.put("maxBooksPerMember", 5.0);
        SETTING_DEFAULTS.put("maxPendingFine", 500.0);
        SETTING_DEFAULTS.put("reservationHoldDays", 3.0);
        SETTING_DEFAULTS.put("reservationValidityDays", 30.0);
        SETTING_DEFAULTS.put("lostProcessingFee", 50.0);
        SETTING_DEFAULTS.put("damageChargePercent", 30.0);
        SETTING_DEFAULTS.put("membershipFee", 500.0);
        SETTING_DEFAULTS.put("dueSoonDays", 2.0);
        SETTING_DEFAULTS.put("backupPath", "./backups");
    }

    private final Db db;

    public Library(Db db) {
        this.db = db;
    }

    /* -------------------------------- Settings -------------------------------- */

    public Settings settings() {
        Map<String, Object> s = new LinkedHashMap<>(SETTING_DEFAULTS);
        for (Map<String, Object> r : db.rows("SELECT setting_key, setting_value FROM settings")) {
            String key = str(r.get("setting_key"));
            if (s.containsKey(key)) s.put(key, TEXT_SETTINGS.contains(key) ? r.get("setting_value") : (Object) num(r.get("setting_value")));
        }
        s.put("currency", "₹");
        return new Settings(s);
    }

    /** The settings as read by the API: numbers, names and the currency sign. */
    public record Settings(Map<String, Object> values) {
        public double n(String key) {
            return num(values.get(key));
        }

        public String text(String key) {
            return str(values.get(key));
        }

        public double finePerDay() {
            return n("finePerDay");
        }
    }

    /* ------------------------------- Pagination ------------------------------- */

    public record Page(long page, long limit, long offset) {
    }

    public static Page paginate(Input q, long def, long max) {
        Long p = Js.parseInt(q.raw("page"));
        Long l = Js.parseInt(q.raw("limit"));
        long page = Math.max(1, p == null || p == 0 ? 1 : p);
        long limit = Math.min(max, Math.max(1, l == null || l == 0 ? def : l));
        return new Page(page, limit, (page - 1) * limit);
    }

    public static Map<String, Object> pageInfo(Page pg, long total) {
        long pages = (long) Math.ceil((double) total / pg.limit());
        return obj("page", pg.page(), "limit", pg.limit(), "total", total, "pages", pages == 0 ? 1 : pages);
    }

    /* ---------------------------------- Codes ---------------------------------- */

    /** Assigns a readable code (BK0001, MEM0001 …) from the auto-increment id. */
    public String assignCode(String table, String pk, String column, String prefix, int width, long rowId) {
        String value = Js.code(prefix, rowId, width);
        db.update("UPDATE " + table + " SET " + column + " = ? WHERE " + pk + " = ?", value, rowId);
        return value;
    }

    /** Assigns the next number of an independent sequence (return codes, receipt numbers). */
    public String assignNextCode(String table, String pk, String column, String prefix, int width, long rowId) {
        Object n = db.value("SELECT COALESCE(MAX(CAST(SUBSTRING(" + column + ", ?) AS UNSIGNED)), 0) AS n FROM " + table + " WHERE " + column
            + " LIKE ? FOR UPDATE", prefix.length() + 1, prefix + "%");
        String value = Js.code(prefix, (long) num(n) + 1, width);
        db.update("UPDATE " + table + " SET " + column + " = ? WHERE " + pk + " = ?", value, rowId);
        return value;
    }

    /* --------------------------------- Lookups --------------------------------- */

    public Map<String, Object> findBookRow(Object ref, boolean lock) {
        if (!Js.truthy(ref)) fail(400, "Book is required");
        String r = str(ref).strip();
        boolean pk = Js.isPk(r);
        String where = pk ? "b.book_id = ?" : "b.book_code = ? OR b.isbn = ?";
        Object[] params = pk ? new Object[] {Long.parseLong(r)} : new Object[] {r.toUpperCase(Locale.ROOT), r};
        Map<String, Object> row = db.row(BOOK_SQL + " WHERE " + where + (lock ? " FOR UPDATE" : ""), params);
        if (row == null) fail(404, "Book not found");
        return row;
    }

    public Map<String, Object> findMemberRow(Object ref) {
        if (!Js.truthy(ref)) fail(400, "Member is required");
        String r = str(ref).strip();
        boolean pk = Js.isPk(r);
        String where = pk ? "m.member_id = ?" : "m.member_code = ? OR m.neptun_code = ?";
        Object[] params = pk ? new Object[] {Long.parseLong(r)} : new Object[] {r.toUpperCase(Locale.ROOT), r.toUpperCase(Locale.ROOT)};
        Map<String, Object> row = db.row(MEMBER_SQL + " WHERE " + where, params);
        if (row == null) fail(404, "Member not found");
        return row;
    }

    public Map<String, Object> findShelfRow(Object ref) {
        if (!Js.truthy(ref)) fail(400, "Shelf is required");
        String r = str(ref).strip();
        Map<String, Object> row = null;
        if (Js.isPk(r)) row = db.row(SHELF_SQL + " WHERE s.shelf_id = ?", Long.parseLong(r));
        if (row == null) {
            String code = r.toUpperCase(Locale.ROOT).replaceFirst("^SH-", "").replaceFirst("^SHELF\\s+", "");
            row = db.row(SHELF_SQL + " WHERE s.code = ?", code);
        }
        if (row == null) fail(404, "Shelf not found");
        return row;
    }

    public Map<String, Object> findLoanRow(CurrentUser user, Object ref, boolean lock) {
        String r = Js.text(ref).strip();
        boolean pk = Js.isPk(r);
        Map<String, Object> row = db.row(LOAN_SQL + " WHERE " + (pk ? "l.loan_id = ?" : "l.transaction_id = ?") + (lock ? " FOR UPDATE" : ""),
            pk ? (Object) Long.parseLong(r) : r.toUpperCase(Locale.ROOT));
        if (row == null || user.isOtherStudent(row.get("member_id"))) fail(404, "Transaction not found");
        return row;
    }

    /** The user as returned by the auth endpoints, with their membership. */
    public Map<String, Object> userOut(Map<String, Object> r) {
        if (r == null) return null;
        Map<String, Object> member = Mappers.memberOut(db.row(MEMBER_SQL + " WHERE m.user_id = ?", r.get("user_id")));
        return obj(
            "_id", Js.id(r.get("user_id")),
            "name", r.get("name"),
            "email", r.get("email"),
            "role", r.get("role"),
            "avatar", Js.truthy(r.get("avatar")) ? r.get("avatar") : "",
            "isActive", Js.truthy(r.get("is_active")),
            "searchHistory", Mappers.parseJson(r.get("search_history"), List.of()),
            "lastLogin", r.get("last_login"),
            "member", member,
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    /* ------------------------------- Bookkeeping ------------------------------- */

    /** Adds a notification. audience: all | staff | member (with memberId). */
    public void notify(String audience, Object memberId, String type, String title, String message, String link) {
        db.update("INSERT INTO notifications (audience, member_id, type, title, message, link) VALUES (?, ?, ?, ?, ?, ?)",
            audience, memberId, type, Js.cut(title, 150), Js.cut(message, 500), link);
    }

    /** A staff notification of type "system" with no link. */
    public void notifyStaff(String title, String message) {
        notify("staff", null, "system", title, message, "");
    }

    public void notifyStaff(String title, String message, String link) {
        notify("staff", null, "system", title, message, link);
    }

    public void notifyMember(Object memberId, String type, String title, String message, String link) {
        notify("member", memberId, type, title, message, link);
    }

    static String computeBookStatus(long available, long reserved, long issued, long lost, long damaged, long quantity) {
        if (available > 0) return "Available";
        if (reserved > 0) return "Reserved";
        if (issued > 0) return "Issued";
        if (lost >= quantity) return "Lost";
        if (damaged > 0) return "Damaged";
        return "Unavailable";
    }

    public record Counts(long available, long reserved, long issued, String status) {
    }

    /** Recomputes a book's copy counters from its loans and reservations. */
    public Counts recalcBook(Object bookId) {
        Map<String, Object> b = db.row("SELECT quantity, lost_copies, damaged_copies FROM books WHERE book_id = ? FOR UPDATE", bookId);
        if (b == null) return null;
        long quantity = (long) num(b.get("quantity"));
        long lost = (long) num(b.get("lost_copies"));
        long damaged = (long) num(b.get("damaged_copies"));
        long issued = (long) num(db.value("SELECT COUNT(*) AS issued FROM loans WHERE book_id = ? AND status IN ('Issued','Overdue')", bookId));
        long reserved = (long) num(db.value("SELECT COUNT(*) AS reserved FROM reservations WHERE book_id = ? AND status = 'Available'", bookId));
        long available = Math.max(0, Math.min(quantity, quantity - issued - reserved - lost - damaged));
        String status = computeBookStatus(available, reserved, issued, lost, damaged, quantity);
        db.update("UPDATE books SET issued_copies = ?, reserved_copies = ?, available_copies = ?, status = ? WHERE book_id = ?",
            issued, reserved, available, status, bookId);
        return new Counts(available, reserved, issued, status);
    }

    public void refreshQueuePositions(Object bookId) {
        List<Map<String, Object>> rows = db.rows(
            "SELECT reservation_id FROM reservations WHERE book_id = ? AND status = 'Waiting' ORDER BY reservation_date, reservation_id", bookId);
        for (int i = 0; i < rows.size(); i++) {
            db.update("UPDATE reservations SET queue_position = ? WHERE reservation_id = ?", i + 1, rows.get(i).get("reservation_id"));
        }
    }

    public Counts processReservationQueue(Object bookId) {
        return processReservationQueue(bookId, settings());
    }

    /** Gives free copies to the earliest waiting reservations (held for reservationHoldDays) and notifies them. */
    public Counts processReservationQueue(Object bookId, Settings s) {
        Counts counts = recalcBook(bookId);
        while (counts != null && counts.available() > 0) {
            Map<String, Object> next = db.row("""
                SELECT r.reservation_id, r.member_id, m.name, m.member_code, b.title, b.book_code
                FROM reservations r JOIN members m ON m.member_id = r.member_id JOIN books b ON b.book_id = r.book_id
                WHERE r.book_id = ? AND r.status = 'Waiting' ORDER BY r.reservation_date, r.reservation_id LIMIT 1 FOR UPDATE""", bookId);
            if (next == null) break;
            Instant expiry = JsDate.addDays(JsDate.now(), s.n("reservationHoldDays"));
            db.update("UPDATE reservations SET status = 'Available', queue_position = 0, available_since = NOW(), expiry_date = ? WHERE reservation_id = ?",
                expiry, next.get("reservation_id"));
            notifyMember(next.get("member_id"), "reservation", "Reserved book is ready",
                "\"" + next.get("title") + "\" is now available for pickup. Please collect it before " + JsDate.toDateString(expiry) + ".",
                "book-details.html?id=" + next.get("book_code"));
            notify("staff", null, "reservation", "Reservation ready for pickup",
                next.get("name") + " (" + next.get("member_code") + ") can now collect \"" + next.get("title") + "\".", "reservations.html");
            counts = recalcBook(bookId);
        }
        refreshQueuePositions(bookId);
        return counts;
    }

    public void refreshFineStatus(Object fineId) {
        Map<String, Object> f = db.row("SELECT original_amount, discount, paid_amount, waived_amount FROM fines WHERE fine_id = ?", fineId);
        if (f == null) return;
        double payable = num(f.get("original_amount")) - num(f.get("discount"));
        double paid = num(f.get("paid_amount"));
        double waived = num(f.get("waived_amount"));
        String status = "Pending";
        if (paid + waived >= payable - 0.001) status = waived > 0 ? "Waived" : "Paid";
        else if (paid > 0) status = "Partially Paid";
        db.update("UPDATE fines SET status = ? WHERE fine_id = ?", status, fineId);
    }

    /** Fails when the shelf cannot take `extra` more copies (ignoring excludeBookId's copies). */
    public void assertShelfSpace(Map<String, Object> shelf, double extra, Object excludeBookId) {
        double used = num(db.value("SELECT COALESCE(SUM(quantity - lost_copies), 0) AS used FROM books WHERE shelf_id = ? AND book_id <> ?",
            shelf.get("shelf_id"), excludeBookId == null ? 0 : excludeBookId));
        double capacity = num(shelf.get("capacity"));
        if (used + extra > capacity) {
            fail(400, shelf.get("name") + " has only " + Js.numberToString(Math.max(0, capacity - used)) + " free slots (needs "
                + Js.numberToString(extra) + ")");
        }
    }
}
