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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.MemberService;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public Map<String, Object> list(CurrentUser user, @RequestParam Map<String, String> q) {
        user.requireStaff();
        return members.listMembers(Input.of(q));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(CurrentUser user, @PathVariable String id) {
        return members.getMember(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return members.createMember(user, Input.of(body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return members.updateMember(Input.of(body), id);
    }

    @PatchMapping("/{id}/renew")
    public Map<String, Object> renew(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return members.renewMember(user, Input.of(body), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return members.deleteMember(id);
    }
}
