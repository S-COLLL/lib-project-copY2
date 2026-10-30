package com.smartlib.service;

import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.id;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.smartlib.common.ApiException;
import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.common.JsonOut;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Page;
import com.smartlib.library.Library.Settings;
import com.smartlib.security.CurrentUser;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Notifications (with per-user read state), library settings and JSON backups. */
@Service
public class NotificationService {

    private static final List<String> BACKUP_TABLES = List.of("users", "members", "authors", "categories", "shelves", "books", "loans", "reservations",
        "fines", "fine_payments", "notifications", "notification_reads", "settings");

    private final Db db;
    private final Library lib;
    private final String databaseName;

    public NotificationService(Db db, Library lib, @Value("${MYSQL_DATABASE:library_app}") String databaseName) {
        this.db = db;
        this.lib = lib;
        this.databaseName = databaseName;
    }

    private record Visible(String sql, List<Object> params) {
    }

    /** Notifications a user may see: everyone's, staff-wide (for staff) and their own member ones. */
    private static Visible visibleWhere(CurrentUser user) {
        List<String> parts = new ArrayList<>(List.of("n.audience = 'all'"));
        List<Object> params = new ArrayList<>();
        if (user.isStaff()) parts.add("n.audience = 'staff'");
        if (user.memberId() != null) {
            parts.add("(n.audience = 'member' AND n.member_id = ?)");
            params.add(user.memberId());
        }
        return new Visible("(" + String.join(" OR ", parts) + ")", params);
    }

    private long unreadCount(CurrentUser user) {
        Visible v = visibleWhere(user);
        List<Object> params = new ArrayList<>(v.params());
        params.add(user.userId());
        return (long) num(db.value("""
            SELECT COUNT(*) AS n FROM notifications n
            WHERE""" + " " + v.sql() + " AND NOT EXISTS (SELECT 1 FROM notification_reads r WHERE r.notification_id = n.notification_id AND r.user_id = ?)", params));
    }

    public Map<String, Object> listNotifications(CurrentUser user, Input q) {
        Visible v = visibleWhere(user);
        List<String> where = new ArrayList<>(List.of(v.sql()));
        List<Object> params = new ArrayList<>(v.params());
        if (q.truthy("type")) {
            where.add("n.type = ?");
            params.add(q.str("type"));
        }
        if ("true".equals(q.raw("unread"))) where.add("r.user_id IS NULL");
        String from = "FROM notifications n LEFT JOIN notification_reads r ON r.notification_id = n.notification_id AND r.user_id = ?";
        Page pg = Library.paginate(q, 20, 100);
        List<Object> countParams = new ArrayList<>();
        countParams.add(user.userId());
        countParams.addAll(params);
        long total = (long) num(db.value("SELECT COUNT(*) AS total " + from + " WHERE " + String.join(" AND ", where), countParams));
        List<Object> pageParams = new ArrayList<>(countParams);
        pageParams.add(pg.limit());
        pageParams.add(pg.offset());
        List<Map<String, Object>> rows = db.rows("SELECT n.*, r.user_id AS read_by " + from + " WHERE " + String.join(" AND ", where)
            + " ORDER BY n.created_at DESC, n.notification_id DESC LIMIT ? OFFSET ?", pageParams);
        List<Map<String, Object>> data = rows.stream().map(n -> obj(
            "_id", id(n.get("notification_id")),
            "audience", n.get("audience"),
            "member", id(n.get("member_id")),
            "type", n.get("type"),
            "title", n.get("title"),
            "message", n.get("message"),
            "link", n.get("link"),
            "read", Js.truthy(n.get("read_by")),
            "createdAt", n.get("created_at"),
            "updatedAt", n.get("updated_at"))).toList();
        return obj("success", true, "data", data, "unread", unreadCount(user), "pagination", Library.pageInfo(pg, total));
    }

    public Map<String, Object> unread(CurrentUser user) {
        return obj("success", true, "unread", unreadCount(user));
    }

    public Map<String, Object> readAll(CurrentUser user) {
        Visible v = visibleWhere(user);
        List<Object> params = new ArrayList<>();
        params.add(user.userId());
        params.addAll(v.params());
        int updated = db.update("INSERT IGNORE INTO notification_reads (notification_id, user_id) SELECT n.notification_id, ? FROM notifications n WHERE " + v.sql(), params);
        return obj("success", true, "updated", updated);
    }

    public Map<String, Object> readOne(CurrentUser user, String idParam) {
        Visible v = visibleWhere(user);
        List<Object> params = new ArrayList<>();
        params.add(Js.num(idParam));
        params.addAll(v.params());
        Map<String, Object> n = db.row("SELECT n.notification_id FROM notifications n WHERE n.notification_id = ? AND " + v.sql(), params);
        if (n == null) fail(404, "Notification not found");
        db.update("INSERT IGNORE INTO notification_reads (notification_id, user_id) VALUES (?, ?)", n.get("notification_id"), user.userId());
        return obj("success", true);
    }

