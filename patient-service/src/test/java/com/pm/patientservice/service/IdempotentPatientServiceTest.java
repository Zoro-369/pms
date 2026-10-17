package com.pm.patientservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.IdempotencyKeyReusedException;
import com.pm.patientservice.exception.IdempotencyRequestInProgressException;
import com.pm.patientservice.exception.InvalidQueryException;
import com.pm.patientservice.model.IdempotencyRecord;
import com.pm.patientservice.repository.IdempotencyRecordRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class IdempotentPatientServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private IdempotencyRecordRepository repository;
    @Mock private PatientService patientService;
    @Mock private TransactionTemplate transactionTemplate;

    private IdempotentPatientService service;

    @BeforeEach
    void setUp() {
        // Run the transactional callback directly; real commit/rollback is covered by
        // PatientCreationAtomicityTest against PostgreSQL.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        service = new IdempotentPatientService(repository, patientService, objectMapper,
                transactionTemplate);
    }

    // ------------------------------------------------------------------ helpers

    private static PatientRequestDTO request() {
        return request("Jane Doe");
    }

    private static PatientRequestDTO request(String name) {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName(name);
        dto.setEmail("jane@example.com");
        dto.setAddress("1 Main St");
        dto.setDateOfBirth(LocalDate.of(1990, 1, 1));
        dto.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return dto;
    }

    private static PatientResponseDTO response() {
        PatientResponseDTO dto = new PatientResponseDTO();
        dto.setId("123e4567-e89b-12d3-a456-426614174000");
        dto.setName("Jane Doe");
        dto.setEmail("jane@example.com");
        dto.setAddress("1 Main St");
        dto.setDateOfBirth("1990-01-01");
        return dto;
    }

    private static IdempotencyRecord record(String key, String hash, String status, String body) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setIdempotencyKey(key);
        r.setRequestHash(hash);
        r.setStatus(status);
        r.setResponseStatus(body == null ? null : 201);
        r.setResponseBody(body);
        return r;
    }

    // ------------------------------------------------------------------ first request

    @Test
    void firstRequest_createsThePatientAndStoresTheResponse() {
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(1);
        when(patientService.createPatient(any(PatientRequestDTO.class))).thenReturn(response());

        IdempotentPatientService.Result result = service.createPatient("key-1", request());

        assertThat(result.replayed()).isFalse();
        assertThat(result.patient().getId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repository).markCompleted(eq("key-1"), eq(201), body.capture());
        assertThat(body.getValue()).contains("jane@example.com");
    }

    // ------------------------------------------------------------------ retries

    @Test
    void sameKeyAndSameBody_replaysTheStoredResponseWithoutCreatingAnything() throws Exception {
        PatientRequestDTO req = request();
        String hash = IdempotentPatientService.requestHash(req);
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(0);
        when(repository.findById("key-1")).thenReturn(Optional.of(
                record("key-1", hash, IdempotencyRecord.COMPLETED, objectMapper.writeValueAsString(response()))));

        IdempotentPatientService.Result result = service.createPatient("key-1", req);

        assertThat(result.replayed()).isTrue();
        assertThat(result.patient().getId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(result.patient().getEmail()).isEqualTo("jane@example.com");
        verifyNoInteractions(patientService);
        verify(repository, never()).markCompleted(anyString(), anyInt(), anyString());
    }

    @Test
    void sameKeyWithDifferentBody_isRejectedAndCreatesNothing() throws Exception {
        String otherHash = IdempotentPatientService.requestHash(request("Someone Else"));
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(0);
        when(repository.findById("key-1")).thenReturn(Optional.of(
                record("key-1", otherHash, IdempotencyRecord.COMPLETED, objectMapper.writeValueAsString(response()))));

        assertThatThrownBy(() -> service.createPatient("key-1", request()))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(patientService);
    }

    @Test
    void sameKeyWhileFirstRequestIsStillRunning_isRejectedAsInProgress() {
        PatientRequestDTO req = request();
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(0);
        when(repository.findById("key-1")).thenReturn(Optional.of(
                record("key-1", IdempotentPatientService.requestHash(req), IdempotencyRecord.IN_PROGRESS, null)));

        assertThatThrownBy(() -> service.createPatient("key-1", req))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        verifyNoInteractions(patientService);
    }

    @Test
    void keyReleasedBetweenClaimAndRead_isTreatedAsInProgress() {
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(0);
        when(repository.findById("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPatient("key-1", request()))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        verifyNoInteractions(patientService);
    }

    // ------------------------------------------------------------------ failures

    @Test
    void failedCreation_releasesTheKeySoTheClientCanRetry() {
        when(repository.insertIfAbsent(eq("key-1"), anyString())).thenReturn(1);
        when(patientService.createPatient(any(PatientRequestDTO.class)))
                .thenThrow(new EmailAlreadyExistsException("exists"));

        assertThatThrownBy(() -> service.createPatient("key-1", request()))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(repository).releaseInProgress("key-1");
        verify(repository, never()).markCompleted(anyString(), anyInt(), anyString());
    }

    // ------------------------------------------------------------------ key validation and hashing

    @Test
    void invalidKeys_areRejectedBeforeAnythingIsTouched() {
        for (String bad : List.of("", "   ", "has space", "tab\tkey", "x".repeat(256))) {
            assertThatThrownBy(() -> service.createPatient(bad, request()))
                    .as("key [%s]", bad.length() > 20 ? bad.substring(0, 20) + "..." : bad)
                    .isInstanceOf(InvalidQueryException.class);
        }
        verifyNoInteractions(repository, patientService);
    }

    @Test
    void requestHash_isStable_andChangesWhenAnyFieldChanges() {
        String base = IdempotentPatientService.requestHash(request());

        assertThat(base).hasSize(64);
        assertThat(IdempotentPatientService.requestHash(request())).isEqualTo(base);

        PatientRequestDTO changedName = request("Other Name");
        assertThat(IdempotentPatientService.requestHash(changedName)).isNotEqualTo(base);

        PatientRequestDTO changedEmail = request();
        changedEmail.setEmail("other@example.com");
        assertThat(IdempotentPatientService.requestHash(changedEmail)).isNotEqualTo(base);

        PatientRequestDTO changedAddress = request();
        changedAddress.setAddress("2 Other St");
        assertThat(IdempotentPatientService.requestHash(changedAddress)).isNotEqualTo(base);

        PatientRequestDTO changedDob = request();
        changedDob.setDateOfBirth(LocalDate.of(1991, 1, 1));
        assertThat(IdempotentPatientService.requestHash(changedDob)).isNotEqualTo(base);

        PatientRequestDTO changedRegistered = request();
        changedRegistered.setRegisteredDate(LocalDate.of(2025, 1, 1));
        assertThat(IdempotentPatientService.requestHash(changedRegistered)).isNotEqualTo(base);
    }
}