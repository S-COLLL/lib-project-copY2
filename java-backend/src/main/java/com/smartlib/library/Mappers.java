package com.smartlib.library;

import static com.smartlib.common.Js.UNDEFINED;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.round2;
import static com.smartlib.common.Js.truthy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.smartlib.common.Js;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for the main records and the mappers that turn database rows into the JSON shapes
 * the SmartLib frontend expects (the same field names as frontend/js/demo/demo-api.js).
 */
public final class Mappers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Mappers() {
    }

    /* ---------------------------------- SQL ---------------------------------- */

    public static final String BOOK_SQL = """
          SELECT b.*, a.name AS author_name, c.name AS category_name,
                 s.code AS shelf_code, s.name AS shelf_name, s.floor AS shelf_floor, s.section AS shelf_section
          FROM books b
          JOIN authors a ON a.author_id = b.author_id
          JOIN categories c ON c.category_id = b.category_id
          LEFT JOIN shelves s ON s.shelf_id = b.shelf_id""";

    public static final String MEMBER_SQL = """
          SELECT m.*,
            (SELECT COUNT(*) FROM loans l WHERE l.member_id = m.member_id AND l.status IN ('Issued','Overdue')) AS books_issued,
            (SELECT COUNT(*) FROM loans l WHERE l.member_id = m.member_id AND l.status IN ('Returned','Damaged','Lost')) AS books_returned,
            (SELECT COALESCE(SUM(f.original_amount - f.discount - f.paid_amount - f.waived_amount), 0) FROM fines f
               WHERE f.member_id = m.member_id AND f.status IN ('Pending','Partially Paid')) AS pending_fine,
            (SELECT COALESCE(SUM(p.amount), 0) FROM fine_payments p
               WHERE p.member_id = m.member_id AND p.fine_id IS NOT NULL AND p.status IN ('Paid','Partially Paid')) AS total_fine_paid
          FROM members m""";

    public static final String LOAN_SQL = """
          SELECT l.*, m.member_code, m.name AS member_name, b.book_code, b.title AS book_title, u.name AS staff_name
          FROM loans l
          JOIN members m ON m.member_id = l.member_id
          JOIN books b ON b.book_id = l.book_id
          LEFT JOIN users u ON u.user_id = l.issued_by""";

    public static final String FINE_SQL = """
          SELECT f.*, l.transaction_id AS loan_txn
          FROM fines f LEFT JOIN loans l ON l.loan_id = f.loan_id""";

    public static final String PAYMENT_SQL = """
          SELECT p.*, u.name AS collected_by_name
          FROM fine_payments p LEFT JOIN users u ON u.user_id = p.received_by""";

    public static final String SHELF_SQL = """
          SELECT s.*, c.name AS category_name, c.icon AS category_icon,
            COALESCE((SELECT SUM(b.quantity - b.lost_copies) FROM books b WHERE b.shelf_id = s.shelf_id), 0) AS occupied,
            (SELECT COUNT(*) FROM books b WHERE b.shelf_id = s.shelf_id) AS titles
          FROM shelves s LEFT JOIN categories c ON c.category_id = s.category_id""";

    /* -------------------------------- Helpers -------------------------------- */

    /** A JSON column value (string or already parsed), or the fallback. */
    public static Object parseJson(Object v, Object fallback) {
        if (v == null || v == UNDEFINED) return fallback;
        if (!(v instanceof String s)) return v;
        try {
            return JSON.readValue(s, Object.class);
        } catch (JacksonException e) {
            return fallback;
        }
    }

    public static String toJson(Object v) {
        return JSON.writeValueAsString(v);
    }

    /** A string list from a JSON column (search history, subcategories). */
    public static List<String> stringList(Object v) {
        Object parsed = parseJson(v, List.of());
        List<String> out = new ArrayList<>();
        if (parsed instanceof List<?> l) for (Object o : l) out.add(Js.str(o));
        return out;
    }

    /** Pick only the given keys (plus _id). */
    public static Map<String, Object> sel(Map<String, Object> o, String... keys) {
        if (o == null) return null;
        Map<String, Object> m = obj("_id", o.get("_id"));
        for (String k : keys) if (o.containsKey(k) && o.get(k) != UNDEFINED) m.put(k, o.get(k));
        return m;
    }

    private static Object orEmpty(Object v) {
        return truthy(v) ? v : "";
    }

    private static Object orUndefined(Object v) {
        return truthy(v) ? v : UNDEFINED;
    }

    /* -------------------------------- Mappers -------------------------------- */

    public static Map<String, Object> bookOut(Map<String, Object> r) {
        return bookOut(r, false);
    }

    public static Map<String, Object> bookOut(Map<String, Object> r, boolean shelfObject) {
        if (r == null) return null;
        Object location = null;
        if (truthy(r.get("shelf_id"))) {
            Object shelf = shelfObject
                ? obj("_id", id(r.get("shelf_id")), "shelfId", "SH-" + r.get("shelf_code"), "name", r.get("shelf_name"), "code", r.get("shelf_code"))
                : id(r.get("shelf_id"));
            location = obj("shelf", shelf, "shelfCode", r.get("shelf_code"), "floor", r.get("shelf_floor"), "section", r.get("shelf_section"),
                "rack", r.get("rack"), "row", r.get("shelf_row"), "position", r.get("position"));
        }
        return obj(
            "_id", id(r.get("book_id")),
            "bookId", r.get("book_code"),
            "title", r.get("title"),
            "isbn", r.get("isbn"),
            "author", id(r.get("author_id")),
            "authorName", r.get("author_name"),
            "publisher", r.get("publisher"),
            "publicationDate", r.get("publication_date"),
            "edition", r.get("edition"),
            "language", r.get("language"),
            "category", id(r.get("category_id")),
            "categoryName", r.get("category_name"),
            "subcategory", r.get("subcategory"),
            "description", orEmpty(r.get("description")),
            "coverImage", orEmpty(r.get("cover_image")),
            "pages", r.get("pages"),
            "price", r.get("price"),
            "purchasePrice", r.get("purchase_price"),
            "currentValue", r.get("current_value"),
            "quantity", r.get("quantity"),
            "availableCopies", r.get("available_copies"),
            "issuedCopies", r.get("issued_copies"),
            "reservedCopies", r.get("reserved_copies"),
            "lostCopies", r.get("lost_copies"),
            "damagedCopies", r.get("damaged_copies"),
            "status", r.get("status"),
            "timesBorrowed", r.get("times_borrowed"),
            "location", location,
            "addedBy", id(r.get("added_by")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> memberOut(Map<String, Object> r) {
        if (r == null) return null;
        return obj(
            "_id", id(r.get("member_id")),
            "memberId", r.get("member_code"),
            "name", r.get("name"),
            "email", r.get("email"),
            "phone", orEmpty(r.get("phone")),
            "neptunCode", orEmpty(r.get("neptun_code")),
            "profilePhoto", orEmpty(r.get("profile_photo")),
            "department", r.get("department"),
            "course", r.get("course"),
            "year", r.get("year"),
            "membershipType", r.get("membership_type"),
            "membershipStart", r.get("membership_start"),
            "membershipExpiry", r.get("membership_expiry"),
            "booksIssued", (long) num(truthy(r.get("books_issued")) ? r.get("books_issued") : 0),
            "booksReturned", (long) num(truthy(r.get("books_returned")) ? r.get("books_returned") : 0),
            "pendingFine", round2(r.get("pending_fine")),
            "totalFinePaid", round2(r.get("total_fine_paid")),
            "status", r.get("status"),
            "expiryNotified", truthy(r.get("expiry_notified")),
            "user", id(r.get("user_id")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> issueOut(Map<String, Object> r) {
        if (r == null) return null;
        return obj(
            "_id", id(r.get("loan_id")),
            "transactionId", r.get("transaction_id"),
            "member", id(r.get("member_id")),
            "book", id(r.get("book_id")),
            "memberCode", r.get("member_code"),
            "memberName", r.get("member_name"),
            "bookCode", r.get("book_code"),
            "bookTitle", r.get("book_title"),
            "issueDate", r.get("issue_date"),
            "dueDate", r.get("due_date"),
            "returnDate", r.get("return_date"),
            "renewalCount", r.get("renewal_count"),
            "staff", id(r.get("issued_by")),
            "staffName", orEmpty(r.get("staff_name")),
            "status", r.get("status"),
            "fineAmount", r.get("fine_amount"),
            "dueSoonNotified", truthy(r.get("due_soon_notified")),
            "overdueNotified", truthy(r.get("overdue_notified")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> returnOut(Map<String, Object> r) {
        if (r == null) return null;
        return obj(
            "_id", id(r.get("loan_id")),
            "returnId", r.get("return_code"),
            "issue", id(r.get("loan_id")),
            "member", id(r.get("member_id")),
            "book", id(r.get("book_id")),
            "transactionId", r.get("transaction_id"),
            "issueDate", r.get("issue_date"),
            "dueDate", r.get("due_date"),
            "returnDate", r.get("return_date"),
            "daysOverdue", r.get("days_overdue"),
            "condition", r.get("return_condition"),
            "lateFine", r.get("late_fine"),
            "damageCharge", r.get("damage_charge"),
            "lostCharge", r.get("lost_charge"),
            "totalFine", r.get("fine_amount"),
            "remarks", r.get("remarks"),
            "processedBy", id(r.get("processed_by")),
            "createdAt", r.get("return_date"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> reservationOut(Map<String, Object> r) {
        if (r == null) return null;
        return obj(
            "_id", id(r.get("reservation_id")),
            "reservationId", r.get("reservation_code"),
            "member", id(r.get("member_id")),
            "book", id(r.get("book_id")),
            "reservationDate", r.get("reservation_date"),
            "queuePosition", r.get("queue_position"),
            "expiryDate", r.get("expiry_date"),
            "availableSince", r.get("available_since"),
            "status", r.get("status"),
            "createdBy", id(r.get("created_by")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    /** original - discount - paid - waived, never below zero. */
    public static double fineRemaining(Map<String, Object> f) {
        return round2(Math.max(0, num(f.get("original_amount")) - num(f.get("discount")) - num(f.get("paid_amount")) - num(f.get("waived_amount"))));
    }

    public static Map<String, Object> fineOut(Map<String, Object> r) {
        if (r == null) return null;
        Object txn = truthy(r.get("loan_txn")) ? r.get("loan_txn") : orUndefined(r.get("transaction_ref"));
        return obj(
            "_id", id(r.get("fine_id")),
            "fineId", r.get("fine_code"),
            "member", id(r.get("member_id")),
            "issue", id(r.get("loan_id")),
            "book", id(r.get("book_id")),
            "transactionId", txn,
            "type", r.get("type"),
            "description", r.get("description"),
            "daysOverdue", r.get("days_overdue"),
            "ratePerDay", r.get("rate_per_day"),
            "originalAmount", r.get("original_amount"),
            "discount", r.get("discount"),
            "paidAmount", r.get("paid_amount"),
            "waivedAmount", r.get("waived_amount"),
            "remainingAmount", fineRemaining(r),
            "status", r.get("status"),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> paymentOut(Map<String, Object> r) {
        if (r == null) return null;
        return obj(
            "_id", id(r.get("payment_id")),
            "paymentId", r.get("payment_code"),
            "receiptNo", orUndefined(r.get("receipt_no")),
            "member", id(r.get("member_id")),
            "fine", id(r.get("fine_id")),
            "transactionId", orUndefined(r.get("transaction_ref")),
            "reason", r.get("reason"),
            "amount", r.get("amount"),
            "date", r.get("payment_date"),
            "method", r.get("method"),
            "status", r.get("status"),
            "reference", r.get("reference"),
            "notes", r.get("notes"),
            "collectedBy", id(r.get("received_by")),
            "collectedByName", orEmpty(r.get("collected_by_name")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> shelfOut(Map<String, Object> r) {
        if (r == null) return null;
        double occupied = num(r.get("occupied"));
        double capacity = num(r.get("capacity"));
        Object category = truthy(r.get("category_id"))
            ? obj("_id", id(r.get("category_id")), "name", r.get("category_name"), "icon", r.get("category_icon"))
            : UNDEFINED;
        return obj(
            "_id", id(r.get("shelf_id")),
            "id", id(r.get("shelf_id")),
            "shelfId", "SH-" + r.get("code"),
            "code", r.get("code"),
            "name", r.get("name"),
            "floor", r.get("floor"),
            "section", r.get("section"),
            "racks", r.get("racks"),
            "rowsPerRack", r.get("rows_per_rack"),
            "category", category,
            "capacity", r.get("capacity"),
            "occupied", Js.jsonNumber(occupied),
            "availableSpace", Js.jsonNumber(Math.max(0, capacity - occupied)),
            "occupancyPercent", capacity != 0 ? Math.min(100, Js.round((occupied / capacity) * 100)) : 0,
            "status", r.get("status"),
            "titles", Js.jsonNumber(num(r.get("titles"))),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> authorOut(Map<String, Object> r) {
        return obj(
            "_id", id(r.get("author_id")),
            "authorId", r.get("author_code"),
            "name", r.get("name"),
            "biography", orEmpty(r.get("biography")),
            "country", r.get("country"),
            "profileImage", orEmpty(r.get("profile_image")),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }

    public static Map<String, Object> categoryOut(Map<String, Object> r) {
        return obj(
            "_id", id(r.get("category_id")),
            "categoryId", r.get("category_code"),
            "name", r.get("name"),
            "description", r.get("description"),
            "icon", r.get("icon"),
            "subcategories", parseJson(r.get("subcategories"), List.of()),
            "createdAt", r.get("created_at"),
            "updatedAt", r.get("updated_at"));
    }
}
