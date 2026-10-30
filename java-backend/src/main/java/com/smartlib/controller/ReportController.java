package com.smartlib.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.AssistantService;
import com.smartlib.service.ReportService;

/** /api/reports (dashboard and tabular reports) and /api/ai (the library assistant). */
@RestController
public class ReportController {

    private final ReportService reports;
    private final AssistantService assistant;

    public ReportController(ReportService reports, AssistantService assistant) {
        this.reports = reports;
        this.assistant = assistant;
    }

    @GetMapping("/api/reports/dashboard")
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/api/reports")
    public Map<String, Object> listReports(CurrentUser user) {
        user.requireStaff();
        return reports.listReports();
    }

    @GetMapping("/api/reports/{type}")
    public Map<String, Object> getReport(CurrentUser user, @PathVariable String type, @RequestParam Map<String, String> q) {
        user.requireStaff();
        return reports.getReport(type, Input.of(q));
    }

    @PostMapping("/api/ai/query")
    public Map<String, Object> ask(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        return assistant.query(user, Input.of(body));
    }
}
