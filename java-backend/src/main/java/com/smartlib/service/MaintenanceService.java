package com.smartlib.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smartlib.common.Db;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Settings;

/**
 * Marks overdue loans, sends due-soon/overdue notices, expires memberships and lapsed reservations
 * (passing the copy to the next in the queue). Runs at start-up, every hour and before the dashboard.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final Db db;
    private final Library lib;

    public MaintenanceService(Db db, Library lib) {
        this.db = db;
        this.lib = lib;
    }

    @Scheduled(initialDelay = 5_000, fixedRate = 3_600_000)
    public void hourly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Maintenance failed: {}", e.getMessage());
        }
    }

    public void run() {
        db.tx(() -> {
            Settings s = lib.settings();
            Instant today = JsDate.startOfDay(JsDate.now());

            for (Map<String, Object> i : db.rows("""
                SELECT l.loan_id, l.member_id, l.due_date, l.transaction_id, l.overdue_notified, b.title, m.name
                FROM loans l JOIN books b ON b.book_id = l.book_id JOIN members m ON m.member_id = l.member_id
                WHERE l.status = 'Issued' AND l.due_date < ? FOR UPDATE""", today)) {
                db.update("UPDATE loans SET status = 'Overdue', overdue_notified = 1 WHERE loan_id = ?", i.get("loan_id"));
                if (!Js.truthy(i.get("overdue_notified"))) {
                    lib.notifyMember(i.get("member_id"), "overdue", "Book overdue",
                        "\"" + i.get("title") + "\" was due on " + JsDate.toDateString(JsDate.parse(i.get("due_date"))) + ". A fine of "
                            + Js.money(s.finePerDay()) + "/day applies.", "issues.html");
                    lib.notify("staff", null, "overdue", "Book overdue",
                        i.get("name") + " has not returned \"" + i.get("title") + "\" (" + i.get("transaction_id") + ").", "returns.html");
                }
            }

            for (Map<String, Object> i : db.rows("""
                SELECT l.loan_id, l.member_id, l.due_date, b.title FROM loans l JOIN books b ON b.book_id = l.book_id
                WHERE l.status = 'Issued' AND l.due_soon_notified = 0 AND l.due_date >= ? AND l.due_date <= ?""",
                today, JsDate.addDays(today, s.n("dueSoonDays") + 1))) {
                db.update("UPDATE loans SET due_soon_notified = 1 WHERE loan_id = ?", i.get("loan_id"));
                lib.notifyMember(i.get("member_id"), "due-soon", "Book due soon",
                    "\"" + i.get("title") + "\" is due on " + JsDate.toDateString(JsDate.parse(i.get("due_date"))) + ". Return or renew it to avoid fines.",
                    "issues.html");
            }

            for (Map<String, Object> m : db.rows("SELECT member_id FROM members WHERE status = 'Active' AND membership_expiry < NOW()")) {
                db.update("UPDATE members SET status = 'Expired', expiry_notified = 1 WHERE member_id = ?", m.get("member_id"));
                lib.notifyMember(m.get("member_id"), "membership", "Membership expired",
                    "Your library membership has expired. Renew it at the desk to keep borrowing.", "");
            }

            List<Map<String, Object>> lapsed = db.rows(
                "SELECT reservation_id, book_id FROM reservations WHERE status IN ('Available','Waiting') AND expiry_date IS NOT NULL AND expiry_date < NOW() FOR UPDATE");
            Set<Object> touched = new LinkedHashSet<>();
            for (Map<String, Object> r : lapsed) {
                db.update("UPDATE reservations SET status = 'Expired' WHERE reservation_id = ?", r.get("reservation_id"));
                touched.add(r.get("book_id"));
            }
            for (Object bookId : touched) lib.processReservationQueue(bookId, s);
        });
    }
}
