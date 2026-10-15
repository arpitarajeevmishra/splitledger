package com.splitledger.common.errors;

/** An error the client should see, carrying a contract code rather than a stack trace. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ApiException(ErrorCode code) {
        this(code, code.title());
    }

    public ErrorCode code() {
        return code;
    }
}
