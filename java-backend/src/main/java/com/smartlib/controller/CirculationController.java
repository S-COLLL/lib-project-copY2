package com.smartlib.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.CirculationService;

/** /api/issues, /api/returns and /api/reservations. Students see only their own records. */
@RestController
public class CirculationController {

    private final CirculationService circulation;

    public CirculationController(CirculationService circulation) {
        this.circulation = circulation;
    }

    /* --------------------------------- Issues --------------------------------- */

    @GetMapping("/api/issues")
    public Map<String, Object> listIssues(CurrentUser user, @RequestParam Map<String, String> q) {
        return circulation.listIssues(user, Input.of(q));
    }

    @GetMapping("/api/issues/{id}")
    public Map<String, Object> getIssue(CurrentUser user, @PathVariable String id) {
        return circulation.getIssue(user, id);
    }

    @PostMapping("/api/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createIssue(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return circulation.createIssue(user, Input.of(body));
    }

    @PatchMapping("/api/issues/{id}/renew")
    public Map<String, Object> renewIssue(CurrentUser user, @PathVariable String id) {
        return circulation.renewIssue(user, id);
    }

    /* --------------------------------- Returns --------------------------------- */

    @GetMapping("/api/returns/lookup")
    public Map<String, Object> lookupReturns(CurrentUser user, @RequestParam Map<String, String> q) {
        user.requireStaff();
        return circulation.lookupReturns(Input.of(q));
    }

    @GetMapping("/api/returns")
    public Map<String, Object> listReturns(CurrentUser user, @RequestParam Map<String, String> q) {
        return circulation.listReturns(user, Input.of(q));
    }

    @PostMapping("/api/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReturn(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return circulation.createReturn(user, Input.of(body));
    }

    /* ------------------------------ Reservations ------------------------------ */

    @GetMapping("/api/reservations")
    public Map<String, Object> listReservations(CurrentUser user, @RequestParam Map<String, String> q) {
        return circulation.listReservations(user, Input.of(q));
    }

    @PostMapping("/api/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReservation(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        return circulation.createReservation(user, Input.of(body));
    }

    @PatchMapping("/api/reservations/{id}/cancel")
    public Map<String, Object> cancelReservation(CurrentUser user, @PathVariable String id) {
        return circulation.cancelReservation(user, id);
    }

    @PatchMapping("/api/reservations/{id}/collect")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> collectReservation(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return circulation.collectReservation(user, id, Input.of(body));
    }

    @DeleteMapping("/api/reservations/{id}")
    public Map<String, Object> deleteReservation(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return circulation.deleteReservation(user, id);
    }
}
