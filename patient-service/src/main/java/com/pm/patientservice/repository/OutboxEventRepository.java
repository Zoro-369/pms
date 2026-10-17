package com.pm.patientservice.repository;

import com.pm.patientservice.model.OutboxEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // The relay's polling query (FOR UPDATE SKIP LOCKED) is added on Day 9.
}