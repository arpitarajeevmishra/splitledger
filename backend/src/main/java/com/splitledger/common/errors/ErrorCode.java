package com.splitledger.common.errors;

import org.springframework.http.HttpStatus;

/**
 * The error vocabulary from LLD section 11.4. Codes are part of the API contract:
 * clients branch on them, so they are renamed only with a version bump.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request failed validation"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Missing, invalid or expired token"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is unknown, expired or already used"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "That email is already registered"),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "Registration is currently closed"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not allowed"),
    NOT_GROUP_MEMBER(HttpStatus.FORBIDDEN, "Not a member of this group"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "The resource changed since you read it"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "The same request is still being processed"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Illegal state transition"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused with a different body"),
    SPLIT_SUM_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Split amounts do not add up"),
    SETTLEMENT_EXCEEDS_OUTSTANDING(HttpStatus.UNPROCESSABLE_ENTITY, "Paying more than the balance allows"),
    SETTLEMENT_BELOW_GATEWAY_MINIMUM(HttpStatus.UNPROCESSABLE_ENTITY, "Below the gateway minimum of 100 paise"),
    PARTICIPANT_NOT_MEMBER(HttpStatus.UNPROCESSABLE_ENTITY, "Payer or participant is not an active group member"),
    CURRENCY_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "Only INR is supported"),
    MEMBER_HAS_OUTSTANDING_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY,
            "That member still has a non-zero balance in this group"),
    LAST_ADMIN(HttpStatus.UNPROCESSABLE_ENTITY, "A group must keep at least one admin"),
    AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "Amount, shares or participant count outside the allowed range"),
    ACCOUNT_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "A cap on this demo account was reached"),
    GROUP_ARCHIVED(HttpStatus.CONFLICT, "This group is archived"),
    ALREADY_GROUP_MEMBER(HttpStatus.CONFLICT, "They are already in this group"),
    INVITE_ALREADY_PENDING(HttpStatus.CONFLICT,
            "An invitation to that address was just created"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN,
            "Verify your email address before joining a group"),
    VERIFICATION_INVALID(HttpStatus.NOT_FOUND, "Unknown, used or expired verification link"),
    INVITE_INVALID(HttpStatus.NOT_FOUND, "Unknown, revoked or expired invite"),
    INVITE_EMAIL_MISMATCH(HttpStatus.FORBIDDEN, "This invite was addressed to someone else"),
    PAYMENT_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Signature verification failed"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "Payment gateway call failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Stable, kebab-case URI used as the problem+json {@code type}. */
    public String typeUri() {
        return "https://splitledger.dev/errors/" + name().toLowerCase().replace('_', '-');
    }
}
