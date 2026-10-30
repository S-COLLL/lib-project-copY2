package com.smartlib.security;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.smartlib.common.ApiException;
import com.smartlib.common.Db;
import com.smartlib.common.Js;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Verifies the Bearer token on every /api request except the public ones, loads the user
 * (with the linked membership id) and stores it as the request attribute "currentUser".
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "currentUser";
    private static final Set<String> PUBLIC = Set.of("/api/health", "/api/auth/login", "/api/auth/register");

    private final JwtService jwt;
    private final Db db;

    public AuthInterceptor(JwtService jwt, Db db) {
        this.jwt = jwt;
        this.db = db;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Unknown /api paths fall through to the 404 handler without asking for a login.
        if (!(handler instanceof HandlerMethod) || PUBLIC.contains(request.getRequestURI())) return true;
        if ("OPTIONS".equals(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new ApiException(401, "Not authenticated. Please log in.");
        Integer userId = jwt.verify(header.substring(7));
        if (userId == null) throw new ApiException(401, "Session expired. Please log in again.");

        Map<String, Object> row = db.row(
            "SELECT u.*, m.member_id AS linked_member_id FROM users u LEFT JOIN members m ON m.user_id = u.user_id WHERE u.user_id = ?", userId);
        if (row == null || !Js.truthy(row.get("is_active"))) throw new ApiException(401, "Not authenticated. Please log in.");
        request.setAttribute(ATTRIBUTE, new CurrentUser(row));
        return true;
    }
}
