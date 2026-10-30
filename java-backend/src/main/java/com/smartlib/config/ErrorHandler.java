package com.smartlib.config;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.smartlib.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Turns every error into { success: false, message, details } with the right status. */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    private static final Pattern DUP_KEY = Pattern.compile("for key '(?:\\w+\\.)?(\\w+)'");
    // MySQL error numbers
    private static final int ER_DUP_ENTRY = 1062;
    private static final Set<Integer> ROW_REFERENCED = Set.of(1451, 1217);
    private static final Set<Integer> NO_REFERENCED_ROW = Set.of(1452, 1216);
    private static final int CHECK_VIOLATED = 3819;
    private static final Set<Integer> WRONG_VALUE = Set.of(1292, 1525);

    private final boolean production;

    public ErrorHandler(@Value("${APP_ENV:${NODE_ENV:development}}") String env) {
        this.production = "production".equalsIgnoreCase(env);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return respond(e.getStatus(), e.getMessage(), e.getDetails(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
        return respond(400, "Request body is not valid JSON", null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> noMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return respond(404, "Route not found: " + req.getMethod() + " " + req.getRequestURI(), null, null);
    }

    /** Unknown /api paths are 404; other unknown pages open the matching .html page or the sign-in page. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e, HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api") && "GET".equals(req.getMethod())) {
            String page = path.replaceAll("/+$", "") + ".html";
            res.sendRedirect(new ClassPathResource("static" + page).exists() ? page : "/index.html");
            return null;
        }
        String query = req.getQueryString() == null ? "" : "?" + req.getQueryString();
        return respond(404, "Route not found: " + req.getMethod() + " " + path + query, null, null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(DataAccessException e) {
        SQLException sql = sqlCause(e);
        int code = sql == null ? 0 : sql.getErrorCode();
        String state = sql == null || sql.getSQLState() == null ? "" : sql.getSQLState();
        if (e instanceof CannotGetJdbcConnectionException || state.startsWith("08")) {
            return respond(503, "The library database is unavailable right now. Please try again in a few minutes.", null, e);
        }
        if (code == ER_DUP_ENTRY) {
            Matcher m = DUP_KEY.matcher(sql.getMessage());
            String field = m.find() ? m.group(1) : "";
            field = field.replaceAll("^uq_|_code$", "").replace('_', ' ');
            return respond(409, "A record with this " + (field.isEmpty() ? "value" : field) + " already exists", null, null);
        }
        if (ROW_REFERENCED.contains(code)) return respond(409, "This record is still referenced by other data", null, null);
        if (NO_REFERENCED_ROW.contains(code)) return respond(400, "A referenced record (category, shelf, member …) does not exist", null, null);
        if (code == CHECK_VIOLATED) return respond(400, "The change violates a data rule of the library", null, null);
        if (WRONG_VALUE.contains(code)) return respond(400, "A date or number in the request is invalid", null, null);
        return respond(500, e.getMostSpecificCause().getMessage(), null, e);
    }

    /** A transaction could not start because no database connection was available. */
    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<Map<String, Object>> noTransaction(CannotCreateTransactionException e) {
        return respond(503, "The library database is unavailable right now. Please try again in a few minutes.", null, e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        return respond(500, e.getMessage() == null ? "Server error" : e.getMessage(), null, e);
    }

    private ResponseEntity<Map<String, Object>> respond(int status, String message, Map<String, String> details, Exception cause) {
        if (status >= 500) log.error("Request failed", cause);
        boolean hide = status >= 500 && status != 503 && production;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", hide ? "Server error" : message);
        if (details != null) body.put("details", details);
        return ResponseEntity.status(status).body(body);
    }

    private static SQLException sqlCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) return s;
        }
        return null;
    }
}