    /** POST /notifications { audience, member, title, message, link } — staff broadcast. */
    public Map<String, Object> broadcast(Input body) {
        String audience = body.has("audience") ? Js.str(body.raw("audience")) : "all";
        Object title = body.get("title");
        Object message = body.get("message");
        Map<String, String> errors = new LinkedHashMap<>();
        if (Js.text(title).strip().isEmpty()) errors.put("title", "Title is required");
        if (Js.text(message).strip().isEmpty()) errors.put("message", "Message is required");
        if (!List.of("all", "staff", "member").contains(audience)) errors.put("audience", "Invalid audience");
        ApiException.failIfAny(errors);
        Object memberId = audience.equals("member") ? lib.findMemberRow(body.raw("member")).get("member_id") : null;
        String link = body.has("link") ? Js.str(body.raw("link")) : "";
        lib.notify(audience, memberId, "system", Js.str(title), Js.str(message), link);
        Map<String, Object> n = db.row("SELECT * FROM notifications ORDER BY notification_id DESC LIMIT 1");
        return obj("success", true, "data", obj(
            "_id", id(n.get("notification_id")),
            "audience", n.get("audience"),
            "member", id(n.get("member_id")),
            "type", n.get("type"),
            "title", n.get("title"),
            "message", n.get("message"),
            "link", n.get("link"),
            "createdAt", n.get("created_at")));
    }

    public Map<String, Object> deleteNotification(String idParam) {
        db.update("DELETE FROM notifications WHERE notification_id = ?", Js.num(idParam));
        return obj("success", true, "message", "Notification deleted");
    }

    /* ================================= Settings ================================= */

    public Map<String, Object> getSettings() {
        return obj("success", true, "data", lib.settings().values());
    }

    /** PUT /settings — admin only; only known keys, numeric ones must be non-negative. */
    public Map<String, Object> updateSettings(Input body) {
        Map<String, Object> entries = new LinkedHashMap<>();
        body.asMap().forEach((k, v) -> {
            if (Library.SETTING_DEFAULTS.containsKey(k)) entries.put(k, v);
        });
        if (entries.isEmpty()) fail(400, "No settings to update");
        Map<String, String> errors = new LinkedHashMap<>();
        entries.forEach((k, v) -> {
            if (Library.TEXT_SETTINGS.contains(k)) {
                if (Js.str(v).strip().isEmpty()) errors.put(k, "Required");
            } else if ("".equals(v) || Double.isNaN(Js.num(v)) || Js.num(v) < 0) {
                errors.put(k, "Must be a positive number");
            }
        });
        for (String k : List.of("loanDays", "maxBooksPerMember", "reservationHoldDays")) {
            if (entries.containsKey(k) && Js.num(entries.get(k)) < 1) {
                errors.putIfAbsent("loanDays", "Must be at least 1");
                break;
            }
        }
        if (!errors.isEmpty()) {
            Map.Entry<String, String> first = errors.entrySet().iterator().next();
            throw new ApiException(400, first.getKey() + ": " + first.getValue(), errors);
        }
        entries.forEach((k, v) -> db.update(
            "INSERT INTO settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)", k, Js.str(v).strip()));
        return obj("success", true, "data", lib.settings().values(), "message", "Settings saved");
    }

    /** POST /settings/backup — admin only; JSON dump of every table into backupPath. */
    public Map<String, Object> backup() {
        Settings s = lib.settings();
        Path dir = Path.of(System.getProperty("user.dir")).resolve(s.text("backupPath")).normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException e) {
            fail(400, "Cannot create the backup folder \"" + s.text("backupPath") + "\"");
        }
        Map<String, Object> tables = new LinkedHashMap<>();
        int rows = 0;
        for (String t : BACKUP_TABLES) {
            List<Map<String, Object>> data = db.rows("SELECT * FROM " + t);
            tables.put(t, data);
            rows += data.size();
        }
        Instant now = JsDate.now();
        Map<String, Object> dump = obj("createdAt", JsDate.iso(now), "database", databaseName, "tables", tables);
        Path file = dir.resolve("library-backup-" + JsDate.iso(now).replaceAll("[:.]", "-") + ".json");
        try {
            JsonMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
            Files.writeString(file, pretty.writeValueAsString(JsonOut.normalize(dump)));
        } catch (IOException | RuntimeException e) {
            fail(400, "Cannot write the backup file in \"" + s.text("backupPath") + "\"");
        }
        return obj("success", true, "data", obj("file", file.toString(), "tables", BACKUP_TABLES.size(), "rows", rows), "message", "Backup written to " + file);
    }
}
