package com.gauribrahme.pgcp.workflow;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkItemRepository extends JpaRepository<WorkItem, String> {

    String CLAIMABLE = "((status = 'PENDING' AND next_attempt_at <= :now) "
            + "OR (status = 'IN_PROGRESS' AND locked_until < :now))";

    /**
     * PostgreSQL: row-locks candidates and skips rows another worker already locked, so N workers
     * never block on each other and never pick the same item.
     */
    @Query(value = "SELECT id FROM work_item WHERE " + CLAIMABLE
            + " ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<String> findClaimableSkipLocked(@Param("now") Instant now, @Param("limit") int limit);

    /** Portable fallback (H2): plain SELECT; correctness comes from the compare-and-set in tryClaim. */
    @Query(value = "SELECT id FROM work_item WHERE " + CLAIMABLE
            + " ORDER BY next_attempt_at, id LIMIT :limit", nativeQuery = true)
    List<String> findClaimablePortable(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Compare-and-set claim: succeeds for exactly one worker. Also takes over IN_PROGRESS items whose
     * lease expired (worker crashed). Increments attempts so a crash-looping item eventually gives up.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkItem w set w.status = com.gauribrahme.pgcp.workflow.WorkStatus.IN_PROGRESS, "
            + "w.lockedBy = :worker, w.lockedUntil = :until, w.attempts = w.attempts + 1, w.updatedAt = :now "
            + "where w.id = :id and ((w.status = com.gauribrahme.pgcp.workflow.WorkStatus.PENDING and w.nextAttemptAt <= :now) "
            + "or (w.status = com.gauribrahme.pgcp.workflow.WorkStatus.IN_PROGRESS and w.lockedUntil < :now))")
    int tryClaim(@Param("id") String id, @Param("worker") String worker,
                 @Param("now") Instant now, @Param("until") Instant until);

    List<WorkItem> findByInstanceIdOrderByCreatedAt(String instanceId);

    long countByStatus(WorkStatus status);
}
