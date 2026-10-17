package com.splitledger.identity.api.dto;

/**
 * The access token travels in the body so the client can hold it in memory. The refresh
 * token is deliberately absent: it is set as an httpOnly cookie and must never be readable
 * by JavaScript (ADR-011).
 */
public record AuthResponse(String accessToken, long expiresInSeconds, SelfProfileResponse user) {
}
