package com.gauribrahme.pgcp.common;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
    Optional<IdempotencyRecord> findByTenantIdAndIdemKey(String tenantId, String idemKey);
}
