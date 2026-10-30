package com.smartlib.service;

import static com.smartlib.common.ApiException.details;
import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.money;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.str;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.library.Mappers.BOOK_SQL;
import static com.smartlib.library.Mappers.FINE_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;
import static com.smartlib.library.Mappers.MEMBER_SQL;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Counts;
import com.smartlib.library.Library.Page;
import com.smartlib.library.Library.Settings;
import com.smartlib.library.Mappers;
import com.smartlib.security.CurrentUser;

/** Issues (loans), returns and reservations. */
@Service
public class CirculationService {

    private final Db db;
    private final Library lib;

    public CirculationService(Db db, Library lib) {
        this.db = db;
        this.lib = lib;
    }

    private static boolean active(Object status) {
        return Library.ACTIVE.contains(str(status));
    }

    /* ================================== Issues ================================== */

    public Map<String, Object> listIssues(CurrentUser user, Input q) {
        Settings s = lib.settings();
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (user.isStudent()) {
            where.add("l.member_id = ?");
            params.add(user.memberIdOrNone());
        }
        if (q.truthy("status")) {
            if (q.str("status").equals("Active")) where.add("l.status IN ('Issued','Overdue')");
            else {
                where.add("l.status = ?");
                params.add(q.str("status"));
            }
        }
        if (q.truthy("search")) {
            String like = "%" + q.str("search") + "%";
            where.add("(l.transaction_id LIKE ? OR m.member_code LIKE ? OR m.name LIKE ? OR b.book_code LIKE ? OR b.title LIKE ?)");
            for (int i = 0; i < 5; i++) params.add(like);
        }
        if (q.truthy("member")) {
            where.add("l.member_id = ?");
            params.add(lib.findMemberRow(q.raw("member")).get("member_id"));
        }
        if (q.truthy("from")) {
            where.add("l.issue_date >= ?");
            params.add(JsDate.parse(q.raw("from")));
        }
        if (q.truthy("to")) {
            where.add("l.issue_date < ?");
            Instant to = JsDate.parse(q.raw("to"));
            params.add(to == null ? null : JsDate.addDays(to, 1));
        }
        String w = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Page pg = Library.paginate(q, 15, 500);
        long total = (long) num(db.value("SELECT COUNT(*) AS total FROM loans l JOIN members m ON m.member_id = l.member_id JOIN books b ON b.book_id = l.book_id " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows(LOAN_SQL.replace("SELECT l.*,", "SELECT l.*, b.cover_image, b.isbn, b.price,") + " " + w
            + " ORDER BY l.issue_date DESC, l.loan_id DESC LIMIT ? OFFSET ?", pageParams);
        List<Map<String, Object>> data = new ArrayList<>();
        Instant now = JsDate.now();
        for (Map<String, Object> r : rows) {
            Map<String, Object> o = Js.with(Mappers.issueOut(r), "book", obj("_id", id(r.get("book_id")), "bookId", r.get("book_code"), "title", r.get("book_title"),
                "coverImage", truthy(r.get("cover_image")) ? r.get("cover_image") : "", "isbn", r.get("isbn"), "price", r.get("price")));
            if (active(r.get("status"))) {
                long days = JsDate.daysBetween(JsDate.parse(r.get("due_date")), now);
                o.put("daysOverdue", days);
                o.put("estimatedFine", days * s.finePerDay());
            }
            data.add(o);
        }
        return obj("success", true, "data", data, "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> getIssue(CurrentUser user, String ref) {
        Map<String, Object> i = lib.findLoanRow(user, ref, false);
        Map<String, Object> book = db.row(BOOK_SQL + " WHERE b.book_id = ?", i.get("book_id"));
        Map<String, Object> member = db.row(MEMBER_SQL + " WHERE m.member_id = ?", i.get("member_id"));
        List<Map<String, Object>> fines = db.rows(FINE_SQL + " WHERE f.loan_id = ?", i.get("loan_id"));
        return obj(
            "success", true,
            "data", Js.with(Mappers.issueOut(i), "book", Mappers.bookOut(book), "member", Mappers.memberOut(member)),
            "return", truthy(i.get("return_date")) ? Mappers.returnOut(i) : null,
            "fines", fines.stream().map(Mappers::fineOut).toList());
    }

    /** Issues a book after the eligibility checks. Shared with reservation pickup. */
    private Map<String, Object> issueBook(CurrentUser user, Input body) {
        Settings s = lib.settings();
        Map<String, Object> m = lib.findMemberRow(body.raw("member"));
        db.rows("SELECT member_id FROM members WHERE member_id = ? FOR UPDATE", m.get("member_id"));
        Map<String, Object> b = lib.findBookRow(body.raw("book"), true);

        Instant now = JsDate.now();
        if (!"Active".equals(m.get("status"))) fail(400, "Membership is " + m.get("status") + ". Book cannot be issued.");
        if (JsDate.parse(m.get("membership_expiry")).isBefore(now)) fail(400, "Membership has expired. Renew it first.");
        long issuedCount = (long) num(m.get("books_issued"));
        if (issuedCount >= s.n("maxBooksPerMember")) {
            fail(400, "Member already has " + issuedCount + " books (limit " + Js.numberToString(s.n("maxBooksPerMember")) + ").");
        }
        if (num(m.get("pending_fine")) > s.n("maxPendingFine")) {
            fail(400, "Member has " + money(m.get("pending_fine")) + " pending fines (limit " + money(s.n("maxPendingFine")) + "). Collect payment first.");
        }
        if (db.row("SELECT loan_id FROM loans WHERE member_id = ? AND book_id = ? AND status IN ('Issued','Overdue')", m.get("member_id"), b.get("book_id")) != null) {
            fail(400, "This member already has a copy of this book.");
        }
        Map<String, Object> held = db.row("SELECT reservation_id FROM reservations WHERE member_id = ? AND book_id = ? AND status = 'Available' FOR UPDATE",
            m.get("member_id"), b.get("book_id"));
        Counts counts = lib.recalcBook(b.get("book_id"));
        if (held == null && counts.available() < 1) fail(400, "No copies of \"" + b.get("title") + "\" are available. You can reserve it instead.");

        Instant issueDate = body.truthy("issueDate") ? JsDate.parse(body.raw("issueDate")) : now;
        if (issueDate == null || issueDate.isAfter(Instant.now().plusMillis(60_000))) {
            fail(400, "Issue date cannot be in the future", details("issueDate", "Cannot be in the future"));
        }
        double days = body.num("days");
        Instant due = body.truthy("dueDate") ? JsDate.parse(body.raw("dueDate"))
            : JsDate.addDays(issueDate, truthy(Js.jsonNumber(days)) ? days : s.n("loanDays"));
        // A back-dated issue (recording a paper loan) only needs a due date after the issue date.
        Instant minDue = body.truthy("issueDate") ? JsDate.startOfDay(issueDate) : JsDate.startOfDay(now);
        if (due == null || !JsDate.startOfDay(due).isAfter(minDue)) {
            fail(400, "Due date must be after the issue date", details("dueDate", "Due date must be after the issue date"));
        }
        due = JsDate.setHours(due, 23, 59, 0, 0);
        String status = due.isBefore(JsDate.startOfDay(JsDate.now())) ? "Overdue" : "Issued";

        long loanId = db.insert("INSERT INTO loans (member_id, book_id, issued_by, issue_date, due_date, status) VALUES (?, ?, ?, ?, ?, ?)",
            m.get("member_id"), b.get("book_id"), user.userId(), issueDate, due, status);
        lib.assignCode("loans", "loan_id", "transaction_id", "TXN", 5, loanId);
        if (held != null) db.update("UPDATE reservations SET status = 'Collected' WHERE reservation_id = ?", held.get("reservation_id"));
        else db.update("UPDATE reservations SET status = 'Collected' WHERE member_id = ? AND book_id = ? AND status = 'Waiting'", m.get("member_id"), b.get("book_id"));
        db.update("UPDATE books SET times_borrowed = times_borrowed + 1 WHERE book_id = ?", b.get("book_id"));
        lib.processReservationQueue(b.get("book_id"), s);
        lib.notifyMember(m.get("member_id"), "issued", "Book issued",
            "\"" + b.get("title") + "\" has been issued to you. Due date: " + JsDate.toDateString(due) + ".", "book-details.html?id=" + b.get("book_code"));
        Map<String, Object> loan = db.row(LOAN_SQL + " WHERE l.loan_id = ?", loanId);
        Map<String, Object> book = db.row(BOOK_SQL + " WHERE b.book_id = ?", b.get("book_id"));
        return obj("success", true, "data", Mappers.issueOut(loan), "book", Mappers.bookOut(book));
    }

    /** POST /issues { member, book, issueDate?, dueDate?, days? } */
    public Map<String, Object> createIssue(CurrentUser user, Input body) {
        if (!body.truthy("member")) fail(400, "Member is required", details("member", "Member is required"));
        if (!body.truthy("book")) fail(400, "Book is required", details("book", "Book is required"));
        return db.tx(() -> issueBook(user, body));
    }

    public Map<String, Object> renewIssue(CurrentUser user, String ref) {
        return db.tx(() -> {
            Settings s = lib.settings();
            Map<String, Object> i = lib.findLoanRow(user, ref, true);
            String status = str(i.get("status"));
            if (!active(status)) fail(400, "Cannot renew a " + status.toLowerCase(Locale.ROOT) + " transaction");
            Instant dueDate = JsDate.parse(i.get("due_date"));
            if (status.equals("Overdue") || JsDate.startOfDay(dueDate).isBefore(JsDate.startOfDay(JsDate.now()))) {
                fail(400, "Overdue books cannot be renewed. Please return the book and clear the fine.");
            }
            if (num(i.get("renewal_count")) >= s.n("maxRenewals")) fail(400, "Renewal limit reached (" + Js.numberToString(s.n("maxRenewals")) + ")");
            long waiting = (long) num(db.value("SELECT COUNT(*) AS waiting FROM reservations WHERE book_id = ? AND status = 'Waiting'", i.get("book_id")));
            if (waiting > 0) fail(400, "Cannot renew — " + waiting + " member(s) are waiting for this book");
            Instant due = JsDate.addDays(dueDate, s.n("loanDays"));
            db.update("UPDATE loans SET due_date = ?, renewal_count = renewal_count + 1, due_soon_notified = 0 WHERE loan_id = ?", due, i.get("loan_id"));
            lib.notifyMember(i.get("member_id"), "issued", "Book renewed",
                "\"" + i.get("book_title") + "\" renewed. New due date: " + JsDate.toDateString(due) + ".", "issues.html");
            Map<String, Object> loan = db.row(LOAN_SQL + " WHERE l.loan_id = ?", i.get("loan_id"));
            return obj("success", true, "data", Mappers.issueOut(loan), "message", "Renewed until " + JsDate.toDateString(due));
        });
    }

    /* ================================== Returns ================================== */

    private record Charges(long daysOverdue, double lateFine, double damageCharge, double lostCharge, double totalFine, double finePerDay) {
        Map<String, Object> toJson() {
            return obj("daysOverdue", daysOverdue, "lateFine", lateFine, "damageCharge", damageCharge, "lostCharge", lostCharge,
                "totalFine", totalFine, "finePerDay", finePerDay);
        }
    }

    private static Charges charges(Map<String, Object> loan, Object price, Settings s, String condition, Instant returnDate, Object damageCharge) {
        long daysOverdue = JsDate.daysBetween(JsDate.parse(loan.get("due_date")), returnDate);
        double lateFine = daysOverdue * s.finePerDay();
        double dmg = 0;
        if (condition.equals("Damaged")) {
            dmg = damageCharge != Js.UNDEFINED && !"".equals(damageCharge) ? num(damageCharge) : Js.round2((num(price) * s.n("damageChargePercent")) / 100);
        }
        double lost = condition.equals("Lost") ? Js.round2(num(price) + s.n("lostProcessingFee")) : 0;
        return new Charges(daysOverdue, lateFine, dmg, lost, Js.round2(lateFine + dmg + lost), s.finePerDay());
    }

    /** GET /returns/lookup?q= — active loans matching a transaction, member, book ID, ISBN or name, with the fine so far. */
    public Map<String, Object> lookupReturns(Input q) {
        Settings s = lib.settings();
        String term = q.text("q").strip();
        List<Map<String, Object>> rows = List.of();
        if (!term.isEmpty()) {
            String like = "%" + term + "%";
            String up = term.toUpperCase(Locale.ROOT);
            rows = db.rows("""
                SELECT l.*, m.member_code, m.name AS member_name, m.email AS member_email, b.book_code, b.title AS book_title, b.isbn, b.price,
                       b.cover_image, a.name AS author_name, u.name AS staff_name
                FROM loans l JOIN members m ON m.member_id = l.member_id JOIN books b ON b.book_id = l.book_id
                JOIN authors a ON a.author_id = b.author_id LEFT JOIN users u ON u.user_id = l.issued_by
                WHERE l.status IN ('Issued','Overdue')
                  AND (l.transaction_id = ? OR m.member_code = ? OR b.book_code = ? OR b.isbn = ? OR m.neptun_code = ?
                       OR CAST(l.book_id AS CHAR) = ? OR m.name LIKE ? OR b.title LIKE ?)
                ORDER BY l.due_date LIMIT 25""", up, up, up, term, up, term, like, like);
        }
        Set<Object> ids = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) ids.add(r.get("member_id"));
        Map<Double, Double> pending = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> x : db.rows("""
                SELECT member_id, SUM(original_amount - discount - paid_amount - waived_amount) AS due FROM fines
                WHERE member_id IN (""" + Js.placeholders(ids) + ") AND status IN ('Pending','Partially Paid') GROUP BY member_id", ids)) {
                pending.put(num(x.get("member_id")), Js.round2(x.get("due")));
            }
        }
        Instant now = JsDate.now();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Double due = pending.get(num(r.get("member_id")));
            data.add(Js.with(Mappers.issueOut(r),
                "book", obj("_id", id(r.get("book_id")), "bookId", r.get("book_code"), "title", r.get("book_title"), "isbn", r.get("isbn"), "price", r.get("price"),
                    "coverImage", truthy(r.get("cover_image")) ? r.get("cover_image") : "", "authorName", r.get("author_name")),
                "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name"), "email", r.get("member_email"),
                    "pendingFine", due != null && due != 0 ? due : 0),
                "charges", charges(r, r.get("price"), s, "Good", now, Js.UNDEFINED).toJson()));
        }
        return obj("success", true, "data", data, "settings",
            obj("finePerDay", s.finePerDay(), "damageChargePercent", s.n("damageChargePercent"), "lostProcessingFee", s.n("lostProcessingFee")));
    }

    public Map<String, Object> listReturns(CurrentUser user, Input q) {
        List<String> where = new ArrayList<>(List.of("l.return_date IS NOT NULL"));
        List<Object> params = new ArrayList<>();
        if (user.isStudent()) {
            where.add("l.member_id = ?");
            params.add(user.memberIdOrNone());
        }
        if (q.truthy("condition")) {
            where.add("l.return_condition = ?");
            params.add(q.str("condition"));
        }
        String w = "WHERE " + String.join(" AND ", where);
        Page pg = Library.paginate(q, 15, 500);
        long total = (long) num(db.value("SELECT COUNT(*) AS total FROM loans l " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows(LOAN_SQL + " " + w + " ORDER BY l.return_date DESC LIMIT ? OFFSET ?", pageParams);
        List<Map<String, Object>> data = rows.stream().map(r -> Js.with(Mappers.returnOut(r),
            "book", obj("_id", id(r.get("book_id")), "bookId", r.get("book_code"), "title", r.get("book_title")),
            "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name")))).toList();
        return obj("success", true, "data", data, "pagination", Library.pageInfo(pg, total));
    }

    /** POST /returns { issue, condition: Good|Damaged|Lost, returnDate?, damageCharge?, remarks? } */
    public Map<String, Object> createReturn(CurrentUser user, Input body) {
        if (!body.truthy("issue")) fail(400, "Transaction is required", details("issue", "Transaction is required"));
        return db.tx(() -> {
            Settings s = lib.settings();
            Map<String, Object> i = lib.findLoanRow(user, body.raw("issue"), true);
            String loanStatus = str(i.get("status"));
            if (!active(loanStatus)) fail(400, "This transaction is already " + loanStatus.toLowerCase(Locale.ROOT));
            String condition = List.of("Good", "Damaged", "Lost").contains(Js.str(body.get("condition"))) ? body.str("condition") : "Good";
            Instant returnDate = body.truthy("returnDate") ? JsDate.parse(body.raw("returnDate")) : JsDate.now();
            if (returnDate == null || returnDate.isBefore(JsDate.startOfDay(JsDate.parse(i.get("issue_date"))))) {
                fail(400, "Return date cannot be before the issue date");
            }
            if (returnDate.isAfter(Instant.now().plusMillis(60_000))) fail(400, "Return date cannot be in the future");
            Object damageIn = body.get("damageCharge");
            if (damageIn != Js.UNDEFINED && !"".equals(damageIn) && !(num(damageIn) >= 0)) fail(400, "Damage charge must be a positive number");

            Map<String, Object> book = db.row("SELECT book_id, book_code, title, price FROM books WHERE book_id = ? FOR UPDATE", i.get("book_id"));
            Charges ch = charges(i, book.get("price"), s, condition, returnDate, damageIn);
            db.update("""
                UPDATE loans SET return_date = ?, status = ?, return_condition = ?, fine_amount = ?, days_overdue = ?, late_fine = ?,
                  damage_charge = ?, lost_charge = ?, remarks = ?, processed_by = ? WHERE loan_id = ?""",
                returnDate, condition.equals("Good") ? "Returned" : condition, condition, ch.totalFine(), ch.daysOverdue(), ch.lateFine(),
                ch.damageCharge(), ch.lostCharge(), Js.cut(body.text("remarks"), 255), user.userId(), i.get("loan_id"));
            lib.assignNextCode("loans", "loan_id", "return_code", "RET", 5, ((Number) i.get("loan_id")).longValue());
            if (condition.equals("Lost")) db.update("UPDATE books SET lost_copies = lost_copies + 1 WHERE book_id = ?", book.get("book_id"));
            if (condition.equals("Damaged")) db.update("UPDATE books SET damaged_copies = damaged_copies + 1 WHERE book_id = ?", book.get("book_id"));

            List<Long> newFines = new ArrayList<>();
            if (ch.lateFine() > 0) {
                newFines.add(addFine(i, book, "Late Fine", ch.lateFine(), ch.daysOverdue() + " day(s) late × " + money(s.finePerDay()) + "/day",
                    ch.daysOverdue(), s.finePerDay()));
            }
            if (ch.damageCharge() > 0) newFines.add(addFine(i, book, "Damage", ch.damageCharge(), "Damage charge for \"" + book.get("title") + "\"", 0, 0));
            if (ch.lostCharge() > 0) {
                newFines.add(addFine(i, book, "Lost Book", ch.lostCharge(),
                    "Replacement " + money(book.get("price")) + " + processing " + money(s.n("lostProcessingFee")), 0, 0));
            }
            if (!newFines.isEmpty()) {
                lib.notifyMember(i.get("member_id"), "fine", "Fine generated",
                    "A fine of " + money(ch.totalFine()) + " was generated for \"" + book.get("title") + "\" (" + i.get("transaction_id") + ").", "fines.html");
            }
            lib.notify("staff", null, "returned",
                condition.equals("Good") ? "Book returned" : "Book reported " + condition.toLowerCase(Locale.ROOT),
                i.get("member_name") + " returned \"" + book.get("title") + "\"" + (ch.daysOverdue() > 0 ? " " + ch.daysOverdue() + " day(s) late" : "")
                    + (ch.totalFine() != 0 ? " — fine " + money(ch.totalFine()) : "") + ".",
                "returns.html");
            lib.processReservationQueue(book.get("book_id"), s);

            Map<String, Object> ret = db.row(LOAN_SQL + " WHERE l.loan_id = ?", i.get("loan_id"));
            List<Map<String, Object>> fines = newFines.isEmpty() ? List.of()
                : db.rows(FINE_SQL + " WHERE f.fine_id IN (" + Js.placeholders(newFines) + ")", newFines);
            Map<String, Object> updated = db.row(BOOK_SQL + " WHERE b.book_id = ?", book.get("book_id"));
            return obj(
                "success", true,
                "data", Mappers.returnOut(ret),
                "fines", fines.stream().map(Mappers::fineOut).toList(),
                "book", Mappers.bookOut(updated),
                "message", ch.totalFine() != 0 ? "Returned with fine " + money(ch.totalFine()) : "Returned on time — no fine");
        });
    }

    private long addFine(Map<String, Object> loan, Map<String, Object> book, String type, double amount, String description, long days, double rate) {
        long fineId = db.insert("INSERT INTO fines (member_id, loan_id, book_id, type, description, days_overdue, rate_per_day, original_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            loan.get("member_id"), loan.get("loan_id"), book.get("book_id"), type, description, days, rate, amount);
        lib.assignCode("fines", "fine_id", "fine_code", "FIN", 5, fineId);
        return fineId;
    }

    /* =============================== Reservations =============================== */

    private Map<String, Object> findReservation(CurrentUser user, String ref, boolean lock) {
        String r = Js.text(ref).strip();
        boolean pk = Js.isPk(r);
        Map<String, Object> row = db.row("SELECT * FROM reservations WHERE " + (pk ? "reservation_id" : "reservation_code") + " = ?" + (lock ? " FOR UPDATE" : ""),
            pk ? (Object) Long.parseLong(r) : r.toUpperCase(Locale.ROOT));
        if (row == null || user.isOtherStudent(row.get("member_id"))) fail(404, "Reservation not found");
        return row;
    }

    public Map<String, Object> listReservations(CurrentUser user, Input q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (user.isStudent()) {
            where.add("r.member_id = ?");
            params.add(user.memberIdOrNone());
        }
        if (q.truthy("status")) {
            where.add("r.status = ?");
            params.add(q.str("status"));
        }
        if (q.truthy("book")) {
            where.add("r.book_id = ?");
            params.add(lib.findBookRow(q.raw("book"), false).get("book_id"));
        }
        String w = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
        Page pg = Library.paginate(q, 15, 500);
        long total = (long) num(db.value("SELECT COUNT(*) AS total FROM reservations r " + w, params));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows("""
            SELECT r.*, b.book_code, b.title, b.cover_image, b.available_copies, b.isbn, a.name AS author_name,
                   s.code AS shelf_code, s.floor AS shelf_floor, s.section AS shelf_section, b.shelf_id, b.rack, b.shelf_row, b.position,
                   m.member_code, m.name AS member_name, m.email AS member_email
            FROM reservations r JOIN books b ON b.book_id = r.book_id JOIN authors a ON a.author_id = b.author_id
            LEFT JOIN shelves s ON s.shelf_id = b.shelf_id JOIN members m ON m.member_id = r.member_id
            """ + w + """

            ORDER BY FIELD(r.status, 'Available', 'Cancelled', 'Collected', 'Expired', 'Waiting'), r.reservation_date DESC
            LIMIT ? OFFSET ?""", pageParams);
        List<Map<String, Object>> data = rows.stream().map(r -> Js.with(Mappers.reservationOut(r),
            "book", obj(
                "_id", id(r.get("book_id")),
                "bookId", r.get("book_code"),
                "title", r.get("title"),
                "coverImage", truthy(r.get("cover_image")) ? r.get("cover_image") : "",
                "availableCopies", r.get("available_copies"),
                "isbn", r.get("isbn"),
                "authorName", r.get("author_name"),
                "location", truthy(r.get("shelf_id"))
                    ? obj("shelf", id(r.get("shelf_id")), "shelfCode", r.get("shelf_code"), "floor", r.get("shelf_floor"), "section", r.get("shelf_section"),
                        "rack", r.get("rack"), "row", r.get("shelf_row"), "position", r.get("position"))
                    : null),
            "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name"), "email", r.get("member_email")))).toList();
        return obj("success", true, "data", data, "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> createReservation(CurrentUser user, Input body) {
        if (!body.truthy("book")) fail(400, "Book is required", details("book", "Book is required"));
        return db.tx(() -> {
            Settings s = lib.settings();
            Object ref = user.isStudent() ? user.memberId() : body.raw("member");
            if (!truthy(ref)) fail(400, user.isStudent() ? "Your account is not linked to a library membership" : "Member is required");
            Map<String, Object> m = lib.findMemberRow(ref);
            Map<String, Object> b = lib.findBookRow(body.raw("book"), true);
            if (!"Active".equals(m.get("status"))) fail(400, "Membership is " + m.get("status") + ". Cannot reserve.");
            if (db.row("SELECT 1 FROM reservations WHERE member_id = ? AND book_id = ? AND status IN ('Waiting','Available')", m.get("member_id"), b.get("book_id")) != null) {
                fail(400, "This member already has an active reservation for this book");
            }
            if (db.row("SELECT 1 FROM loans WHERE member_id = ? AND book_id = ? AND status IN ('Issued','Overdue')", m.get("member_id"), b.get("book_id")) != null) {
                fail(400, "This member currently has this book issued");
            }
            long resId = db.insert("INSERT INTO reservations (member_id, book_id, reservation_date, expiry_date, status, created_by) VALUES (?, ?, NOW(), ?, 'Waiting', ?)",
                m.get("member_id"), b.get("book_id"), JsDate.addDays(JsDate.now(), s.n("reservationValidityDays")), user.userId());
            lib.assignCode("reservations", "reservation_id", "reservation_code", "RES", 5, resId);
            lib.processReservationQueue(b.get("book_id"), s);
            Map<String, Object> r = db.row("SELECT * FROM reservations WHERE reservation_id = ?", resId);
            boolean ready = "Available".equals(r.get("status"));
            lib.notify("staff", null, "reservation", "New reservation",
                m.get("name") + " reserved \"" + b.get("title") + "\" (" + (ready ? "ready for pickup" : "queue #" + r.get("queue_position")) + ").", "reservations.html");
            return obj(
                "success", true,
                "data", Js.with(Mappers.reservationOut(r),
                    "book", obj("_id", id(b.get("book_id")), "bookId", b.get("book_code"), "title", b.get("title")),
                    "member", obj("_id", id(m.get("member_id")), "memberId", m.get("member_code"), "name", m.get("name"))),
                "message", ready ? "A copy is on hold — ready for pickup" : "Reserved — position #" + r.get("queue_position") + " in queue");
        });
    }

    public Map<String, Object> cancelReservation(CurrentUser user, String ref) {
        Map<String, Object> out = db.tx(() -> {
            Map<String, Object> r = findReservation(user, ref, true);
            String status = str(r.get("status"));
            if (!List.of("Waiting", "Available").contains(status)) fail(400, "Reservation is already " + status.toLowerCase(Locale.ROOT));
            db.update("UPDATE reservations SET status = 'Cancelled' WHERE reservation_id = ?", r.get("reservation_id"));
            lib.processReservationQueue(r.get("book_id"));
            return Mappers.reservationOut(db.row("SELECT * FROM reservations WHERE reservation_id = ?", r.get("reservation_id")));
        });
        return obj("success", true, "data", out, "message", "Reservation cancelled");
    }

    /** PATCH /reservations/:id/collect — issue the held copy to the member. */
    public Map<String, Object> collectReservation(CurrentUser user, String ref, Input body) {
        return db.tx(() -> {
            Map<String, Object> r = findReservation(user, ref, true);
            if (!"Available".equals(r.get("status"))) fail(400, "Only reservations marked \"Available\" can be collected");
            Input issue = Input.empty().with("member", r.get("member_id")).with("book", r.get("book_id")).with("days", body.get("days"));
            return issueBook(user, issue);
        });
    }

    public Map<String, Object> deleteReservation(CurrentUser user, String ref) {
        Map<String, Object> r = findReservation(user, ref, false);
        if (List.of("Waiting", "Available").contains(str(r.get("status")))) fail(400, "Cancel the reservation before deleting it");
        db.update("DELETE FROM reservations WHERE reservation_id = ?", r.get("reservation_id"));
        return obj("success", true, "message", "Reservation deleted");
    }
}
