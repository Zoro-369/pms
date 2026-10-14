package com.pm.patientservice.exception;

/** Thrown when query parameters (sort field, search text) are not acceptable. Mapped to HTTP 400. */
public class InvalidQueryException extends RuntimeException {
    public InvalidQueryException(String message) {
        super(message);
    }
}