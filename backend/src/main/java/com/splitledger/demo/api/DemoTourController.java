package com.splitledger.demo.api;

import com.splitledger.common.ratelimit.RateLimiter;
import com.splitledger.demo.DemoTourService;
import com.splitledger.identity.api.SessionResponses;
import com.splitledger.identity.api.dto.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Explore the demo" (SL-904).
 *
 * <p>POST and not GET, although it takes nothing: it creates a session and sets a cookie,
 * and a GET that does either is one prefetching browser extension away from signing people
 * in by accident.
 *
 * <p>Rate-limited per IP like the other credential endpoints. Nobody is being protected
 * from a wrong password here -- there is no password to get wrong -- but each call mints a
 * refresh token, and an uncapped endpoint that writes a row per request is worth capping
 * whatever it is for.
 */
@RestController
@RequestMapping("/api/v1/auth/demo")
public class DemoTourController {

    private final DemoTourService tour;
    private final SessionResponses sessions;
    private final RateLimiter rateLimiter;

    DemoTourController(DemoTourService tour, SessionResponses sessions, RateLimiter rateLimiter) {
        this.tour = tour;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<AuthResponse> explore(HttpServletRequest http) {
        rateLimiter.check("demo-tour", clientIp(http));
        return sessions.respond(tour.start(), HttpStatus.OK);
    }

    /**
     * The same reading of the forwarded header the auth endpoints use: first hop, because
     * everything after it is whatever the client decided to send.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
