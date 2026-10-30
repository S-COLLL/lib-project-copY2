package com.smartlib.common;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thin JDBC access over the MySQL connection pool. Inside {@link #tx} every query runs on the same
 * connection in one InnoDB transaction; any exception rolls the whole transaction back.
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public Db(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** All rows as column → value maps. */
    public List<Map<String, Object>> rows(String sql, Object... params) {
        return jdbc.queryForList(sql, bind(params));
    }

    /** The first row, or null. */
    public Map<String, Object> row(String sql, Object... params) {
        List<Map<String, Object>> r = rows(sql, params);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The first column of the first row, or null. */
    public Object value(String sql, Object... params) {
        Map<String, Object> r = row(sql, params);
        return r == null || r.isEmpty() ? null : r.values().iterator().next();
    }

    /** Runs an UPDATE / DELETE / INSERT and returns the affected row count. */
    public int update(String sql, Object... params) {
        return jdbc.update(sql, bind(params));
    }

    /** Runs an INSERT and returns the AUTO_INCREMENT id of the new row. */
    public long insert(String sql, Object... params) {
        Object[] args = bind(params);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    /** Runs fn in one transaction and returns its result. */
    public <T> T tx(Supplier<T> fn) {
        return transactions.execute(status -> fn.get());
    }

    public void tx(Runnable fn) {
        transactions.executeWithoutResult(status -> fn.run());
    }

    /** Flattens params: collections expand (for IN lists built with Js.placeholders), instants become local DATETIME values. */
    private static Object[] bind(Object[] params) {
        List<Object> out = new ArrayList<>();
        for (Object p : params) {
            if (p instanceof Collection<?> c) c.forEach(v -> out.add(convert(v)));
            else out.add(convert(p));
        }
        return out.toArray();
    }

    private static Object convert(Object v) {
        if (v == Js.UNDEFINED) return null;
        if (v instanceof Instant i) return JsDate.toDb(i);
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return d.longValue();
        return v;
    }
}
