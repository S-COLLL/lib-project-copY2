package com.smartlib.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
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
import com.smartlib.service.FinanceService;

/** /api/fines and /api/payments. Students see only their own. */
@RestController
public class FinanceController {

    private final FinanceService finance;

    public FinanceController(FinanceService finance) {
        this.finance = finance;
    }

    /* ---------------------------------- Fines ---------------------------------- */

    @GetMapping("/api/fines")
    public Map<String, Object> listFines(CurrentUser user, @RequestParam Map<String, String> q) {
        return finance.listFines(user, Input.of(q));
    }

    @GetMapping("/api/fines/summary")
    public Map<String, Object> fineSummary(CurrentUser user) {
        return finance.fineSummary(user);
    }

    @PostMapping("/api/fines")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFine(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return finance.createFine(Input.of(body));
    }

    @PatchMapping("/api/fines/{id}/discount")
    public Map<String, Object> discountFine(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return finance.discountFine(user, Input.of(body), id);
    }

    @PatchMapping("/api/fines/{id}/waive")
    public Map<String, Object> waiveFine(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return finance.waiveFine(user, Input.of(body), id);
    }

    /* --------------------------------- Payments --------------------------------- */

    @GetMapping("/api/payments")
    public Map<String, Object> listPayments(CurrentUser user, @RequestParam Map<String, String> q) {
        return finance.listPayments(user, Input.of(q));
    }

    @GetMapping("/api/payments/{id}")
    public Map<String, Object> getPayment(CurrentUser user, @PathVariable String id) {
        return finance.getPayment(user, id);
    }

    @PostMapping("/api/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPayment(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return finance.createPayment(user, Input.of(body));
    }

    @PatchMapping("/api/payments/{id}/confirm")
    public Map<String, Object> confirmPayment(CurrentUser user, @PathVariable String id) {
        user.requireStaff();
        return finance.confirmPayment(user, id);
    }
}
