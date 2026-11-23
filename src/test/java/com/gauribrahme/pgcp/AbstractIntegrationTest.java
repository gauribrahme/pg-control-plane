package com.gauribrahme.pgcp;

import com.gauribrahme.pgcp.common.IdempotencyRepository;
import com.gauribrahme.pgcp.instance.BackupRepository;
import com.gauribrahme.pgcp.instance.CreateInstanceRequest;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceRepository;
import com.gauribrahme.pgcp.instance.InstanceService;
import com.gauribrahme.pgcp.instance.Plan;
import com.gauribrahme.pgcp.provisioner.InMemoryProvisioner;
import com.gauribrahme.pgcp.tenant.TenantRepository;
import com.gauribrahme.pgcp.workflow.WorkItem;
import com.gauribrahme.pgcp.workflow.WorkItemRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Full Spring context on H2 (PostgreSQL mode) running the real Flyway migrations. No Docker needed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureObservability
@Import(AbstractIntegrationTest.TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired protected InstanceRepository instances;
    @Autowired protected WorkItemRepository workItems;
    @Autowired protected BackupRepository backups;
    @Autowired protected IdempotencyRepository idempotency;
    @Autowired protected TenantRepository tenants;
    @Autowired protected InMemoryProvisioner provisioner;
    @Autowired protected InstanceService service;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanState() {
        workItems.deleteAll();
        backups.deleteAll();
        idempotency.deleteAll();
        instances.deleteAll();
        tenants.deleteAll();
        provisioner.reset();
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
    }

    protected Instance create(String tenant, String key, String name) {
        return service.create(tenant, key, new CreateInstanceRequest(name, Plan.SMALL, null, null));
    }

    protected WorkItem firstItem(String instanceId) {
        return workItems.findByInstanceIdOrderByCreatedAt(instanceId).get(0);
    }

    protected Instance reload(String id) {
        return instances.findById(id).orElseThrow();
    }
}
