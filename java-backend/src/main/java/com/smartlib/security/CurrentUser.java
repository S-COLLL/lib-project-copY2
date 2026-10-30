package com.smartlib.security;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.smartlib.common.ApiException;
import com.smartlib.common.Js;

/** The signed-in user: their users row plus the id of the library membership linked to it. */
public final class CurrentUser {

    public static final List<String> STAFF = List.of("admin", "librarian", "staff");

    private final Map<String, Object> row;
    private final Integer memberId;

    public CurrentUser(Map<String, Object> row) {
        this.row = row;
        Object linked = row.get("linked_member_id");
        this.memberId = linked == null ? null : ((Number) linked).intValue();
    }

    public Map<String, Object> row() {
        return row;
    }

    public int userId() {
        return ((Number) row.get("user_id")).intValue();
    }

    public String role() {
        return Js.str(row.get("role"));
    }

    /** The linked member id, or null when the account has no membership. */
    public Integer memberId() {
        return memberId;
    }

    /** memberId || -1, for queries that must match nothing when there is no membership. */
    public int memberIdOrNone() {
        return memberId == null ? -1 : memberId;
    }

    public boolean isStudent() {
        return "student".equals(role());
    }

    public boolean isStaff() {
        return STAFF.contains(role());
    }

    /** True when this user is a student and the record belongs to another member. */
    public boolean isOtherStudent(Object memberIdOfRecord) {
        return isStudent() && (memberId == null || memberIdOfRecord == null || ((Number) memberIdOfRecord).intValue() != memberId);
    }

    /** Fails with 403 unless the user has one of the roles. */
    public void require(String... roles) {
        if (!Arrays.asList(roles).contains(role())) {
            throw new ApiException(403, "This action requires one of the roles: " + String.join(", ", roles));
        }
    }

    public void requireStaff() {
        require("admin", "librarian", "staff");
    }

    public void requireEditor() {
        require("admin", "librarian");
    }
}
