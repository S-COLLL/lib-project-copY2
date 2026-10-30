package com.smartlib.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.NotificationService;

/** /api/notifications and /api/settings. */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public Map<String, Object> list(CurrentUser user, @RequestParam Map<String, String> q) {
        return notifications.listNotifications(user, Input.of(q));
    }

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Object> unread(CurrentUser user) {
        return notifications.unread(user);
    }

    @PatchMapping("/api/notifications/read-all")
    public Map<String, Object> readAll(CurrentUser user) {
        return notifications.readAll(user);
    }

    @PatchMapping("/api/notifications/{id}/read")
    public Map<String, Object> readOne(CurrentUser user, @PathVariable String id) {
        return notifications.readOne(user, id);
    }

    @PostMapping("/api/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> broadcast(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return notifications.broadcast(Input.of(body));
    }

    @DeleteMapping("/api/notifications/{id}")
    public Map<String, Object> delete(CurrentUser user, @PathVariable String id) {
        user.requireStaff();
        return notifications.deleteNotification(id);
    }

    /* -------------------------------- Settings -------------------------------- */

    @GetMapping("/api/settings")
    public Map<String, Object> getSettings() {
        return notifications.getSettings();
    }

    @PutMapping("/api/settings")
    public Map<String, Object> updateSettings(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.require("admin");
        return notifications.updateSettings(Input.of(body));
    }

    @PostMapping("/api/settings/backup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> backup(CurrentUser user) {
        user.require("admin");
        return notifications.backup();
    }
}
