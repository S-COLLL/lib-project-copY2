package com.smartlib.service;

import static com.smartlib.common.ApiException.details;
import static com.smartlib.common.ApiException.fail;
import static com.smartlib.common.Js.obj;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.smartlib.common.ApiException;
import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Mappers;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.JwtService;

/** Sign-in, student self-registration, profile and the admin's user management. */
@Service
public class AuthService {

    public static final List<String> ROLES = List.of("admin", "librarian", "staff", "student");
    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^\\S+@\\S+\\.\\S+$");
    private static final Pattern EMAIL = Pattern.compile(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[+\\d][\\d\\s-]{7,15}$");

    private final Db db;
    private final Library lib;
    private final JwtService jwt;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    public AuthService(Db db, Library lib, JwtService jwt) {
        this.db = db;
        this.lib = lib;
        this.jwt = jwt;
    }

    public static boolean validEmail(Object e) {
        return SIMPLE_EMAIL.matcher(Js.text(e)).matches();
    }

    public boolean passwordMatches(String password, Object hash) {
        try {
            return hash != null && bcrypt.matches(password, Js.str(hash));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String hash(String password) {
        return bcrypt.encode(password);
    }

    /* ------------------------------- Validation ------------------------------- */

    /** Field rules checked before the handler runs; answers 400 with per-field details. */
    private static void check(Map<String, String> errors) {
        if (!errors.isEmpty()) throw new ApiException(400, errors.values().iterator().next(), errors);
    }

    /** isString().notEmpty().withMessage(m): a non-string fails with the validator's default "Invalid value". */
    private static void requiredString(Map<String, String> errors, Input body, String field, String message) {
        if (!(body.raw(field) instanceof String s)) errors.put(field, "Invalid value");
        else if (s.isEmpty()) errors.put(field, message);
    }

    private static boolean lengthBetween(Object v, int min, int max) {
        String s = v == null || v == Js.UNDEFINED ? "" : Js.str(v);
        int n = s.codePointCount(0, s.length());
        return n >= min && n <= max;
    }

    /* --------------------------------- Login --------------------------------- */

    /** POST /auth/login { email | identifier, password } — the identifier may be an email or a numeric user ID. */
    public Map<String, Object> login(Input body) {
        Map<String, String> errors = new LinkedHashMap<>();
        requiredString(errors, body, "password", "Password is required");
        Object identRaw = Js.truthy(body.get("email")) ? body.get("email") : body.get("identifier");
        if (Js.text(identRaw).strip().isEmpty()) errors.putIfAbsent("", "Email (or user ID) is required");
        check(errors);

        String ident = Js.text(identRaw).strip().toLowerCase(Locale.ROOT);
        String password = body.text("password");
        if (ident.isEmpty() || password.isEmpty()) fail(400, "Email (or user ID) and password are required");
        Map<String, Object> u = db.row("SELECT * FROM users WHERE " + (ident.matches("^\\d+$") ? "user_id" : "email") + " = ?", ident);
        if (u == null || !passwordMatches(password, u.get("password_hash"))) fail(401, "Invalid email or password");
        if (!Js.truthy(u.get("is_active"))) fail(403, "This account has been disabled");
        db.update("UPDATE users SET last_login = NOW() WHERE user_id = ?", u.get("user_id"));
        u = new LinkedHashMap<>(u);
        u.put("last_login", JsDate.now());
        return obj("success", true, "token", jwt.sign(((Number) u.get("user_id")).intValue()), "user", lib.userOut(u));
    }

    /** POST /auth/register — student self-registration; links to an existing member with the same email. */
    public Map<String, Object> register(Input body) {
        String nameIn = body.has("name") && body.raw("name") != null ? Js.str(body.raw("name")).strip() : "";
        String emailIn = body.has("email") && body.raw("email") != null ? Js.str(body.raw("email")).strip() : "";
        Map<String, String> errors = new LinkedHashMap<>();
        if (!lengthBetween(nameIn, 2, 120)) errors.put("name", "Name must be at least 2 characters");
        if (!EMAIL.matcher(emailIn).matches()) errors.put("email", "Email is invalid");
        if (!lengthBetween(body.raw("password"), 6, 128)) errors.put("password", "Password must be at least 6 characters");
        if (body.truthy("phone") && !PHONE.matcher(body.str("phone")).matches()) errors.put("phone", "Phone number is invalid");
        check(errors);

        String password = body.str("password");
        String mail = emailIn.toLowerCase(Locale.ROOT);
        String name = nameIn;
        long userId = db.tx(() -> {
            if (db.row("SELECT user_id FROM users WHERE email = ?", mail) != null) fail(409, "An account with this email already exists");
            long uid = db.insert("INSERT INTO users (name, email, password_hash, role, search_history) VALUES (?, ?, ?, 'student', '[]')",
                name, mail, hash(password));
            Map<String, Object> m = db.row("SELECT member_id, member_code FROM members WHERE email = ? FOR UPDATE", mail);
            String memberCode;
            if (m != null) {
                db.update("UPDATE members SET user_id = ? WHERE member_id = ?", uid, m.get("member_id"));
                memberCode = Js.str(m.get("member_code"));
            } else {
                long mid = db.insert("""
                    INSERT INTO members (user_id, name, email, phone, department, course, year, membership_type, membership_start, membership_expiry)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'Student', NOW(), DATE_ADD(NOW(), INTERVAL 365 DAY))""",
                    uid, name, mail, body.truthy("phone") ? body.raw("phone") : null,
                    body.truthy("department") ? body.raw("department") : "", body.truthy("course") ? body.raw("course") : "",
                    body.truthy("year") ? body.raw("year") : "");
                memberCode = lib.assignCode("members", "member_id", "member_code", "MEM", 4, mid);
            }
            lib.notifyStaff("New member registered", name + " (" + memberCode + ") created an account.", "members.html");
            return uid;
        });
        Map<String, Object> u = db.row("SELECT * FROM users WHERE user_id = ?", userId);
        return obj("success", true, "token", jwt.sign((int) userId), "user", lib.userOut(u));
    }

    public Map<String, Object> me(CurrentUser user) {
        return obj("success", true, "user", lib.userOut(user.row()));
    }

    /** PUT /auth/profile { name, avatar } */
    public Map<String, Object> updateProfile(CurrentUser user, Input body) {
        if (body.has("name") && Js.str(body.get("name")).strip().isEmpty()) fail(400, "Name is required", details("name", "Name is required"));
        db.tx(() -> {
            if (body.has("name")) db.update("UPDATE users SET name = ? WHERE user_id = ?", Js.str(body.get("name")).strip(), user.userId());
            if (body.has("avatar")) db.update("UPDATE users SET avatar = ? WHERE user_id = ?", body.text("avatar"), user.userId());
            if (user.memberId() != null) {
                if (body.has("name")) db.update("UPDATE members SET name = ? WHERE member_id = ?", Js.str(body.get("name")).strip(), user.memberId());
                if (body.has("avatar")) db.update("UPDATE members SET profile_photo = ? WHERE member_id = ?", body.text("avatar"), user.memberId());
            }
        });
        return obj("success", true, "user", lib.userOut(db.row("SELECT * FROM users WHERE user_id = ?", user.userId())));
    }

    /** PATCH /auth/password { currentPassword, newPassword } */
    public Map<String, Object> changePassword(CurrentUser user, Input body) {
        Map<String, String> errors = new LinkedHashMap<>();
        requiredString(errors, body, "currentPassword", "Current password is required");
        if (!lengthBetween(body.raw("newPassword"), 6, 128)) errors.put("newPassword", "New password must be at least 6 characters");
        check(errors);
        if (!passwordMatches(body.text("currentPassword"), user.row().get("password_hash"))) {
            fail(400, "Current password is incorrect", details("currentPassword", "Current password is incorrect"));
        }
        if (body.text("newPassword").length() < 6) fail(400, "New password must be at least 6 characters", details("newPassword", "At least 6 characters"));
        db.update("UPDATE users SET password_hash = ? WHERE user_id = ?", hash(body.str("newPassword")), user.userId());
        return obj("success", true, "message", "Password updated");
    }

    /** POST /auth/search-history { term } */
    public Map<String, Object> addSearchHistory(CurrentUser user, Input body) {
        String term = Js.cut(body.text("term").strip(), 60);
        List<String> history = Mappers.stringList(user.row().get("search_history"));
        if (term.length() >= 2) {
            List<String> next = new ArrayList<>();
            next.add(term);
            for (String t : history) if (!t.toLowerCase(Locale.ROOT).equals(term.toLowerCase(Locale.ROOT))) next.add(t);
            history = next.size() > 20 ? next.subList(0, 20) : next;
            db.update("UPDATE users SET search_history = ? WHERE user_id = ?", Mappers.toJson(history), user.userId());
        }
        return obj("success", true, "searchHistory", history);
    }

    /** GET /auth/users (admin) */
    public Map<String, Object> listUsers() {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> u : db.rows("""
            SELECT u.*, m.member_id, m.member_code, m.name AS member_name FROM users u LEFT JOIN members m ON m.user_id = u.user_id
            ORDER BY u.created_at DESC, u.user_id DESC""")) {
            data.add(obj(
                "_id", Js.id(u.get("user_id")),
                "name", u.get("name"),
                "email", u.get("email"),
                "role", u.get("role"),
                "avatar", Js.truthy(u.get("avatar")) ? u.get("avatar") : "",
                "isActive", Js.truthy(u.get("is_active")),
                "lastLogin", u.get("last_login"),
                "member", Js.truthy(u.get("member_id"))
                    ? obj("_id", Js.id(u.get("member_id")), "memberId", u.get("member_code"), "name", u.get("member_name"))
                    : Js.UNDEFINED,
                "createdAt", u.get("created_at"),
                "updatedAt", u.get("updated_at")));
        }
        return obj("success", true, "data", data);
    }

    /** POST /auth/users (admin) { name, email, password, role } */
    public Map<String, Object> createUser(Input body) {
        String nameIn = body.raw("name") == null ? "" : Js.str(body.raw("name")).strip();
        String emailIn = body.raw("email") == null ? "" : Js.str(body.raw("email")).strip();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!lengthBetween(nameIn, 2, 120)) errors.put("name", "Name is required");
        if (!EMAIL.matcher(emailIn).matches()) errors.put("email", "Email is invalid");
        if (!lengthBetween(body.raw("password"), 6, 128)) errors.put("password", "Password must be at least 6 characters");
        if (!ROLES.contains(Js.str(body.get("role")))) errors.put("role", "Role is required");
        check(errors);

        String role = body.str("role");
        String mail = emailIn.toLowerCase(Locale.ROOT);
        long userId = db.tx(() -> {
            if (db.row("SELECT user_id FROM users WHERE email = ?", mail) != null) fail(409, "An account with this email already exists");
            Map<String, Object> member = null;
            if (role.equals("student")) {
                member = db.row("SELECT member_id, user_id FROM members WHERE email = ? FOR UPDATE", mail);
                if (member == null) fail(400, "Create the library member first; student accounts must match a member email");
                if (Js.truthy(member.get("user_id"))) fail(409, "This member already has a login");
            }
            long uid = db.insert("INSERT INTO users (name, email, password_hash, role, search_history) VALUES (?, ?, ?, ?, '[]')",
                nameIn, mail, hash(body.str("password")), role);
            if (member != null) db.update("UPDATE members SET user_id = ? WHERE member_id = ?", uid, member.get("member_id"));
            return uid;
        });
        return obj("success", true, "data", lib.userOut(db.row("SELECT * FROM users WHERE user_id = ?", userId)));
    }

    /** PATCH /auth/users/:id (admin) { name, role, isActive, password } */
    public Map<String, Object> updateUser(CurrentUser user, String idParam, Input body) {
        double target = Js.num(idParam);
        Map<String, Object> u = Double.isNaN(target) ? null : db.row("SELECT * FROM users WHERE user_id = ?", target);
        if (u == null) fail(404, "User not found");
        Object role = body.get("role");
        Object isActive = body.get("isActive");
        Object password = body.get("password");
        boolean self = (int) target == user.userId();
        if (self && ((Js.truthy(role) && !Js.str(role).equals(Js.str(u.get("role")))) || Boolean.FALSE.equals(isActive))) {
            fail(400, "You cannot change your own role or disable your own account");
        }
        if (role != Js.UNDEFINED && !ROLES.contains(Js.str(role))) fail(400, "Invalid role");
        if (password != Js.UNDEFINED && Js.str(password).length() < 6) fail(400, "Password must be at least 6 characters");
        db.update("""
            UPDATE users SET name = COALESCE(?, name), role = COALESCE(?, role), is_active = COALESCE(?, is_active),
              password_hash = COALESCE(?, password_hash) WHERE user_id = ?""",
            body.truthy("name") ? Js.str(body.get("name")).strip() : null,
            Js.truthy(role) ? Js.str(role) : null,
            isActive == Js.UNDEFINED ? null : Js.truthy(isActive) ? 1 : 0,
            Js.truthy(password) ? hash(Js.str(password)) : null,
            target);
        return obj("success", true, "data", lib.userOut(db.row("SELECT * FROM users WHERE user_id = ?", target)));
    }
}
