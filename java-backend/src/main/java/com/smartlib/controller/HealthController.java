package com.smartlib.controller;

import java.sql.SQLException;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Js;

/**
 * GET /api/health — liveness. With ?db=1 it also checks the database and reports which settings
 * are present (never their values), so a hosted deployment can be diagnosed without its logs.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final Environment env;

    public HealthController(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health(@RequestParam(required = false) String db) {
        Map<String, Object> out = Js.obj("success", true, "status", "ok", "mode", "mysql", "backend", "java-spring-boot");
        if (db != null) {
            String ca = env.getProperty("MYSQL_SSL_CA", "");
            out.put("settings", Js.obj(
                "MYSQL_HOST", has("MYSQL_HOST"),
                "MYSQL_PORT", has("MYSQL_PORT"),
                "MYSQL_USER", has("MYSQL_USER"),
                "MYSQL_PASSWORD", has("MYSQL_PASSWORD"),
                "MYSQL_DATABASE", has("MYSQL_DATABASE"),
                "MYSQL_SSL_CA", ca.isEmpty() ? "missing" : !ca.contains("-----BEGIN") ? "file path" : ca.contains("-----END") ? "certificate" : "certificate (incomplete)",
                "JWT_SECRET", has("JWT_SECRET")));
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                out.put("database", "connected");
            } catch (RuntimeException e) {
                out.put("success", false);
                out.put("database", "error");
                out.put("code", code(e));
                out.put("detail", detail(e));
            }
        }
        return ResponseEntity.status(Boolean.TRUE.equals(out.get("success")) ? 200 : 503).body(out);
    }

    private String has(String key) {
        String v = env.getProperty(key);
        return v == null || v.isEmpty() ? "missing" : "set";
    }

    /** The root cause's message without any connection options (the part after "?"), at most 160 characters. */
    private static String detail(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = String.valueOf(root.getMessage()).replaceAll("\\?\\S*", "");
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    /** A short error code: the MySQL error / SQL state, or the root cause's class name (e.g. UnknownHostException). */
    private static String code(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getErrorCode() != 0) return "MYSQL_" + s.getErrorCode();
        }
        return root.getClass().getSimpleName();
    }
}
