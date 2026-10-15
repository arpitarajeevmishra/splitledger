package com.splitledger.identity.api;

import com.splitledger.common.errors.ApiException;
import com.splitledger.identity.application.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves a {@code Bearer} access token to an authenticated principal.
 *
 * <p>An absent or invalid token is left unauthenticated rather than rejected here: a filter
 * sits outside Spring MVC, so an exception thrown from it would bypass the problem+json
 * handler. Authorisation then rejects the request and the entry point renders the error in
 * the usual contract.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            try {
                UUID userId = jwtService.verifyAndExtractUserId(header.substring(PREFIX.length()));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            } catch (ApiException ex) {
                // Expired, tampered or malformed: stay anonymous and let authorisation decide.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
