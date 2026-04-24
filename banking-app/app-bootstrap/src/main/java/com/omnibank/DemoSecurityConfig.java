package com.omnibank;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo-friendly security configuration for the Omnibank banking app.
 *
 * <p>Spring Boot's auto-configuration locks every endpoint behind HTTP
 * Basic auth using a generated in-memory user. That's the sane default
 * for a real bank, but it makes "click Open banking app" from the
 * WebUI return a Whitelabel 401 page that confuses first-time
 * visitors. Here we open up the welcome page and the actuator probes
 * so visitors get something useful at the root, while real customer
 * endpoints (everything under {@code /api/**}) stay authenticated.
 */
@Configuration
public class DemoSecurityConfig {

    @Bean
    public SecurityFilterChain demoFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Welcome JSON, actuator probes, error pages, and the
                // one-time autologin bridge are public. The bridge is
                // protected by a 256-bit token (held only in memory by
                // the WebUI manager + this JVM, never persisted). The
                // AppMap agent's /_appmap/record endpoint is exposed on
                // the app port; the WebUI drives it to bracket
                // interactive recordings, so it must be reachable
                // without credentials.
                .requestMatchers("/", "/actuator/**", "/error",
                                 "/favicon.ico", "/_demo/autologin",
                                 "/_appmap/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(b -> {});
        return http.build();
    }
}
