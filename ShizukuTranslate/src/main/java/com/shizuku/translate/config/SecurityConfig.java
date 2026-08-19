package com.shizuku.translate.config;

import com.shizuku.translate.security.ApiKeyAuthenticationFilter;
import com.shizuku.translate.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // SSE (SseEmitter) is async: Tomcat re-enters the
                        // filter chain on every async dispatch, but the
                        // OncePerRequestFilter auth filters are skipped
                        // there (already-filtered marker), leaving an
                        // empty SecurityContext — AuthorizationFilter then
                        // rejects and kills the stream mid-flight. Permit
                        // ASYNC/ERROR/FORWARD dispatches; real auth is
                        // enforced on the initial REQUEST dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC,
                                DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/presets").permitAll()
                        .requestMatchers("/api/v1/announcements").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        // .requestMatchers("/api/v1/stats/**").permitAll() — removed, restricted to authenticated users only
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
