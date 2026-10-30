package com.smartlib.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        return auth.login(Input.of(body));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body) {
        return auth.register(Input.of(body));
    }

    @GetMapping("/me")
    public Map<String, Object> me(CurrentUser user) {
        return auth.me(user);
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        return auth.updateProfile(user, Input.of(body));
    }

    @PatchMapping("/password")
    public Map<String, Object> changePassword(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        return auth.changePassword(user, Input.of(body));
    }

    @PostMapping("/search-history")
    public Map<String, Object> addSearchHistory(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        return auth.addSearchHistory(user, Input.of(body));
    }

    @GetMapping("/users")
    public Map<String, Object> listUsers(CurrentUser user) {
        user.require("admin");
        return auth.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.require("admin");
        return auth.createUser(Input.of(body));
    }

    @PatchMapping("/users/{id}")
    public Map<String, Object> updateUser(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.require("admin");
        return auth.updateUser(user, id, Input.of(body));
    }
}
