package com.pm.patientservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.OutboxEventRepository;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import patient.events.PatientEvent;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Mock private OutboxEventRepository repository;

    @InjectMocks private OutboxService outboxService;

    private static Patient patient() {
        Patient p = new Patient();
        p.setId(ID);
        p.setName("Jane Doe");
        p.setEmail("jane@example.com");
        p.setAddress("1 Main St");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return p;
    }

    @Test
    void recordPatientCreated_savesAnUnpublishedRowWithTheProtobufPayload() throws Exception {
        when(repository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        outboxService.recordPatientCreated(patient());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent row = captor.getValue();

        assertThat(row.getAggregateType()).isEqualTo("PATIENT");
        assertThat(row.getAggregateId()).isEqualTo(ID.toString()); // later used as the Kafka key
        assertThat(row.getEventType()).isEqualTo("PATIENT_CREATED");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPublishedAt()).isNull(); // not published until the relay delivers it

        PatientEvent event = PatientEvent.parseFrom(row.getPayload());
        assertThat(event.getPatientId()).isEqualTo(ID.toString());
        assertThat(event.getName()).isEqualTo("Jane Doe");
        assertThat(event.getEmail()).isEqualTo("jane@example.com");
        assertThat(event.getEventType()).isEqualTo("PATIENT_CREATED");
    }

    /** Guards the design rule: the outbox row may only be written inside the caller's transaction. */
    @Test
    void recordPatientCreated_requiresAnExistingTransaction() throws Exception {
        Method method = OutboxService.class.getMethod("recordPatientCreated", Patient.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}