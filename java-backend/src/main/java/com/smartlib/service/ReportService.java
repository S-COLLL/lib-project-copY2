package com.smartlib.service;

import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.round2;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.common.JsDate.ymd;
import static com.smartlib.library.Mappers.BOOK_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;
import static com.smartlib.library.Mappers.MEMBER_SQL;
import static com.smartlib.library.Mappers.SHELF_SQL;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Settings;
import com.smartlib.library.Mappers;

/** The dashboard (stats, charts, recent activity) and the tabular reports. */
@Service
public class ReportService {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private final Db db;
    private final Library lib;
    private final MaintenanceService maintenance;
    private final Map<String, Report> reports = new LinkedHashMap<>();

    public ReportService(Db db, Library lib, MaintenanceService maintenance) {
        this.db = db;
        this.lib = lib;
        this.maintenance = maintenance;
        defineReports();
    }

    /* -------------------------------- Dashboard -------------------------------- */

    private record Month(String key, String label) {
    }

    private static List<Month> lastMonths(int n) {
        LocalDate first = ZonedDateTime.now(JsDate.zone()).toLocalDate().withDayOfMonth(1);
        List<Month> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate m = first.minusMonths(i);
            out.add(new Month(String.format("%d-%02d", m.getYear(), m.getMonthValue()),
                MONTHS[m.getMonthValue() - 1] + " '" + String.valueOf(m.getYear()).substring(2)));
        }
        return out;
    }

    private List<Object> monthly(String sql, List<Month> months) {
        Instant from = JsDate.parse(months.get(0).key() + "-01T00:00:00");
        Map<String, Double> map = new HashMap<>();
        for (Map<String, Object> r : db.rows(sql, from)) map.put(Js.str(r.get("ym")), round2(r.get("v")));
        List<Object> out = new ArrayList<>();
        for (Month m : months) {
            Double v = map.get(m.key());
            out.add(v == null || v == 0 ? 0 : v);
        }
        return out;
    }

    public Map<String, Object> dashboard() {
        maintenance.run();
        Settings s = lib.settings();
        Instant now = JsDate.now();
        List<Month> months = lastMonths(6);

        Map<String, Object> b = db.row("""
            SELECT COALESCE(SUM(quantity),0) AS total, COUNT(*) AS titles, COALESCE(SUM(available_copies),0) AS available, COALESCE(SUM(issued_copies),0) AS issued,
                   COALESCE(SUM(reserved_copies),0) AS reserved, COALESCE(SUM(lost_copies),0) AS lost, COALESCE(SUM(damaged_copies),0) AS damaged,
                   COALESCE(SUM(current_value * quantity),0) AS value, SUM(created_at >= ?) AS recent
            FROM books""", JsDate.addDays(now, -30));
        Map<String, Object> m = db.row("SELECT COUNT(*) AS total, SUM(status = 'Active') AS active FROM members");
        Object waiting = db.value("SELECT COUNT(*) AS waiting FROM reservations WHERE status = 'Waiting'");
        Map<String, Object> f = db.row("SELECT COALESCE(SUM(original_amount - discount),0) AS total, COALESCE(SUM(paid_amount + waived_amount),0) AS settled FROM fines");
        Object collected = db.value("SELECT COALESCE(SUM(amount),0) AS collected FROM fine_payments WHERE status IN ('Paid','Partially Paid')");
        List<Map<String, Object>> overdue = db.rows(LOAN_SQL.replace("SELECT l.*,", "SELECT l.*, b.cover_image,") + " WHERE l.status = 'Overdue' ORDER BY l.due_date");
        double accruing = 0;
        for (Map<String, Object> i : overdue) accruing += JsDate.daysBetween(JsDate.parse(i.get("due_date")), now) * s.finePerDay();

        List<Map<String, Object>> cats = db.rows("""
            SELECT c.name AS label, COALESCE(SUM(b.quantity),0) AS value, COUNT(b.book_id) AS titles FROM categories c
            JOIN books b ON b.category_id = c.category_id GROUP BY c.category_id ORDER BY value DESC""");
        List<Map<String, Object>> popular = db.rows("""
            SELECT b.title AS label, a.name AS author, b.times_borrowed AS value, b.book_code AS id FROM books b JOIN authors a ON a.author_id = b.author_id
            WHERE b.times_borrowed > 0 ORDER BY b.times_borrowed DESC, b.title LIMIT 7""");
        List<Map<String, Object>> shelves = db.rows(SHELF_SQL + " ORDER BY s.floor, s.code");
        List<Map<String, Object>> recentTx = db.rows(LOAN_SQL + " ORDER BY l.updated_at DESC, l.loan_id DESC LIMIT 8");
        List<Map<String, Object>> recentMembers = db.rows(MEMBER_SQL + " ORDER BY m.created_at DESC, m.member_id DESC LIMIT 5");
        List<Map<String, Object>> recentBooks = db.rows(BOOK_SQL + " ORDER BY b.created_at DESC, b.book_id DESC LIMIT 5");

        double fTotal = num(f.get("total"));
        Map<String, Object> stats = obj(
            "totalBooks", Js.jsonNumber(num(b.get("total"))),
            "titles", Js.jsonNumber(num(b.get("titles"))),
            "availableBooks", Js.jsonNumber(num(b.get("available"))),
            "issuedBooks", Js.jsonNumber(num(b.get("issued"))),
            "reservedBooks", Js.jsonNumber(num(b.get("reserved"))),
            "waitingReservations", Js.jsonNumber(num(waiting)),
            "lostBooks", Js.jsonNumber(num(b.get("lost"))),
            "damagedBooks", Js.jsonNumber(num(b.get("damaged"))),
            "totalMembers", Js.jsonNumber(num(m.get("total"))),
            "activeMembers", Js.jsonNumber(truthy(m.get("active")) ? num(m.get("active")) : 0),
            "overdueBooks", overdue.size(),
            "totalFines", round2(fTotal + accruing),
            "finesOutstanding", round2(fTotal - num(f.get("settled")) + accruing),
            "accruingFines", accruing,
            "totalCollected", round2(collected),
            "booksAddedRecently", Js.jsonNumber(truthy(b.get("recent")) ? num(b.get("recent")) : 0),
            "collectionValue", round2(b.get("value")));

        Map<String, Object> charts = obj(
            "months", months.stream().map(Month::label).toList(),
            "issued", monthly("SELECT DATE_FORMAT(issue_date, '%Y-%m') AS ym, COUNT(*) AS v FROM loans WHERE issue_date >= ? GROUP BY ym", months),
            "returned", monthly("SELECT DATE_FORMAT(return_date, '%Y-%m') AS ym, COUNT(*) AS v FROM loans WHERE return_date >= ? GROUP BY ym", months),
            "fines", monthly("SELECT DATE_FORMAT(created_at, '%Y-%m') AS ym, SUM(original_amount) AS v FROM fines WHERE created_at >= ? GROUP BY ym", months),
            "collected", monthly("""
                SELECT DATE_FORMAT(payment_date, '%Y-%m') AS ym, SUM(amount) AS v FROM fine_payments
                WHERE status IN ('Paid','Partially Paid') AND payment_date >= ? GROUP BY ym""", months),
            "popular", popular.stream().map(p -> Js.with(p, "value", Js.jsonNumber(num(p.get("value"))))).toList(),
            "categories", cats.stream().map(c -> obj("label", c.get("label"), "value", Js.jsonNumber(num(c.get("value"))),
                "titles", Js.jsonNumber(num(c.get("titles"))))).toList(),
            "shelves", shelves.stream().map(x -> {
                Map<String, Object> o = Mappers.shelfOut(x);
                double occupied = num(o.get("occupied"));
                double capacity = num(o.get("capacity"));
                return obj("label", o.get("name"), "code", o.get("code"), "floor", o.get("floor"), "section", o.get("section"),
                    "occupied", o.get("occupied"), "capacity", o.get("capacity"), "percent", capacity != 0 ? Js.round((occupied / capacity) * 100) : 0);
            }).toList());

        List<Map<String, Object>> overdueOut = new ArrayList<>();
        for (Map<String, Object> i : overdue.subList(0, Math.min(6, overdue.size()))) {
            long d = JsDate.daysBetween(JsDate.parse(i.get("due_date")), now);
            overdueOut.add(Js.with(Mappers.issueOut(i),
                "book", obj("_id", id(i.get("book_id")), "bookId", i.get("book_code"), "title", i.get("book_title"),
                    "coverImage", truthy(i.get("cover_image")) ? i.get("cover_image") : ""),
                "daysOverdue", d,
                "fine", d * s.finePerDay()));
        }

        return obj("success", true, "data", obj(
            "stats", stats,
            "charts", charts,
            "recentTransactions", recentTx.stream().map(Mappers::issueOut).toList(),
            "recentMembers", recentMembers.stream().map(Mappers::memberOut).toList(),
            "recentBooks", recentBooks.stream().map(Mappers::bookOut).toList(),
            "overdue", overdueOut));
    }

    /* --------------------------------- Reports --------------------------------- */

    private record Report(String title, List<String> columns, Function<Input, List<List<Object>>> rows) {
    }

    private record Range(String sql, List<Object> params) {
    }

    private static Range range(Input q, String col) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.truthy("from")) {
            where.add(col + " >= ?");
            params.add(JsDate.parse(q.raw("from")));
        }
        if (q.truthy("to")) {
            where.add(col + " < ?");
            Instant to = JsDate.parse(q.raw("to"));
            params.add(to == null ? null : JsDate.addDays(to, 1));
        }
        return new Range(where.isEmpty() ? "" : " AND " + String.join(" AND ", where), params);
    }

    private static List<Object> row(Object... values) {
        List<Object> out = new ArrayList<>();
        for (Object v : values) out.add(v);
        return out;
    }

    private static Object orEmpty(Object v) {
        return truthy(v) ? v : "";
    }

    private void defineReports() {
        reports.put("books", new Report("Books Report",
            List.of("Book ID", "Title", "Author", "Category", "ISBN", "Total", "Available", "Issued", "Price (₹)", "Location", "Status"),
            q -> db.rows(BOOK_SQL + " ORDER BY b.book_code").stream().map(b -> row(b.get("book_code"), b.get("title"), b.get("author_name"),
                b.get("category_name"), b.get("isbn"), b.get("quantity"), b.get("available_copies"), b.get("issued_copies"), b.get("price"),
                truthy(b.get("shelf_code"))
                    ? "F" + b.get("shelf_floor") + "-" + b.get("shelf_code") + "-R" + b.get("rack") + "-" + b.get("shelf_row") + b.get("position")
                    : "",
                b.get("status"))).toList()));

        reports.put("issued", new Report("Issued Books Report",
            List.of("Transaction", "Member ID", "Member", "Book ID", "Book", "Issue Date", "Due Date", "Renewals", "Staff", "Status"),
            q -> {
                Range rg = range(q, "l.issue_date");
                return db.rows(LOAN_SQL + " WHERE l.status IN ('Issued','Overdue')" + rg.sql() + " ORDER BY l.issue_date DESC", rg.params()).stream()
                    .map(i -> row(i.get("transaction_id"), i.get("member_code"), i.get("member_name"), i.get("book_code"), i.get("book_title"),
                        ymd(i.get("issue_date")), ymd(i.get("due_date")), i.get("renewal_count"), orEmpty(i.get("staff_name")), i.get("status"))).toList();
            }));

        reports.put("returned", new Report("Returned Books Report",
            List.of("Return ID", "Transaction", "Member", "Book", "Issue Date", "Due Date", "Return Date", "Days Late", "Condition", "Fine (₹)"),
            q -> {
                Range rg = range(q, "l.return_date");
                return db.rows(LOAN_SQL + " WHERE l.return_date IS NOT NULL" + rg.sql() + " ORDER BY l.return_date DESC", rg.params()).stream()
                    .map(i -> row(i.get("return_code"), i.get("transaction_id"), i.get("member_name"), i.get("book_title"), ymd(i.get("issue_date")),
                        ymd(i.get("due_date")), ymd(i.get("return_date")), i.get("days_overdue"), i.get("return_condition"), i.get("fine_amount"))).toList();
            }));

        reports.put("overdue", new Report("Overdue Report",
            List.of("Transaction", "Member ID", "Member", "Book", "Due Date", "Days Overdue", "Fine So Far (₹)"),
            q -> {
                Settings s = lib.settings();
                Instant now = JsDate.now();
                return db.rows(LOAN_SQL + " WHERE l.status = 'Overdue' ORDER BY l.due_date").stream().map(i -> {
                    long d = JsDate.daysBetween(JsDate.parse(i.get("due_date")), now);
                    return row(i.get("transaction_id"), i.get("member_code"), i.get("member_name"), i.get("book_title"), ymd(i.get("due_date")), d,
                        d * s.finePerDay());
                }).toList();
            }));

        reports.put("fines", new Report("Fine Report",
            List.of("Fine ID", "Member", "Type", "Transaction", "Original (₹)", "Discount (₹)", "Paid (₹)", "Waived (₹)", "Remaining (₹)", "Status", "Date"),
            q -> {
                Range rg = range(q, "f.created_at");
                return db.rows("""
                    SELECT f.*, l.transaction_id AS loan_txn, m.name AS member_name, m.member_code FROM fines f LEFT JOIN loans l ON l.loan_id = f.loan_id
                    JOIN members m ON m.member_id = f.member_id WHERE 1=1""" + rg.sql() + " ORDER BY f.created_at DESC", rg.params()).stream()
                    .map(f -> row(f.get("fine_code"), f.get("member_name") + " (" + f.get("member_code") + ")", f.get("type"),
                        truthy(f.get("loan_txn")) ? f.get("loan_txn") : orEmpty(f.get("transaction_ref")), f.get("original_amount"), f.get("discount"),
                        f.get("paid_amount"), f.get("waived_amount"), Mappers.fineRemaining(f), f.get("status"), ymd(f.get("created_at")))).toList();
            }));

        reports.put("payments", new Report("Payment Report",
            List.of("Payment ID", "Receipt", "Member", "Reason", "Amount (₹)", "Method", "Status", "Date", "Collected By"),
            q -> {
                Range rg = range(q, "p.payment_date");
                return db.rows("""
                    SELECT p.*, u.name AS collected_by_name, m.name AS member_name, m.member_code FROM fine_payments p JOIN members m ON m.member_id = p.member_id
                    LEFT JOIN users u ON u.user_id = p.received_by WHERE 1=1""" + rg.sql() + " ORDER BY p.payment_date DESC", rg.params()).stream()
                    .map(p -> row(p.get("payment_code"), truthy(p.get("receipt_no")) ? p.get("receipt_no") : "—", p.get("member_name") + " (" + p.get("member_code") + ")",
                        p.get("reason"), p.get("amount"), p.get("method"), p.get("status"), ymd(p.get("payment_date")), orEmpty(p.get("collected_by_name")))).toList();
            }));

        reports.put("members", new Report("Member Report",
            List.of("Member ID", "Name", "Email", "Neptun", "Phone", "Department", "Type", "Expiry", "Issued", "Returned", "Pending Fine (₹)", "Paid (₹)", "Status"),
            q -> db.rows(MEMBER_SQL + " ORDER BY m.member_code").stream().map(Mappers::memberOut).map(m -> row(m.get("memberId"), m.get("name"), m.get("email"),
                m.get("neptunCode"), m.get("phone"), m.get("department"), m.get("membershipType"), ymd(m.get("membershipExpiry")), m.get("booksIssued"),
                m.get("booksReturned"), m.get("pendingFine"), m.get("totalFinePaid"), m.get("status"))).toList()));

        reports.put("popular", new Report("Popular Books Report",
            List.of("Rank", "Book ID", "Title", "Author", "Category", "Times Borrowed", "Available / Total"),
            q -> {
                List<Map<String, Object>> r = db.rows(BOOK_SQL + " ORDER BY b.times_borrowed DESC, b.title LIMIT 25");
                List<List<Object>> out = new ArrayList<>();
                for (int i = 0; i < r.size(); i++) {
                    Map<String, Object> b = r.get(i);
                    out.add(row(i + 1, b.get("book_code"), b.get("title"), b.get("author_name"), b.get("category_name"), b.get("times_borrowed"),
                        b.get("available_copies") + " / " + b.get("quantity")));
                }
                return out;
            }));

        reports.put("shelves", new Report("Shelf Occupancy Report",
            List.of("Shelf ID", "Name", "Floor", "Section", "Racks", "Capacity", "Occupied", "Available", "Occupancy %", "Status"),
            q -> db.rows(SHELF_SQL + " ORDER BY s.floor, s.code").stream().map(Mappers::shelfOut).map(x -> row(x.get("shelfId"), x.get("name"), x.get("floor"),
                x.get("section"), x.get("racks"), x.get("capacity"), x.get("occupied"), x.get("availableSpace"), x.get("occupancyPercent") + "%",
                x.get("status"))).toList()));
    }

    public Map<String, Object> listReports() {
        List<Map<String, Object>> data = new ArrayList<>();
        reports.forEach((key, r) -> data.add(obj("key", key, "title", r.title())));
        return obj("success", true, "data", data);
    }

    public Map<String, Object> getReport(String type, Input q) {
        Report r = reports.get(type);
        if (r == null) fail(404, "Unknown report type");
        return obj("success", true, "data", obj("key", type, "title", r.title(), "columns", r.columns(), "rows", r.rows().apply(q), "generatedAt", JsDate.now()));
    }
}
