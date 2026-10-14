package com.pm.patientservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.patientservice.model.Patient;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import patient.events.PatientEvent;

@ExtendWith(MockitoExtension.class)
class KafkaProducerTest {

    @Mock private KafkaTemplate<String, byte[]> kafkaTemplate;

    @InjectMocks private KafkaProducer kafkaProducer;

    private static Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        p.setName("Jane Doe");
        p.setEmail("jane@example.com");
        p.setAddress("1 Main St");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return p;
    }

    @Test
    void sendEvent_publishesProtobufPatientCreatedEventToPatientTopic() throws Exception {
        kafkaProducer.sendEvent(patient());

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(kafkaTemplate).send(eq("patient"), payload.capture());

        PatientEvent event = PatientEvent.parseFrom(payload.getValue());
        assertThat(event.getPatientId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(event.getName()).isEqualTo("Jane Doe");
        assertThat(event.getEmail()).isEqualTo("jane@example.com");
        assertThat(event.getEventType()).isEqualTo("PATIENT_CREATED");
    }

    /**
     * Characterization test: pins the CURRENT behaviour, where a synchronous send error is caught
     * and only logged, so the caller never learns the event was lost. (Asynchronous send failures
     * are not even caught here.) Change this when the transactional outbox replaces direct sends.
     */
    @Test
    void sendEvent_whenSendThrows_currentlySwallowsTheError() {
        when(kafkaTemplate.send(anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatCode(() -> kafkaProducer.sendEvent(patient())).doesNotThrowAnyException();
    }
}