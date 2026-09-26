package com.example.creator.auth;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/api/auth/register").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"FORBIDDEN\"}");
                        }))
                .formLogin(login -> login.loginProcessingUrl("/api/auth/login").usernameParameter("email")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"INVALID_CREDENTIALS\"}");
                        }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("CREATOR_SESSION")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .build();
    }
}
