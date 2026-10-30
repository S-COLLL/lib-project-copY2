package com.smartlib.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date rules of the original JavaScript API. A JavaScript Date is an Instant; "local" means the server's
 * time zone (set TZ=UTC on a hosted server). DATETIME columns hold local wall-clock time, as mysql2 wrote them.
 */
public final class JsDate {

    public static final long DAY = 86_400_000L;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STRING = DateTimeFormatter.ofPattern("EEE MMM dd yyyy", Locale.ENGLISH);
    // new Date(string) for ISO-style strings: a date alone is UTC, a date-time without a zone is local time.
    private static final Pattern ISO_INPUT = Pattern.compile(
        "^\\s*([+-]\\d{6}|\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?)?\\s*$",
        Pattern.CASE_INSENSITIVE);

    private JsDate() {
    }

    public static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** new Date(v) for request values and database values; null when the date is invalid. */
    public static Instant parse(Object v) {
        if (v == null || v == Js.UNDEFINED) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof LocalDateTime t) return t.atZone(zone()).toInstant();
        if (v instanceof java.sql.Timestamp t) return t.toInstant();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay(zone()).toInstant();
        if (v instanceof LocalDate d) return d.atStartOfDay(zone()).toInstant();
        if (v instanceof Date d) return d.toInstant();
        if (v instanceof OffsetDateTime o) return o.toInstant();
        if (v instanceof ZonedDateTime z) return z.toInstant();
        if (v instanceof Number n) {
            double ms = n.doubleValue();
            return Double.isNaN(ms) || Double.isInfinite(ms) ? null : Instant.ofEpochMilli((long) ms);
        }
        if (v instanceof Boolean b) return Instant.ofEpochMilli(b ? 1 : 0);
        return parseString(Js.str(v));
    }

    private static Instant parseString(String s) {
        Matcher m = ISO_INPUT.matcher(s);
        if (!m.matches()) return null;
        try {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            if (m.group(4) == null) return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            int nanos = 0;
            if (m.group(7) != null) {
                String f = (m.group(7) + "000000000").substring(0, 9);
                nanos = Integer.parseInt(f.substring(0, 3)) * 1_000_000;
            }
            LocalDateTime t;
            if (hour == 24 && minute == 0 && second == 0) t = LocalDate.of(year, month, day).plusDays(1).atStartOfDay();
            else t = LocalDateTime.of(year, month, day, hour, minute, second, nanos);
            String z = m.group(8);
            if (z == null) return t.atZone(zone()).toInstant();
            if (z.equalsIgnoreCase("Z")) return t.toInstant(ZoneOffset.UTC);
            String off = z.length() == 5 ? z.substring(0, 3) + ":" + z.substring(3) : z;
            return t.toInstant(ZoneOffset.of(off));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Local midnight of the instant (startOfDay). */
    public static Instant startOfDay(Instant d) {
        return d.atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant();
    }

    /** d + n days (as milliseconds, like the JavaScript addDays). */
    public static Instant addDays(Instant d, double n) {
        return d.plusMillis((long) (n * DAY));
    }

    /** Whole local days from a to b, never negative. */
    public static long daysBetween(Instant a, Instant b) {
        long diff = startOfDay(b).toEpochMilli() - startOfDay(a).toEpochMilli();
        return Math.max(0, Math.floorDiv(diff, DAY));
    }

    /** date.setHours(h, m, s, ms) in local time. */
    public static Instant setHours(Instant d, int h, int m, int s, int ms) {
        return d.atZone(zone()).withHour(h).withMinute(m).withSecond(s).withNano(ms * 1_000_000).toInstant();
    }

    /** date.setMonth(date.getMonth() + months) — a day past the month's end overflows into the next month, as in JavaScript. */
    public static Instant addMonths(Instant d, int months) {
        ZonedDateTime z = d.atZone(zone());
        ZonedDateTime first = z.withDayOfMonth(1).plusMonths(months);
        return first.plusDays(z.getDayOfMonth() - 1L).toInstant();
    }

    /** Date.prototype.toDateString(), e.g. "Fri Oct 30 2026". */
    public static String toDateString(Instant d) {
        return d == null ? "Invalid Date" : DATE_STRING.format(d.atZone(zone()));
    }

    /** Date.prototype.toISOString(). */
    public static String iso(Instant d) {
        return ISO.format(d);
    }

    /** toISOString().slice(0, 10) of a database or request date; '' when there is none. */
    public static String ymd(Object v) {
        Instant d = parse(v);
        return d == null ? "" : iso(d).substring(0, 10);
    }

    /** The local wall-clock time stored in a DATETIME column for this instant. */
    public static LocalDateTime toDb(Instant d) {
        return d == null ? null : LocalDateTime.ofInstant(d, zone());
    }
}
