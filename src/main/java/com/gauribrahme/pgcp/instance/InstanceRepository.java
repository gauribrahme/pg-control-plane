package com.gauribrahme.pgcp.instance;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstanceRepository extends JpaRepository<Instance, String> {
    /** Tenant-scoped lookup: another tenant's id is simply not found (404 rather than 403, so existence does not leak). */
    Optional<Instance> findByIdAndTenantId(String id, String tenantId);

    Page<Instance> findByTenantIdAndStateNot(String tenantId, InstanceState state, Pageable pageable);

    Page<Instance> findByTenantIdAndState(String tenantId, InstanceState state, Pageable pageable);

    long countByTenantIdAndStateNot(String tenantId, InstanceState state);

    long countByState(InstanceState state);

    boolean existsByTenantIdAndNameAndStateNot(String tenantId, String name, InstanceState state);

    @Query("select i.id from Instance i where i.state = :state order by i.id")
    List<String> findIdsByState(@Param("state") InstanceState state);
}
