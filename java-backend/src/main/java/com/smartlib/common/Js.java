package com.smartlib.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Value helpers with the same rules the original JavaScript API used (Number(), String(), truthiness,
 * rounding, ₹ formatting), so request handling and JSON responses stay identical for the frontend.
 */
public final class Js {

    /** A field that is absent (JavaScript undefined). Left out of JSON objects. */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private static final Pattern PK = Pattern.compile("^\\d{1,9}$");
    private static final Pattern NUMERIC = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    private Js() {
    }

    /* ------------------------------ Conversions ------------------------------ */

    /** JavaScript Number(v). */
    public static double num(Object v) {
        if (v == UNDEFINED) return Double.NaN;
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            String t = s.strip();
            if (t.isEmpty()) return 0;
            if (NUMERIC.matcher(t).matches()) return Double.parseDouble(t);
            if (t.matches("^[+-]?Infinity$")) return t.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            if (t.matches("^0[xX][0-9a-fA-F]+$")) return Long.parseLong(t.substring(2), 16);
            return Double.NaN;
        }
        if (v instanceof List<?> l) return l.isEmpty() ? 0 : l.size() == 1 ? num(str(l.get(0))) : Double.NaN;
        return Double.NaN;
    }

    /** JavaScript String(v). */
    public static String str(Object v) {
        if (v == UNDEFINED) return "undefined";
        if (v == null) return "null";
        if (v instanceof String s) return s;
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) return numberToString(((Number) v).doubleValue());
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) parts.add(o == null || o == UNDEFINED ? "" : str(o));
            return String.join(",", parts);
        }
        if (v instanceof Map) return "[object Object]";
        return v.toString();
    }

    /** JavaScript String(v || '') — the empty string for any falsy value. */
    public static String text(Object v) {
        return truthy(v) ? str(v) : "";
    }

    /** JavaScript truthiness. */
    public static boolean truthy(Object v) {
        if (v == null || v == UNDEFINED) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof BigDecimal d) return d.signum() != 0;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        return true;
    }

    /** JavaScript parseInt(v, 10); null when the result is NaN. */
    public static Long parseInt(Object v) {
        if (v == null || v == UNDEFINED) return null;
        String s = str(v).strip();
        int i = 0;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) neg = s.charAt(i++) == '-';
        int start = i;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) i++;
        if (i == start) return null;
        String digits = s.substring(start, Math.min(i, start + 18));
        long n = Long.parseLong(digits);
        return neg ? -n : n;
    }

    /** Number → string the way JavaScript prints it (3 not 3.0). */
    public static String numberToString(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e21) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** A number as the JSON value JavaScript would write: whole numbers without ".0", NaN as null. */
    public static Object jsonNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) return (long) d;
        return d;
    }

    public static boolean isNaN(double d) {
        return Double.isNaN(d);
    }

    /** True when the reference is a numeric primary key (1–9 digits). */
    public static boolean isPk(Object ref) {
        return ref != null && ref != UNDEFINED && PK.matcher(str(ref)).matches();
    }

    /** Math.round(n * 100) / 100 of Number(n) (0 for NaN). */
    public static double round2(Object n) {
        double d = num(n);
        if (Double.isNaN(d)) d = 0;
        return Math.floor(d * 100 + 0.5) / 100;
    }

    /** JavaScript Math.round. */
    public static long round(double d) {
        return (long) Math.floor(d + 0.5);
    }

    /** Pads like String(v).padStart(width, '0'). */
    public static String padStart(String s, int width, char c) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) b.append(c);
        return b.append(s).toString();
    }

    /** Readable record code, e.g. code("BK", 7, 4) = "BK0007". */
    public static String code(String prefix, long n, int width) {
        return prefix + padStart(Long.toString(n), width, '0');
    }

    /** String.prototype.slice(0, max). */
    public static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** id(v): the value as a string, or null. */
    public static String id(Object v) {
        return v == null || v == UNDEFINED ? null : str(v instanceof BigDecimal d ? (Object) jsonNumber(d.doubleValue()) : v);
    }

    /* ----------------------------- Indian rupees ----------------------------- */

    /** `₹${Number(n || 0).toLocaleString('en-IN')}` — Indian digit grouping (1,23,456.5). */
    public static String money(Object n) {
        double d = truthy(n) ? num(n) : 0;
        return "₹" + enIN(d);
    }

    /** Number.prototype.toLocaleString('en-IN'): at most 3 decimals, lakh/crore grouping. */
    public static String enIN(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "∞" : "-∞";
        BigDecimal v = new BigDecimal(Math.abs(d)).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = v.toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "" : plain.substring(dot);
        StringBuilder grouped = new StringBuilder();
        int len = intPart.length();
        if (len <= 3) grouped.append(intPart);
        else {
            String head = intPart.substring(0, len - 3);
            String tail = intPart.substring(len - 3);
            StringBuilder h = new StringBuilder();
            int first = head.length() % 2;
            if (first > 0) h.append(head, 0, first);
            for (int i = first; i < head.length(); i += 2) {
                if (h.length() > 0) h.append(',');
                h.append(head, i, i + 2);
            }
            grouped.append(h).append(',').append(tail);
        }
        boolean negative = d < 0 && v.signum() != 0;
        return (negative ? "-" : "") + grouped + frac;
    }

    /* ------------------------------ JSON objects ------------------------------ */

    /** Builds an ordered JSON object from key/value pairs; UNDEFINED values are left out. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != UNDEFINED) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** { ...base, ...extra }: a copy of base with extra's entries applied (UNDEFINED removes a key). */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> m = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        extra.forEach((k, v) -> {
            if (v == UNDEFINED) m.remove(k);
            else m.put(k, v);
        });
        return m;
    }

    /** { ...base, k1: v1, ... }. */
    public static Map<String, Object> with(Map<String, Object> base, Object... kv) {
        Map<String, Object> m = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == UNDEFINED) m.remove((String) kv[i]);
            else m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** "?, ?, ?" for an SQL IN (...) list ("NULL" when empty, which matches nothing). */
    public static String placeholders(Collection<?> values) {
        if (values.isEmpty()) return "NULL";
        return String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    }
}
