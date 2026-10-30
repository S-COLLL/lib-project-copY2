package com.smartlib.service;

import static com.smartlib.common.ApiException.details;
import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.str;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.library.Mappers.BOOK_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;
import static com.smartlib.library.Mappers.SHELF_SQL;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.smartlib.common.ApiException;
import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Page;
import com.smartlib.library.Mappers;
import com.smartlib.security.CurrentUser;

/** Books, authors, categories and shelves. */
@Service
public class CatalogService {

    private static final Map<String, String> SORTS = Map.of(
        "az", "b.title ASC",
        "za", "b.title DESC",
        "newest", "b.created_at DESC, b.book_id DESC",
        "oldest", "b.created_at ASC, b.book_id ASC",
        "price_asc", "b.price ASC",
        "price_desc", "b.price DESC",
        "popular", "b.times_borrowed DESC, b.title ASC");

    private static final Map<String, String> STATUS_FILTERS = Map.of(
        "available", "b.available_copies > 0",
        "issued", "b.issued_copies > 0",
        "reserved", "(b.reserved_copies > 0 OR EXISTS (SELECT 1 FROM reservations r WHERE r.book_id = b.book_id AND r.status IN ('Waiting','Available')))",
        "overdue", "EXISTS (SELECT 1 FROM loans l WHERE l.book_id = b.book_id AND l.status = 'Overdue')",
        "lost", "b.lost_copies > 0",
        "damaged", "b.damaged_copies > 0",
        "unavailable", "b.available_copies = 0");

