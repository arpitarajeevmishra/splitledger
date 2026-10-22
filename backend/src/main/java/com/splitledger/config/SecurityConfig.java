package com.splitledger.config;

import com.splitledger.common.web.CorrelationIdFilter;
import com.splitledger.common.web.ProblemAuthenticationEntryPoint;
import com.splitledger.identity.api.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Application-wide security wiring.
 *
 * <p>This deliberately does not live in {@code common}: it must reference the identity
 * module's authentication filter, and an ArchUnit rule forbids {@code common} depending on
 * feature packages. Rather than exempt the rule, the class moved to where it belongs —
 * {@code common} is shared plumbing, while security configuration is application assembly.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;

    SecurityConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ProblemAuthenticationEntryPoint authenticationEntryPoint) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // The only cookie is the refresh token: SameSite=Strict and scoped to one
                // path, so CSRF tokens buy nothing. ADR-011.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        // The invite landing page must be able to say what the
                        // invitation is for before asking anyone to sign in. Preview only
                        // -- accept and decline fall through to authenticated below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/invites/*").permitAll()
                        // The sign-up page must know whether registration is open
                        // before anyone has an account to ask with.
                        .requestMatchers(HttpMethod.GET, "/api/v1/config").permitAll()
                        .requestMatchers("/api/v1/health-check").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Signature-verified, never under JWT, never under /api/v1. LLD 7.4.
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Strength 10 or higher, per LLD section 12.
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "If-Match", CorrelationIdFilter.HEADER));
        config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER, "ETag"));
        config.setAllowCredentials(true);   // the refresh cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
