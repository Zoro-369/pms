package com.pm.patientservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import billing.BillingResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import com.pm.patientservice.model.IdempotencyRecord;
import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.IdempotencyRecordRepository;
import com.pm.patientservice.repository.OutboxEventRepository;
import com.pm.patientservice.service.IdempotentPatientService;
import com.pm.patientservice.service.OutboxService;
import com.pm.patientservice.service.PatientService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import patient.events.PatientEvent;

/**
 * Proves the transactional guarantees against a REAL PostgreSQL (Testcontainers, Docker needed):
 * the patient row, its outbox event row and the idempotency key commit or roll back together.
 * These tests are NOT @Transactional on purpose: each service call commits (or rolls back) for
 * real, and the tests count rows with plain JDBC afterwards. Billing is mocked; Kafka is never
 * contacted because the service no longer sends events directly.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.sql.init.mode=never",
        "spring.kafka.bootstrap-servers=localhost:1"
})
@Testcontainers
class PatientCreationAtomicityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @MockitoBean private BillingServiceGrpcClient billingClient;

    @Autowired private PatientService patientService;
    @Autowired private IdempotentPatientService idempotentPatientService;
    @Autowired private OutboxService outboxService;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from idempotency_key");
        jdbc.update("delete from patient");
    }

    // ------------------------------------------------------------------ helpers

    private static PatientRequestDTO request(String email) {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName("Jane Doe");
        dto.setEmail(email);
        dto.setAddress("1 Main St");
        dto.setDateOfBirth(LocalDate.of(1990, 1, 1));
        dto.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return dto;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void billingWillFail() {
        doThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .when(billingClient).createBillingAccount(anyString(), anyString(), anyString());
    }

    private void billingWillSucceed() {
        doReturn(BillingResponse.getDefaultInstance())
                .when(billingClient).createBillingAccount(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void createPatient_commitsThePatientAndItsOutboxEventTogether() throws Exception {
        billingWillSucceed();

        PatientResponseDTO created = patientService.createPatient(request("jane@example.com"));

        assertThat(count("patient")).isEqualTo(1);
        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateId()).isEqualTo(created.getId());
        assertThat(event.getEventType()).isEqualTo("PATIENT_CREATED");
        assertThat(event.getPublishedAt()).isNull();
        PatientEvent payload = PatientEvent.parseFrom(event.getPayload());
        assertThat(payload.getPatientId()).isEqualTo(created.getId());
        assertThat(payload.getEmail()).isEqualTo("jane@example.com");
    }

    /** The original flaw: billing failed AFTER the patient was saved, leaving a half-created patient. */
    @Test
    void createPatient_whenBillingFails_rollsBackBothThePatientAndTheOutboxEvent() {
        billingWillFail();

        assertThatThrownBy(() -> patientService.createPatient(request("jane@example.com")))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(count("patient")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void createPatient_duplicateEmail_addsNoSecondPatientOrEvent() {
        billingWillSucceed();
        patientService.createPatient(request("jane@example.com"));

        assertThatThrownBy(() -> patientService.createPatient(request("jane@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        assertThat(count("patient")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void outboxService_cannotBeUsedOutsideATransaction() {
        Patient patient = new Patient();
        patient.setId(java.util.UUID.randomUUID());
        patient.setName("No Tx");
        patient.setEmail("notx@example.com");

        assertThatThrownBy(() -> outboxService.recordPatientCreated(patient))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void idempotentCreate_afterABillingFailure_thenRetry_createsExactlyOnce() {
        // first attempt: billing is down, second attempt: billing works
        doThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .doReturn(BillingResponse.getDefaultInstance())
                .when(billingClient).createBillingAccount(anyString(), anyString(), anyString());
        PatientRequestDTO request = request("jane@example.com");

        assertThatThrownBy(() -> idempotentPatientService.createPatient("key-1", request))
                .isInstanceOf(StatusRuntimeException.class);

        // everything rolled back and the key was released, so the same key can be retried
        assertThat(count("patient")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(idempotencyRepository.findById("key-1")).isEmpty();

        IdempotentPatientService.Result retry = idempotentPatientService.createPatient("key-1", request);

        assertThat(retry.replayed()).isFalse();
        assertThat(count("patient")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        IdempotencyRecord key = idempotencyRepository.findById("key-1").orElseThrow();
        assertThat(key.getStatus()).isEqualTo(IdempotencyRecord.COMPLETED);

        // a third call replays the stored response and changes nothing
        IdempotentPatientService.Result replay = idempotentPatientService.createPatient("key-1", request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.patient().getId()).isEqualTo(retry.patient().getId());
        assertThat(count("patient")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }
}