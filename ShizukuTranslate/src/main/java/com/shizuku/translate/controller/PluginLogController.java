package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.PluginLogRequest;
import com.shizuku.translate.entity.PluginLog;
import com.shizuku.translate.repository.PluginLogRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plugin/logs")
public class PluginLogController {

    private final PluginLogRepository pluginLogRepository;
    private final AppConfig.AppProperties appProperties;

    public PluginLogController(PluginLogRepository pluginLogRepository,
                               AppConfig.AppProperties appProperties) {
        this.pluginLogRepository = pluginLogRepository;
        this.appProperties = appProperties;
    }

    /**
     * Submit a plugin error report. Authenticated via X-API-Key (extension)
     * or JWT (web). The submitter is resolved from the principal, never
     * trusted from the client.
     */
    @PostMapping
    public ResponseEntity<?> submit(@Valid @RequestBody PluginLogRequest request,
                                    Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        PluginLog log = PluginLog.builder()
                .username(principal.getName())
                .version(request.getVersion())
                .errorMessage(request.getErrorMessage())
                .build();
        pluginLogRepository.save(log);
        return ResponseEntity.ok(Map.of("message", "日志已提交，感谢反馈"));
    }

    /**
     * List plugin error reports. Regular users see only their own; admins
     * see everything (the "log page" on the server).
     */
    @GetMapping
    public ResponseEntity<?> list(Principal principal,
                                  @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        Page<PluginLog> page;
        if (appProperties.isAdmin(principal.getName())) {
            page = pluginLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            page = pluginLogRepository.findByUsernameOrderByCreatedAtDesc(principal.getName(), pageable);
        }
        return ResponseEntity.ok(page.map(log -> {
            // LinkedHashMap permits a null timestamp for legacy rows;
            // Map.of would throw and turn a valid list request into 500.
            var data = new LinkedHashMap<String, Object>();
            data.put("id", log.getId());
            data.put("username", log.getUsername());
            data.put("version", log.getVersion() == null ? "" : log.getVersion());
            data.put("errorMessage", log.getErrorMessage());
            data.put("createdAt", log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
            return data;
        }));
    }
}