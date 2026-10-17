package com.pm.patientservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.IdempotencyKeyReusedException;
import com.pm.patientservice.exception.IdempotencyRequestInProgressException;
import com.pm.patientservice.exception.InvalidQueryException;
import com.pm.patientservice.model.IdempotencyRecord;
import com.pm.patientservice.repository.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes POST /patients safe to retry. The client sends an Idempotency-Key header:
 *
 * <ul>
 *   <li>first request with a key: claim the key, create the patient, store the response;
 *   <li>same key + same body again: return the stored response and create nothing;
 *   <li>same key + different body: reject with 422 (almost certainly a client bug);
 *   <li>same key while the first request is still running: reject with 409.
 * </ul>
 *
 * Creating the patient (with its outbox event) and completing the key happen in ONE database
 * transaction, so there is no window where a patient exists but its key is not marked complete.
 * The initial claim of the key is deliberately a separate, immediately committed step so that
 * concurrent requests can see it. If the process dies after the claim but before the transaction
 * commits, the key stays IN_PROGRESS until a purge job removes it (not implemented yet); on a
 * normal failure the key is released so the client can retry.
 */
@Service
public class IdempotentPatientService {

    private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    /** The patient to return and whether it came from a stored response. */
    public record Result(PatientResponseDTO patient, boolean replayed) {}

    private final IdempotencyRecordRepository repository;
    private final PatientService patientService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotentPatientService(IdempotencyRecordRepository repository,
                                    PatientService patientService, ObjectMapper objectMapper,
                                    TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.patientService = patientService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public Result createPatient(String idempotencyKey, PatientRequestDTO request) {
        validateKey(idempotencyKey);
        String hash = requestHash(request);

        if (repository.insertIfAbsent(idempotencyKey, hash) == 0) {
            return replayOrReject(idempotencyKey, hash);
        }

        try {
            // One transaction: patient + outbox event + key completion commit or roll back together.
            // PatientService.createPatient and markCompleted both join this transaction.
            return transactionTemplate.execute(status -> {
                PatientResponseDTO created = patientService.createPatient(request);
                repository.markCompleted(idempotencyKey, HttpStatus.CREATED.value(), toJson(created));
                return new Result(created, false);
            });
        } catch (RuntimeException e) {
            // Everything above rolled back, so no patient exists. Free the key so the client can retry.
            repository.releaseInProgress(idempotencyKey);
            throw e;
        }
    }

    private Result replayOrReject(String idempotencyKey, String hash) {
        IdempotencyRecord existing = repository.findById(idempotencyKey)
                .orElseThrow(() -> new IdempotencyRequestInProgressException(
                        "A request with this Idempotency-Key is being processed"));

        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency-Key was already used with a different request body");
        }
        if (IdempotencyRecord.COMPLETED.equals(existing.getStatus())) {
            return new Result(fromJson(existing.getResponseBody()), true);
        }
        throw new IdempotencyRequestInProgressException(
                "A request with this Idempotency-Key is being processed");
    }

    private static void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new InvalidQueryException(
                    "Idempotency-Key must be 1-255 printable ASCII characters without spaces");
        }
    }

    /** SHA-256 over every request field, so any change to the body changes the fingerprint. */
    static String requestHash(PatientRequestDTO r) {
        String canonical = String.join("\u001f",
                String.valueOf(r.getName()),
                String.valueOf(r.getEmail()),
                String.valueOf(r.getAddress()),
                String.valueOf(r.getDateOfBirth()),
                String.valueOf(r.getRegisteredDate()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String toJson(PatientResponseDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response for idempotency record", e);
        }
    }

    private PatientResponseDTO fromJson(String json) {
        try {
            return objectMapper.readValue(json, PatientResponseDTO.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored idempotent response", e);
        }
    }
}