    private static final Pattern SHELF_TERM = Pattern.compile("^(?:shelf\\s*|sh-)?([a-z]{1,2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RACK_TERM = Pattern.compile("^rack\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISBN = Pattern.compile("^(97[89])?\\d{9}[\\dX]$");

    private final Db db;
    private final Library lib;

    public CatalogService(Db db, Library lib) {
        this.db = db;
        this.lib = lib;
    }

    /* ================================== Books ================================== */

    private record Where(String sql, List<Object> params) {
    }

    /** WHERE clause for book searches (shared with the AI assistant). */
    private static Where bookFilter(Input q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.truthy("search")) {
            String term = q.str("search").strip();
            String like = "%" + term + "%";
            List<String> parts = new ArrayList<>();
            for (String col : List.of("b.title", "b.isbn", "a.name", "b.publisher", "c.name", "b.book_code", "b.language", "b.subcategory", "s.section")) {
                parts.add(col + " LIKE ?");
                params.add(like);
            }
            Matcher shelf = SHELF_TERM.matcher(term);
            Matcher rack = RACK_TERM.matcher(term);
            if (shelf.matches()) {
                parts.add("s.code = ?");
                params.add(shelf.group(1).toUpperCase(Locale.ROOT));
            }
            if (rack.matches()) {
                parts.add("b.rack = ?");
                params.add(Js.padStart(rack.group(1), 2, '0'));
            }
            where.add("(" + String.join(" OR ", parts) + ")");
        }
        if (q.truthy("category")) {
            where.add(Js.isPk(q.raw("category")) ? "b.category_id = ?" : "c.name = ?");
            params.add(q.raw("category"));
        }
        if (q.truthy("author")) {
            if (Js.isPk(q.raw("author"))) {
                where.add("b.author_id = ?");
                params.add(q.raw("author"));
            } else {
                where.add("a.name LIKE ?");
                params.add("%" + q.str("author") + "%");
            }
        }
        if (q.truthy("language")) {
            where.add("b.language = ?");
            params.add(q.raw("language"));
        }
        if (q.truthy("floor")) {
            where.add("s.floor = ?");
            params.add(q.num("floor"));
        }
        if (q.truthy("shelf")) {
            if (Js.isPk(q.raw("shelf"))) {
                where.add("b.shelf_id = ?");
                params.add(q.raw("shelf"));
            } else {
                where.add("s.code = ?");
                params.add(q.str("shelf").toUpperCase(Locale.ROOT).replaceFirst("^SH-", ""));
            }
        }
        if (q.truthy("rack")) {
            where.add("b.rack = ?");
            params.add(Js.padStart(q.str("rack"), 2, '0'));
        }
        if (q.truthy("minPrice")) {
            where.add("b.price >= ?");
            params.add(q.num("minPrice"));
        }
        if (q.truthy("maxPrice")) {
            where.add("b.price <= ?");
            params.add(q.num("maxPrice"));
        }
        String status = STATUS_FILTERS.get(q.text("status").toLowerCase(Locale.ROOT));
        if (status != null) where.add(status);
        return new Where(where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where), params);
    }

    public record SearchResult(List<Map<String, Object>> rows, Map<String, Object> pagination) {
    }

    public SearchResult searchBooks(Input q, long defLimit, long maxLimit, String orderBy) {
        Where w = bookFilter(q);
        Page pg = Library.paginate(q, defLimit, maxLimit);
        long total = (long) num(db.value("""
            SELECT COUNT(*) AS total FROM books b JOIN authors a ON a.author_id = b.author_id JOIN categories c ON c.category_id = b.category_id
            LEFT JOIN shelves s ON s.shelf_id = b.shelf_id""" + " " + w.sql(), w.params()));
        String order = orderBy != null ? orderBy : SORTS.getOrDefault(q.text("sort"), SORTS.get("newest"));
        List<Object> params = new ArrayList<>(w.params());
        params.add(pg.limit());
        params.add(pg.offset());
        List<Map<String, Object>> rows = db.rows(BOOK_SQL + " " + w.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ?", params);
        return new SearchResult(rows, Library.pageInfo(pg, total));
    }

    public SearchResult searchBooks(Input q, String orderBy) {
        return searchBooks(q, 12, 500, orderBy);
    }

    public Map<String, Object> listBooks(Input q) {
        SearchResult r = searchBooks(q, null);
        Set<Object> overdue = new HashSet<>();
        if (!r.rows().isEmpty()) {
            List<Object> ids = r.rows().stream().map(b -> b.get("book_id")).toList();
            for (Map<String, Object> o : db.rows("SELECT DISTINCT book_id FROM loans WHERE status = 'Overdue' AND book_id IN (" + Js.placeholders(ids) + ")", ids)) {
                overdue.add(num(o.get("book_id")));
            }
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> b : r.rows()) data.add(Js.with(Mappers.bookOut(b, true), "hasOverdue", overdue.contains(num(b.get("book_id")))));
        return obj("success", true, "data", data, "pagination", r.pagination());
    }

    public Map<String, Object> bookMeta() {
        List<Object> languages = db.rows("SELECT DISTINCT language FROM books ORDER BY language").stream().map(r -> r.get("language")).toList();
        List<Object> publishers = db.rows("SELECT DISTINCT publisher FROM books WHERE publisher <> '' ORDER BY publisher").stream().map(r -> r.get("publisher")).toList();
        List<Object> categories = db.rows("SELECT category_id, name FROM categories ORDER BY name").stream()
            .map(c -> (Object) obj("_id", id(c.get("category_id")), "name", c.get("name"))).toList();
        List<Object> floors = db.rows("SELECT DISTINCT floor FROM shelves ORDER BY floor").stream().map(r -> r.get("floor")).toList();
        return obj("success", true, "data", obj("languages", languages, "publishers", publishers, "categories", categories, "floors", floors));
    }

    /** GET /books/recommendations — scored from borrowing history, search history and popularity. */
    public Map<String, Object> recommendations(CurrentUser user, Input q) {
        Object memberId = user.memberId();
        if (!user.isStudent() && q.truthy("member")) memberId = lib.findMemberRow(q.raw("member")).get("member_id");
        List<Map<String, Object>> history = memberId != null
            ? db.rows("SELECT l.book_id, b.category_id, b.author_id FROM loans l JOIN books b ON b.book_id = l.book_id WHERE l.member_id = ?", memberId)
            : List.of();
        Set<Double> read = new HashSet<>();
        Map<Double, Integer> catScore = new HashMap<>();
        Map<Double, Integer> authScore = new HashMap<>();
        for (Map<String, Object> h : history) {
            read.add(num(h.get("book_id")));
            catScore.merge(num(h.get("category_id")), 1, Integer::sum);
            authScore.merge(num(h.get("author_id")), 1, Integer::sum);
        }
        List<String> allTerms = Mappers.stringList(user.row().get("search_history"));
        List<String> terms = allTerms.size() > 5 ? allTerms.subList(0, 5) : allTerms;
        List<Pattern> termRx = terms.stream().map(t -> Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList();

        List<Map<String, Object>> cands = db.rows(BOOK_SQL).stream().filter(b -> !read.contains(num(b.get("book_id")))).toList();
        double maxB = 1;
        for (Map<String, Object> b : cands) maxB = Math.max(maxB, num(b.get("times_borrowed")));
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> b : cands) {
            double borrowed = num(b.get("times_borrowed"));
            double score = (borrowed / maxB) * 2;
            List<String> reasons = new ArrayList<>();
            Integer cs = catScore.get(num(b.get("category_id")));
            if (cs != null) {
                score += cs * 2;
                reasons.add("Because you read " + b.get("category_name"));
            }
            Integer as = authScore.get(num(b.get("author_id")));
            if (as != null) {
                score += as * 3;
                reasons.add("More by " + b.get("author_name"));
            }
            if (termRx.stream().anyMatch(rx -> rx.matcher(str(b.get("title"))).find() || rx.matcher(str(b.get("author_name"))).find()
                || rx.matcher(str(b.get("category_name"))).find())) {
                score += 2.5;
                reasons.add("Matches your searches");
            }
            if (num(b.get("available_copies")) > 0) score += 0.5;
            if (reasons.isEmpty()) reasons.add(borrowed > maxB / 2 ? "Popular in the library" : "Trending pick");
            scored.add(Js.with(Mappers.bookOut(b), "score", score, "reason", reasons.get(0)));
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("score")).reversed());
        double limit = q.num("limit");
        int n = truthy(Js.jsonNumber(limit)) ? (int) limit : 8;
        List<Map<String, Object>> top = scored.subList(0, Math.max(0, Math.min(n, scored.size())));
        return obj("success", true, "data", top, "basedOn", obj("borrowed", history.size(), "searches", terms));
    }

    public Map<String, Object> getBook(CurrentUser user, String ref) {
        Map<String, Object> b = lib.findBookRow(ref, false);
        Map<String, Object> out = new LinkedHashMap<>(Mappers.bookOut(b));
        out.put("author", Mappers.authorOut(db.row("SELECT * FROM authors WHERE author_id = ?", b.get("author_id"))));
        out.put("category", Mappers.sel(Mappers.categoryOut(db.row("SELECT * FROM categories WHERE category_id = ?", b.get("category_id"))), "name", "icon", "categoryId"));
        if (truthy(b.get("shelf_id"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> location = new LinkedHashMap<>((Map<String, Object>) out.get("location"));
            location.put("shelf", Mappers.shelfOut(db.row(SHELF_SQL + " WHERE s.shelf_id = ?", b.get("shelf_id"))));
            out.put("location", location);
        }
        List<Map<String, Object>> issues = db.rows(LOAN_SQL + " WHERE l.book_id = ? AND l.status IN ('Issued','Overdue') ORDER BY l.due_date", b.get("book_id"));
        List<Map<String, Object>> queue = db.rows("""
            SELECT r.*, m.member_code, m.name AS member_name FROM reservations r JOIN members m ON m.member_id = r.member_id
            WHERE r.book_id = ? AND r.status IN ('Waiting','Available') ORDER BY r.status, r.reservation_date""", b.get("book_id"));
        List<Map<String, Object>> similar = db.rows(BOOK_SQL + " WHERE b.book_id <> ? AND (b.category_id = ? OR b.author_id = ?) ORDER BY b.times_borrowed DESC LIMIT 6",
            b.get("book_id"), b.get("category_id"), b.get("author_id"));
        boolean staff = user.isStaff();
        List<Map<String, Object>> reservations = queue.stream()
            .filter(r -> staff || ownMember(user, r.get("member_id")))
            .map(r -> Js.with(Mappers.reservationOut(r), "member", obj("_id", id(r.get("member_id")), "memberId", r.get("member_code"), "name", r.get("member_name"))))
            .toList();
        List<Map<String, Object>> activeIssues = issues.stream().filter(i -> staff || ownMember(user, i.get("member_id"))).map(Mappers::issueOut).toList();
        return obj(
            "success", true,
            "data", out,
            "activeIssues", activeIssues,
            "nextDue", issues.isEmpty() ? null : issues.get(0).get("due_date"),
            "reservations", reservations,
            "queueLength", queue.stream().filter(r -> "Waiting".equals(r.get("status"))).count(),
            "similar", similar.stream().map(Mappers::bookOut).toList());
    }

    private static boolean ownMember(CurrentUser user, Object memberId) {
        return user.memberId() != null && memberId != null && ((Number) memberId).intValue() == user.memberId();
    }

    private Map<String, Object> resolveAuthor(Input body) {
        if (body.truthy("author") && Js.isPk(body.raw("author"))) {
            Map<String, Object> a = db.row("SELECT * FROM authors WHERE author_id = ?", body.raw("author"));
            if (a == null) fail(400, "Selected author does not exist");
            return a;
        }
        String name = Js.text(body.truthy("authorName") ? body.raw("authorName") : body.raw("author")).strip();
        if (name.isEmpty()) fail(400, "Author is required", details("authorName", "Author is required"));
        Map<String, Object> a = db.row("SELECT * FROM authors WHERE name = ?", name);
        if (a != null) return a;
        long id = db.insert("INSERT INTO authors (name, biography, country, profile_image) VALUES (?, '', '', '')", name);
        lib.assignCode("authors", "author_id", "author_code", "AUT", 3, id);
        return obj("author_id", id, "name", name);
    }

    private Map<String, Object> resolveCategory(Input body) {
        Object ref = body.truthy("category") ? body.raw("category") : body.raw("categoryName");
        if (!truthy(ref)) fail(400, "Category is required", details("category", "Category is required"));
        Map<String, Object> cat = db.row("SELECT * FROM categories WHERE " + (Js.isPk(ref) ? "category_id" : "name") + " = ?", ref);
        if (cat == null) fail(400, "Selected category does not exist");
        return cat;
    }

    private record Location(Map<String, Object> shelf, String rack, String row, String position) {
    }

    private Location resolveLocation(Input loc) {
        if (loc == null || !loc.truthy("shelf")) fail(400, "Shelf location is required", details("shelf", "Shelf is required"));
        Map<String, Object> s = lib.findShelfRow(loc.raw("shelf"));
        String rack = loc.text("rack").strip();
        String row = loc.text("row").strip().toUpperCase(Locale.ROOT);
        String position = loc.text("position").strip();
        if (rack.isEmpty() || row.isEmpty() || position.isEmpty()) fail(400, "Rack, row and position are required");
        long racks = (long) num(s.get("racks"));
        long rows = (long) num(s.get("rows_per_rack"));
        if (!rack.matches("^\\d{1,3}$") || Integer.parseInt(rack) < 1 || Integer.parseInt(rack) > racks) {
            fail(400, "Rack must be between 1 and " + racks + " for " + s.get("name"), details("rack", "1–" + racks));
        }
        if (!row.matches("^[A-Z]$") || row.charAt(0) - 64 > rows) {
            String last = String.valueOf((char) (64 + rows));
            fail(400, "Row must be between A and " + last + " for " + s.get("name"), details("row", "A–" + last));
        }
        if (!position.matches("^\\d{1,3}$") || Integer.parseInt(position) < 1) {
            fail(400, "Position must be a positive number", details("position", "Must be a positive number"));
        }
        return new Location(s, Js.padStart(rack, 2, '0'), row, Js.padStart(position, 2, '0'));
    }

    private static final Map<String, String> NUM_FIELDS = new LinkedHashMap<>();
    private static final Map<String, String> TEXT_FIELDS = new LinkedHashMap<>();

    static {
        NUM_FIELDS.put("pages", "pages");
        NUM_FIELDS.put("price", "price");
        NUM_FIELDS.put("purchasePrice", "purchase_price");
        NUM_FIELDS.put("currentValue", "current_value");
        NUM_FIELDS.put("quantity", "quantity");
        NUM_FIELDS.put("lostCopies", "lost_copies");
        NUM_FIELDS.put("damagedCopies", "damaged_copies");
        TEXT_FIELDS.put("title", "title");
        TEXT_FIELDS.put("isbn", "isbn");
        TEXT_FIELDS.put("publisher", "publisher");
        TEXT_FIELDS.put("edition", "edition");
        TEXT_FIELDS.put("language", "language");
        TEXT_FIELDS.put("subcategory", "subcategory");
        TEXT_FIELDS.put("description", "description");
        TEXT_FIELDS.put("coverImage", "cover_image");
    }

    private static void validateBook(Input body, boolean partial) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!partial || body.has("title")) if (body.text("title").strip().isEmpty()) errors.put("title", "Book name is required");
        if (!partial || body.has("isbn")) if (!ISBN.matcher(body.text("isbn")).matches()) errors.put("isbn", "ISBN must be a valid ISBN-10 or ISBN-13 (digits only)");
        if (!partial || body.has("price")) if ("".equals(body.raw("price")) || !(body.num("price") >= 0)) errors.put("price", "Price is required");
        if (!partial || body.has("quantity")) if (!(body.num("quantity") >= 1)) errors.put("quantity", "Quantity must be at least 1");
        for (String k : List.of("pages", "purchasePrice", "currentValue", "lostCopies", "damagedCopies")) {
            if (body.has(k) && !"".equals(body.raw(k)) && !(body.num(k) >= 0)) errors.put(k, "Must be zero or more");
        }
        if (body.truthy("publicationDate") && JsDate.parse(body.raw("publicationDate")) == null) errors.put("publicationDate", "Invalid date");
        ApiException.failIfAny(errors);
    }

    private static Map<String, Object> bookColumns(Input body) {
        Map<String, Object> cols = new LinkedHashMap<>();
        TEXT_FIELDS.forEach((k, col) -> {
            if (body.has(k)) cols.put(col, body.raw(k) == null ? "" : Js.str(body.raw(k)).strip());
        });
        NUM_FIELDS.forEach((k, col) -> {
            if (body.has(k) && !"".equals(body.raw(k))) cols.put(col, body.num(k));
        });
        if (body.has("publicationDate")) cols.put("publication_date", body.truthy("publicationDate") ? JsDate.parse(body.raw("publicationDate")) : null);
        return cols;
    }

    private Map<String, Object> bookById(Object bookId) {
        return Mappers.bookOut(db.row(BOOK_SQL + " WHERE b.book_id = ?", bookId));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createBook(CurrentUser user, Input body) {
        validateBook(body, false);
        Map<String, Object> book = db.tx(() -> {
            Map<String, Object> a = resolveAuthor(body);
            Map<String, Object> cat = resolveCategory(body);
            Location loc = resolveLocation(body.obj("location"));
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("edition", "1st");
            cols.put("language", "English");
            cols.putAll(bookColumns(body));
            if (!truthy(cols.get("lost_copies"))) cols.put("lost_copies", 0.0);
            if (!truthy(cols.get("current_value"))) cols.put("current_value", cols.get("price"));
            lib.assertShelfSpace(loc.shelf(), num(cols.get("quantity")) - num(cols.get("lost_copies")), 0);
            cols.put("author_id", a.get("author_id"));
            cols.put("category_id", cat.get("category_id"));
            cols.put("shelf_id", loc.shelf().get("shelf_id"));
            cols.put("rack", loc.rack());
            cols.put("shelf_row", loc.row());
            cols.put("position", loc.position());
            cols.put("added_by", user.userId());
            List<String> keys = new ArrayList<>(cols.keySet());
            long id = db.insert("INSERT INTO books (" + String.join(", ", keys) + ") VALUES (" + Js.placeholders(keys) + ")", new ArrayList<>(cols.values()));
            lib.assignCode("books", "book_id", "book_code", "BK", 4, id);
            lib.recalcBook(id);
            Map<String, Object> out = bookById(id);
            Map<String, Object> l = (Map<String, Object>) out.get("location");
            lib.notify("all", null, "new-book", "New book added",
                "\"" + out.get("title") + "\" by " + a.get("name") + " is now in " + cat.get("name") + " — Floor " + l.get("floor") + ", Shelf " + l.get("shelfCode") + ".",
                "book-details.html?id=" + out.get("bookId"));
            return out;
        });
        return obj("success", true, "data", book);
    }

    public Map<String, Object> updateBook(Input body, String ref) {
        validateBook(body, true);
        Map<String, Object> book = db.tx(() -> {
            Map<String, Object> b = lib.findBookRow(ref, true);
            Map<String, Object> cols = bookColumns(body);
            if (body.truthy("author") || body.truthy("authorName")) cols.put("author_id", resolveAuthor(body).get("author_id"));
            if (body.truthy("category") || body.truthy("categoryName")) cols.put("category_id", resolveCategory(body).get("category_id"));
            Map<String, Object> shelf = null;
            if (body.truthy("location")) {
                Location loc = resolveLocation(body.obj("location"));
                shelf = loc.shelf();
                cols.put("shelf_id", shelf.get("shelf_id"));
                cols.put("rack", loc.rack());
                cols.put("shelf_row", loc.row());
                cols.put("position", loc.position());
            }
            double quantity = cols.containsKey("quantity") ? num(cols.get("quantity")) : num(b.get("quantity"));
            double lost = cols.containsKey("lost_copies") ? num(cols.get("lost_copies")) : num(b.get("lost_copies"));
            double damaged = cols.containsKey("damaged_copies") ? num(cols.get("damaged_copies")) : num(b.get("damaged_copies"));
            long active = (long) num(db.value("SELECT COUNT(*) AS active FROM loans WHERE book_id = ? AND status IN ('Issued','Overdue')", b.get("book_id")));
            if (quantity < active + lost + damaged) fail(400, "Quantity cannot be lower than issued (" + active + ") + lost + damaged copies");
            if (shelf != null) lib.assertShelfSpace(shelf, quantity - lost, b.get("book_id"));
            // Keep available_copies within the CHECK range until recalcBook runs.
            cols.put("available_copies", Math.min(num(b.get("available_copies")), quantity));
            List<String> keys = new ArrayList<>(cols.keySet());
            if (!keys.isEmpty()) {
                List<Object> params = new ArrayList<>(cols.values());
                params.add(b.get("book_id"));
                db.update("UPDATE books SET " + String.join(", ", keys.stream().map(k -> k + " = ?").toList()) + " WHERE book_id = ?", params);
            }
            lib.processReservationQueue(b.get("book_id"));
            return bookById(b.get("book_id"));
        });
        return obj("success", true, "data", book);
    }

    /** PATCH /books/:id/location { shelf, rack, row, position } */
    @SuppressWarnings("unchecked")
    public Map<String, Object> moveBook(Input body, String ref) {
        Map<String, Object> book = db.tx(() -> {
            Map<String, Object> b = lib.findBookRow(ref, true);
            Location loc = resolveLocation(body);
            if (num(loc.shelf().get("shelf_id")) != num(b.get("shelf_id"))) {
                lib.assertShelfSpace(loc.shelf(), num(b.get("quantity")) - num(b.get("lost_copies")), b.get("book_id"));
            }
            db.update("UPDATE books SET shelf_id = ?, rack = ?, shelf_row = ?, position = ? WHERE book_id = ?",
                loc.shelf().get("shelf_id"), loc.rack(), loc.row(), loc.position(), b.get("book_id"));
            Map<String, Object> out = bookById(b.get("book_id"));
            Map<String, Object> l = (Map<String, Object>) out.get("location");
            lib.notifyStaff("Book moved", "\"" + out.get("title") + "\" moved to Floor " + l.get("floor") + ", " + l.get("section") + ", Shelf " + l.get("shelfCode")
                + ", Rack " + l.get("rack") + ", Row " + l.get("row") + ", Position " + l.get("position") + ".", "book-details.html?id=" + out.get("bookId"));
            return out;
        });
        return obj("success", true, "data", book);
    }

    private void deleteBookRow(Map<String, Object> b) {
        long active = (long) num(db.value("SELECT COUNT(*) AS active FROM loans WHERE book_id = ? AND status IN ('Issued','Overdue')", b.get("book_id")));
        if (active > 0) fail(400, "\"" + b.get("title") + "\" has " + active + " copy(s) currently issued and cannot be deleted");
        long history = (long) num(db.value("SELECT COUNT(*) AS history FROM loans WHERE book_id = ?", b.get("book_id")));
        if (history > 0) fail(400, "\"" + b.get("title") + "\" has loan history and cannot be deleted. Set its quantity or mark copies lost instead.");
        db.update("DELETE FROM books WHERE book_id = ?", b.get("book_id"));
    }

    public Map<String, Object> deleteBook(String ref) {
        db.tx(() -> deleteBookRow(lib.findBookRow(ref, true)));
        return obj("success", true, "message", "Book deleted");
    }

    /** POST /books/bulk { ids: [...], action: 'delete' | 'category' | 'move', category?, shelf? } */
    public Map<String, Object> bulkBooks(Input body) {
        List<?> ids = body.list("ids");
        if (ids == null || ids.isEmpty()) fail(400, "Select at least one book");
        List<Object> numeric = new ArrayList<>();
        for (Object i : ids) {
            double d = num(i);
            if (!Double.isNaN(d) && d == Math.rint(d) && !Double.isInfinite(d)) numeric.add((long) d);
        }
        String action = Js.str(body.get("action"));
        int[] ok = {0};
        List<Map<String, Object>> failed = new ArrayList<>();
        if (action.equals("delete")) {
            for (Map<String, Object> b : db.rows("SELECT book_id, book_code, title FROM books WHERE book_id IN (" + Js.placeholders(numeric) + ")", numeric)) {
                try {
                    db.tx(() -> deleteBookRow(b));
                    ok[0]++;
                } catch (RuntimeException e) {
                    failed.add(obj("id", b.get("book_code"), "reason", e.getMessage()));
                }
            }
        } else if (action.equals("category")) {
            db.tx(() -> {
                Map<String, Object> cat = resolveCategory(body);
                ok[0] = db.update("UPDATE books SET category_id = ? WHERE book_id IN (" + Js.placeholders(numeric) + ")", cat.get("category_id"), numeric);
            });
        } else if (action.equals("move")) {
            db.tx(() -> {
                Map<String, Object> s = lib.findShelfRow(body.raw("shelf"));
                double incoming = num(db.value("SELECT COALESCE(SUM(quantity - lost_copies), 0) AS incoming FROM books WHERE book_id IN ("
                    + Js.placeholders(numeric) + ") AND (shelf_id IS NULL OR shelf_id <> ?)", numeric, s.get("shelf_id")));
                lib.assertShelfSpace(s, incoming, 0);
                // Books moved without explicit coordinates keep rack/row/position, clamped to the target shelf's layout.
                ok[0] = db.update("""
                    UPDATE books SET shelf_id = ?, rack = LPAD(LEAST(CAST(rack AS UNSIGNED), ?), 2, '0'),
                      shelf_row = IF(ORD(shelf_row) - 64 > ?, CHAR(64 + ?), shelf_row) WHERE book_id IN (""" + Js.placeholders(numeric) + ")",
                    s.get("shelf_id"), s.get("racks"), s.get("rows_per_rack"), s.get("rows_per_rack"), numeric);
            });
        } else {
            fail(400, "Unknown bulk action");
        }
        return obj("success", true, "data", obj("ok", ok[0], "failed", failed),
            "message", ok[0] + " book(s) updated" + (failed.isEmpty() ? "" : ", " + failed.size() + " skipped"));
    }

    /* ================================= Authors ================================= */

    private static final String AUTHOR_STATS = """
          SELECT a.*, COUNT(b.book_id) AS book_count, COALESCE(SUM(b.quantity), 0) AS total_copies, COALESCE(SUM(b.times_borrowed), 0) AS times_borrowed
          FROM authors a LEFT JOIN books b ON b.author_id = a.author_id""";

    private Map<String, Object> findAuthor(String ref) {
        boolean pk = Js.isPk(ref);
        Map<String, Object> a = db.row("SELECT * FROM authors WHERE " + (pk ? "author_id" : "author_code") + " = ?", pk ? ref : ref.toUpperCase(Locale.ROOT));
        if (a == null) fail(404, "Author not found");
        return a;
    }

    public Map<String, Object> listAuthors(Input q) {
        boolean search = q.truthy("search");
        List<Map<String, Object>> rows = search
            ? db.rows(AUTHOR_STATS + " WHERE a.name LIKE ? GROUP BY a.author_id ORDER BY a.name", "%" + q.str("search") + "%")
            : db.rows(AUTHOR_STATS + "  GROUP BY a.author_id ORDER BY a.name");
        List<Map<String, Object>> data = rows.stream().map(r -> Js.with(Mappers.authorOut(r),
            "bookCount", Js.jsonNumber(num(r.get("book_count"))),
            "totalCopies", Js.jsonNumber(num(r.get("total_copies"))),
            "timesBorrowed", Js.jsonNumber(num(r.get("times_borrowed"))))).toList();
        return obj("success", true, "data", data);
    }

    public Map<String, Object> getAuthor(String ref) {
        Map<String, Object> a = findAuthor(ref);
        List<Map<String, Object>> books = db.rows(BOOK_SQL + " WHERE b.author_id = ? ORDER BY b.title", a.get("author_id"));
        return obj("success", true, "data", Js.with(Mappers.authorOut(a), "bookCount", books.size()), "books", books.stream().map(Mappers::bookOut).toList());
    }

    public Map<String, Object> createAuthor(Input body) {
        if (body.text("name").strip().isEmpty()) fail(400, "Name is required", details("name", "Name is required"));
        Map<String, Object> data = db.tx(() -> {
            long id = db.insert("INSERT INTO authors (name, biography, country, profile_image) VALUES (?, ?, ?, ?)",
                body.str("name").strip(), defaulted(body, "biography", ""), defaulted(body, "country", ""), defaulted(body, "profileImage", ""));
            lib.assignCode("authors", "author_id", "author_code", "AUT", 3, id);
            return Mappers.authorOut(db.row("SELECT * FROM authors WHERE author_id = ?", id));
        });
        return obj("success", true, "data", data);
    }

    /** Destructuring default: the value, or the default when the key is absent. */
    private static Object defaulted(Input body, String k, Object def) {
        return body.has(k) ? body.raw(k) : def;
    }

    public Map<String, Object> updateAuthor(Input body, String ref) {
        Map<String, Object> a = findAuthor(ref);
        if (body.has("name") && Js.str(body.get("name")).strip().isEmpty()) fail(400, "Name is required", details("name", "Name is required"));
        db.update("""
            UPDATE authors SET name = COALESCE(?, name), biography = COALESCE(?, biography), country = COALESCE(?, country),
              profile_image = COALESCE(?, profile_image) WHERE author_id = ?""",
            body.truthy("name") ? body.str("name").strip() : null, body.raw("biography"), body.raw("country"), body.raw("profileImage"), a.get("author_id"));
        return obj("success", true, "data", Mappers.authorOut(db.row("SELECT * FROM authors WHERE author_id = ?", a.get("author_id"))));
    }

    public Map<String, Object> deleteAuthor(String ref) {
        Map<String, Object> a = findAuthor(ref);
        long n = (long) num(db.value("SELECT COUNT(*) AS n FROM books WHERE author_id = ?", a.get("author_id")));
        if (n > 0) fail(400, "Cannot delete: " + n + " book(s) are linked to this author");
        db.update("DELETE FROM authors WHERE author_id = ?", a.get("author_id"));
        return obj("success", true, "message", "Author deleted");
    }

    /* ================================ Categories ================================ */

    private Map<String, Object> findCategory(String ref) {
        boolean pk = Js.isPk(ref);
        Map<String, Object> c = db.row("SELECT * FROM categories WHERE " + (pk ? "category_id" : "category_code") + " = ?", pk ? ref : ref.toUpperCase(Locale.ROOT));
        if (c == null) fail(404, "Category not found");
        return c;
    }

    public Map<String, Object> listCategories() {
        List<Map<String, Object>> rows = db.rows("""
            SELECT c.*, COUNT(b.book_id) AS titles, COALESCE(SUM(b.quantity), 0) AS total_books, COALESCE(SUM(b.available_copies), 0) AS available_books,
               COALESCE(SUM(b.issued_copies), 0) AS issued_books, COALESCE(SUM(b.reserved_copies), 0) AS reserved_books
            FROM categories c LEFT JOIN books b ON b.category_id = c.category_id GROUP BY c.category_id ORDER BY c.name""");
        List<Map<String, Object>> data = rows.stream().map(r -> Js.with(Mappers.categoryOut(r),
            "titles", Js.jsonNumber(num(r.get("titles"))),
            "totalBooks", Js.jsonNumber(num(r.get("total_books"))),
            "availableBooks", Js.jsonNumber(num(r.get("available_books"))),
            "issuedBooks", Js.jsonNumber(num(r.get("issued_books"))),
            "reservedBooks", Js.jsonNumber(num(r.get("reserved_books"))))).toList();
        return obj("success", true, "data", data);
    }

    public Map<String, Object> getCategory(String ref) {
        Map<String, Object> cat = findCategory(ref);
        List<Map<String, Object>> books = db.rows(BOOK_SQL + " WHERE b.category_id = ? ORDER BY b.title", cat.get("category_id"));
        return obj("success", true, "data", Mappers.categoryOut(cat), "books", books.stream().map(Mappers::bookOut).toList());
    }

    /** Subcategories as a JSON array from a list or a comma-separated string. */
    private static String subcats(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object s : l) if (!Js.str(s).strip().isEmpty()) out.add(Js.str(s).strip());
        } else {
            for (String s : Js.text(v).split(",")) if (!s.strip().isEmpty()) out.add(s.strip());
        }
        return Mappers.toJson(out);
    }

    public Map<String, Object> createCategory(Input body) {
        if (body.text("name").strip().isEmpty()) fail(400, "Name is required", details("name", "Name is required"));
        Object icon = defaulted(body, "icon", "📚");
        Map<String, Object> data = db.tx(() -> {
            long id = db.insert("INSERT INTO categories (name, description, icon, subcategories) VALUES (?, ?, ?, ?)",
                body.str("name").strip(), defaulted(body, "description", ""), truthy(icon) ? icon : "📚", subcats(defaulted(body, "subcategories", List.of())));
            lib.assignCode("categories", "category_id", "category_code", "CAT", 3, id);
            return Mappers.categoryOut(db.row("SELECT * FROM categories WHERE category_id = ?", id));
        });
        return obj("success", true, "data", data);
    }

    public Map<String, Object> updateCategory(Input body, String ref) {
        Map<String, Object> cat = findCategory(ref);
        if (body.has("name") && Js.str(body.get("name")).strip().isEmpty()) fail(400, "Name is required", details("name", "Name is required"));
        db.update("""
            UPDATE categories SET name = COALESCE(?, name), description = COALESCE(?, description), icon = COALESCE(?, icon),
              subcategories = COALESCE(?, subcategories) WHERE category_id = ?""",
            body.truthy("name") ? body.str("name").strip() : null, body.raw("description"), body.truthy("icon") ? body.raw("icon") : null,
            body.has("subcategories") ? subcats(body.raw("subcategories")) : null, cat.get("category_id"));
        return obj("success", true, "data", Mappers.categoryOut(db.row("SELECT * FROM categories WHERE category_id = ?", cat.get("category_id"))));
    }

    public Map<String, Object> deleteCategory(String ref) {
        Map<String, Object> cat = findCategory(ref);
        long n = (long) num(db.value("SELECT COUNT(*) AS n FROM books WHERE category_id = ?", cat.get("category_id")));
        if (n > 0) fail(400, "Cannot delete: " + n + " book(s) belong to this category");
        db.update("DELETE FROM categories WHERE category_id = ?", cat.get("category_id"));
        return obj("success", true, "message", "Category deleted");
    }

    /* ================================= Shelves ================================= */

    public Map<String, Object> listShelves(Input q) {
        List<Map<String, Object>> rows = q.truthy("floor")
            ? db.rows(SHELF_SQL + " WHERE s.floor = ? ORDER BY s.floor, s.section, s.code", q.num("floor"))
            : db.rows(SHELF_SQL + "  ORDER BY s.floor, s.section, s.code");
        return obj("success", true, "data", rows.stream().map(Mappers::shelfOut).toList());
    }

    public Map<String, Object> getShelf(String ref) {
        Map<String, Object> s = lib.findShelfRow(ref);
        List<Map<String, Object>> books = db.rows(BOOK_SQL + " WHERE b.shelf_id = ? ORDER BY b.rack, b.shelf_row, b.position", s.get("shelf_id"));
        return obj("success", true, "data", Mappers.shelfOut(s), "books", books.stream().map(Mappers::bookOut).toList());
    }

    private static Map<String, Object> shelfBody(Input body, boolean partial) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        if (body.has("code")) {
            String code = body.str("code").strip().toUpperCase(Locale.ROOT);
            out.put("code", code);
            if (!code.matches("^[A-Z]{1,3}$")) errors.put("code", "Use 1–3 letters");
        }
        if (body.has("name")) out.put("name", body.str("name").strip());
        if (body.has("section")) out.put("section", body.str("section").strip());
        if (body.has("status")) out.put("status", body.str("status"));
        String[][] numeric = {{"floor", "floor"}, {"racks", "racks"}, {"rowsPerRack", "rows_per_rack"}, {"capacity", "capacity"}};
        for (String[] f : numeric) {
            if (body.has(f[0]) && !"".equals(body.raw(f[0]))) {
                double v = body.num(f[0]);
                out.put(f[1], v);
                boolean whole = !Double.isNaN(v) && !Double.isInfinite(v) && v == Math.rint(v);
                if (!whole || v < (f[0].equals("floor") ? 0 : 1)) errors.put(f[0], "Must be a positive whole number");
            }
        }
        if (out.containsKey("rows_per_rack") && num(out.get("rows_per_rack")) > 26) errors.put("rowsPerRack", "At most 26 rows (A–Z)");
        if (body.has("category")) out.put("category_id", body.truthy("category") ? body.num("category") : null);
        if (!partial) {
            if (!truthy(out.get("code"))) errors.putIfAbsent("code", "Shelf code is required");
            if (!out.containsKey("floor")) errors.put("floor", "Floor is required");
            if (!truthy(out.get("section"))) errors.put("section", "Section is required");
            if (!truthy(out.get("capacity"))) errors.putIfAbsent("capacity", "Capacity is required");
        }
        ApiException.failIfAny(errors);
        return out;
    }

    public Map<String, Object> createShelf(Input body) {
        Map<String, Object> b = shelfBody(body, false);
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("racks", 4);
        cols.put("rows_per_rack", 4);
        cols.put("status", "Active");
        cols.putAll(b);
        cols.put("name", truthy(b.get("name")) ? b.get("name") : "Shelf " + b.get("code"));
        List<String> keys = new ArrayList<>(cols.keySet());
        long id = db.insert("INSERT INTO shelves (" + String.join(", ", keys) + ") VALUES (" + Js.placeholders(keys) + ")", new ArrayList<>(cols.values()));
        return obj("success", true, "data", Mappers.shelfOut(db.row(SHELF_SQL + " WHERE s.shelf_id = ?", id)));
    }

    public Map<String, Object> updateShelf(Input body, String ref) {
        Map<String, Object> s = lib.findShelfRow(ref);
        Map<String, Object> b = shelfBody(body, true);
        double occupied = num(s.get("occupied"));
        if (b.containsKey("capacity") && num(b.get("capacity")) < occupied) {
            String n = Js.numberToString(occupied);
            fail(400, "Capacity cannot be less than the " + n + " books currently stored", details("capacity", "At least " + n));
        }
        if (truthy(b.get("code")) && !b.get("code").equals(s.get("code")) && !truthy(b.get("name"))) b.put("name", "Shelf " + b.get("code"));
        List<String> keys = new ArrayList<>(b.keySet());
        if (!keys.isEmpty()) {
            List<Object> params = new ArrayList<>(b.values());
            params.add(s.get("shelf_id"));
            db.update("UPDATE shelves SET " + String.join(", ", keys.stream().map(k -> k + " = ?").toList()) + " WHERE shelf_id = ?", params);
        }
        return obj("success", true, "data", Mappers.shelfOut(db.row(SHELF_SQL + " WHERE s.shelf_id = ?", s.get("shelf_id"))));
    }

    public Map<String, Object> deleteShelf(String ref) {
        Map<String, Object> s = lib.findShelfRow(ref);
        long titles = (long) num(s.get("titles"));
        if (titles > 0) fail(400, "Cannot delete: " + titles + " book title(s) are stored on " + s.get("name") + ". Move them first.");
        db.update("DELETE FROM shelves WHERE shelf_id = ?", s.get("shelf_id"));
        return obj("success", true, "message", "Shelf deleted");
    }
}
