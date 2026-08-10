package com.replysis.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminPingController {

    @GetMapping("/admin-api/ping")
    public String ping() {
        return "OK";
    }
}