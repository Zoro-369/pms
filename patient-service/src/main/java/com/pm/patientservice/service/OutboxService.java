package com.pm.patientservice.service;

import com.pm.patientservice.model.OutboxEvent;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.OutboxEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import patient.events.PatientEvent;

/** Writes domain events to the outbox table. It never talks to Kafka. */
@Service
public class OutboxService {

    public static final String PATIENT_AGGREGATE = "PATIENT";
    public static final String PATIENT_CREATED = "PATIENT_CREATED";

    private final OutboxEventRepository repository;

    public OutboxService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    /**
     * MANDATORY: this must be called inside the caller's transaction, so the event row commits or
     * rolls back together with the business change. Calling it without a transaction throws
     * instead of silently writing an event that could outlive a rolled-back patient.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent recordPatientCreated(Patient patient) {
        PatientEvent event = PatientEvent.newBuilder()
                .setPatientId(patient.getId().toString())
                .setName(patient.getName())
                .setEmail(patient.getEmail())
                .setEventType(PATIENT_CREATED)
                .build();

        OutboxEvent row = new OutboxEvent();
        row.setAggregateType(PATIENT_AGGREGATE);
        row.setAggregateId(patient.getId().toString());
        row.setEventType(PATIENT_CREATED);
        row.setPayload(event.toByteArray());
        row.setCreatedAt(Instant.now());
        return repository.save(row);
    }
}