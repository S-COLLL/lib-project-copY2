package com.smartlib.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cross-cutting HTTP behaviour: security headers, CORS (any origin), a 2 MB body limit and
 * rate limits (2000 API requests and 30 sign-in / sign-up attempts per IP every 15 minutes).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final long MAX_BODY = 2L * 1024 * 1024;

    private final boolean trustProxy;
    private final Map<String, Window> apiHits = new ConcurrentHashMap<>();
    private final Map<String, Window> loginHits = new ConcurrentHashMap<>();

    public RequestFilter(@Value("${TRUST_PROXY:}") String trustProxy) {
        this.trustProxy = !trustProxy.isBlank() && !"0".equals(trustProxy) && !"false".equalsIgnoreCase(trustProxy);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        securityHeaders(res);
        res.setHeader("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equals(req.getMethod())) {
            res.setHeader("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
            String requested = req.getHeader("Access-Control-Request-Headers");
            if (requested != null) res.setHeader("Access-Control-Allow-Headers", requested);
            res.setHeader("Vary", "Access-Control-Request-Headers");
            res.setStatus(204);
            return;
        }

        String path = req.getRequestURI();
        if (path.startsWith("/api")) {
            if (req.getContentLengthLong() > MAX_BODY) {
                json(res, 413, "Request body is too large (limit 2 MB)");
                return;
            }
            String ip = clientIp(req);
            if (!allow(apiHits, ip, 2000)) {
                json(res, 429, "Too many requests, please try again later.");
                return;
            }
            boolean authAttempt = "POST".equals(req.getMethod()) && (path.equals("/api/auth/login") || path.equals("/api/auth/register"));
            if (authAttempt && !allow(loginHits, ip, 30)) {
                json(res, 429, "Too many attempts. Try again in a few minutes.");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static void securityHeaders(HttpServletResponse res) {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Strict-Transport-Security", "max-age=15552000; includeSubDomains");
        res.setHeader("X-DNS-Prefetch-Control", "off");
        res.setHeader("X-Download-Options", "noopen");
        res.setHeader("X-Permitted-Cross-Domain-Policies", "none");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        res.setHeader("Origin-Agent-Cluster", "?1");
        res.setHeader("X-XSS-Protection", "0");
    }

    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** Fixed 15-minute window per client. */
    private static boolean allow(Map<String, Window> hits, String ip, int max) {
        long now = System.currentTimeMillis();
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> now - e.getValue().start > WINDOW_MS);
        Window w = hits.compute(ip, (k, old) -> old == null || now - old.start > WINDOW_MS ? new Window(now) : old);
        synchronized (w) {
            return ++w.count <= max;
        }
    }

    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        res.getOutputStream().write(("{\"success\":false,\"message\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }
}
