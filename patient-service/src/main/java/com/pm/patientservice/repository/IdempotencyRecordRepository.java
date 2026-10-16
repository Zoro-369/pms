package com.pm.patientservice.repository;

import com.pm.patientservice.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Atomically claims a key. Returns 1 if this caller inserted it (and now owns the request),
     * 0 if the key already existed. The unique primary key makes this safe under concurrency:
     * of N simultaneous callers with the same key exactly one gets 1.
     * Runs in its own committed transaction so other requests can see the claim immediately.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO idempotency_key (idempotency_key, request_hash, status, created_at) "
            + "VALUES (:idempotencyKey, :requestHash, 'IN_PROGRESS', now()) "
            + "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("idempotencyKey") String idempotencyKey,
                       @Param("requestHash") String requestHash);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update IdempotencyRecord r set r.status = 'COMPLETED', "
            + "r.responseStatus = :responseStatus, r.responseBody = :responseBody "
            + "where r.idempotencyKey = :idempotencyKey")
    int markCompleted(@Param("idempotencyKey") String idempotencyKey,
                      @Param("responseStatus") int responseStatus,
                      @Param("responseBody") String responseBody);

    /** Frees a key whose request failed, so the client can retry with the same key. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from IdempotencyRecord r "
            + "where r.idempotencyKey = :idempotencyKey and r.status = 'IN_PROGRESS'")
    int releaseInProgress(@Param("idempotencyKey") String idempotencyKey);
}