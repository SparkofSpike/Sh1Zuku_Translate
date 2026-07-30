package com.shizuku.translate.controller;

import com.shizuku.translate.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StatsController {

    private final UserService userService;

    public StatsController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/stats/users")
    public Map<String, Long> getUserCount() {
        return Map.of("count", userService.getUserCount());
    }
}
