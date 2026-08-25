package com.shizuku.translate.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.shizuku.translate.repository.ApiKeyRepository;
import com.shizuku.translate.service.ApiKeyService;

import java.io.IOException;
import java.util.Collections;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip if already authenticated by JWT
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKeyValue = extractApiKey(request);
        if (StringUtils.hasText(apiKeyValue)) {
            String keyHash = ApiKeyService.hashApiKey(apiKeyValue.trim());
            apiKeyRepository.findByKeyHashAndActiveTrue(keyHash)
                    .or(() -> apiKeyRepository.findByLegacyKeyValueAndActiveTrue(apiKeyValue.trim()))
                    .ifPresent(key -> {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                key.getUser().getUsername(), null, Collections.emptyList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }

    private String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (StringUtils.hasText(apiKey)) {
            return apiKey;
        }
        return request.getParameter("api_key");
    }
}
