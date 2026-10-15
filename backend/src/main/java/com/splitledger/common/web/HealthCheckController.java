package com.splitledger.common.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint (LLD section 11.1). Deliberately touches no database:
 * the deployment smoke test and the uptime monitor use it, and it must answer
 * cheaply. Database health lives behind /actuator/health.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthCheckController {

    @GetMapping("/health-check")
    public Map<String, String> healthCheck() {
        return Map.of("status", "UP");
    }
}
