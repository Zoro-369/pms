package com.pm.patientservice.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central error handling. Every error is returned as an RFC 7807 ProblemDetail
 * (Content-Type: application/problem+json).
 *
 * Extending ResponseEntityExceptionHandler keeps Spring's own handling of standard MVC errors
 * (malformed JSON or dates -> 400, unsupported method -> 405, unknown path -> 404, bad UUID -> 400),
 * so the catch-all below only sees genuinely unexpected exceptions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
          MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
          WebRequest request) {

    Map<String, String> errors = new LinkedHashMap<>();
    ex.getBindingResult().getFieldErrors()
            .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Validation failed");
    problem.setTitle("Invalid request");
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  @ExceptionHandler(InvalidQueryException.class)
  public ProblemDetail handleInvalidQuery(InvalidQueryException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            ex.getMessage());
    problem.setTitle("Invalid query");
    return problem;
  }

  @ExceptionHandler(EmailAlreadyExistsException.class)
  public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
    log.warn("Duplicate email rejected: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Email address already exists");
    problem.setTitle("Duplicate email");
    return problem;
  }

  @ExceptionHandler(PatientNotFoundException.class)
  public ProblemDetail handlePatientNotFound(PatientNotFoundException ex) {
    log.warn("Patient not found: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Patient not found");
    problem.setTitle("Patient not found");
    return problem;
  }

  // Safety net for the check-then-save race: two concurrent requests can both pass existsByEmail,
  // and the UNIQUE constraint then rejects the second insert.
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
    log.warn("Data integrity violation", ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The request conflicts with existing data");
    problem.setTitle("Conflict");
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred");
    problem.setTitle("Internal server error");
    return problem;
  }
}