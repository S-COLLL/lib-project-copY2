package com.smartlib.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** An error with an HTTP status, answered as { success: false, message, details }. */
public class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, String> details;

    public ApiException(int status, String message) {
        this(status, message, null);
    }

    public ApiException(int status, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    /** Throws an ApiException with this status and message. */
    public static void fail(int status, String message) {
        throw new ApiException(status, message);
    }

    public static void fail(int status, String message, Map<String, String> details) {
        throw new ApiException(status, message, details);
    }

    /** Fails with 400 and the first error's message when any field errors were collected. */
    public static void failIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) throw new ApiException(400, errors.values().iterator().next(), new LinkedHashMap<>(errors));
    }

    /** One-field details map, e.g. details("name", "Name is required"). */
    public static Map<String, String> details(String field, String message) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put(field, message);
        return d;
    }
}
