package com.pm.patientservice.exception;

/** A request with this Idempotency-Key is still being processed. Mapped to HTTP 409. */
public class IdempotencyRequestInProgressException extends RuntimeException {
    public IdempotencyRequestInProgressException(String message) {
        super(message);
    }
}