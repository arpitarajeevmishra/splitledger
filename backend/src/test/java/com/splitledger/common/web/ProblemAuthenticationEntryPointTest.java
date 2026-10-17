package com.splitledger.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

/**
 * A 401 is the one error Spring MVC never sees, so it is the one most likely to drift out
 * of the problem+json contract. This pins it.
 */
class ProblemAuthenticationEntryPointTest {

    private final ProblemAuthenticationEntryPoint entryPoint =
            new ProblemAuthenticationEntryPoint(new ObjectMapper());

    @Test
    void writes401AsProblemJsonWithTheContractCode() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("nope"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHENTICATED\"")
                .contains("\"status\":401")
                .contains("splitledger.dev/errors/unauthenticated");
    }

    @Test
    void includesTheCorrelationIdWhenOneIsSet() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "corr-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // The filter normally populates the MDC; set it directly to keep this a unit test.
        org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        try {
            entryPoint.commence(request, response, new InsufficientAuthenticationException("nope"));
            assertThat(response.getContentAsString()).contains("corr-123");
        } finally {
            org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
