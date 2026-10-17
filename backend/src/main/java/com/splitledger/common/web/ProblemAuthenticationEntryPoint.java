package com.splitledger.common.web;

import tools.jackson.databind.ObjectMapper;
import com.splitledger.common.errors.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Spring Security rejects unauthenticated requests before Spring MVC runs, so without this
 * a 401 would be the only response in the API that is not problem+json. The error contract
 * is only worth something if it has no exceptions.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // Jackson 3 (tools.jackson), not Jackson 2 (com.fasterxml.jackson). Spring Boot 4
    // registers the former; the latter is still on the classpath as a transitive
    // dependency, so importing it compiles cleanly and then fails to wire at runtime.
    private final ObjectMapper objectMapper;

    ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ErrorCode code = ErrorCode.UNAUTHENTICATED;
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setDetail("Authentication is required for this endpoint");
        problem.setProperty("code", code.name());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
