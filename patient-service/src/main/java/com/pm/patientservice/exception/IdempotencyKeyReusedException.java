package com.pm.patientservice.exception;

/** Same Idempotency-Key sent with a different request body. Mapped to HTTP 422. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}