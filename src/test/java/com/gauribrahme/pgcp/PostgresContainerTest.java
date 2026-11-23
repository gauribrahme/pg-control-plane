package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.gauribrahme.pgcp.instance.CreateInstanceRequest;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceRepository;
import com.gauribrahme.pgcp.instance.InstanceService;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.instance.Plan;
import com.gauribrahme.pgcp.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the same Flyway migrations and the real SELECT ... FOR UPDATE SKIP LOCKED claim query against PostgreSQL 16.
 * Skipped automatically when no Docker daemon is available (for example on a laptop with Docker stopped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"pgcp.scheduling.enabled=false", "pgcp.worker.skip-locked=true",
        "pgcp.in-memory.delay-ms=0"})
class PostgresContainerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired InstanceService service;
    @Autowired WorkflowService worker;
    @Autowired InstanceRepository instances;

    @Test
    void createIsProvisionedUsingSkipLockedClaim() {
        Instance i = service.create("acme", "pg-it-1", new CreateInstanceRequest("pg-it-db", Plan.SMALL, null, null));
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(instances.findById(i.getId()).orElseThrow().getState()).isEqualTo(InstanceState.AVAILABLE);
    }
}
