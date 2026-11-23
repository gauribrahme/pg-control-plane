package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gauribrahme.pgcp.workflow.WorkflowService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end over HTTP with the real service, H2 and the in-memory provisioner. */
class ApiFlowTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired WorkflowService worker;

    private MvcResult createDb(String tenant, String key, String name) throws Exception {
        return mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", tenant).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"plan\":\"medium\",\"storageGb\":100}")).andReturn();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MvcResult created = createDb("acme", "k1", "orders-db");
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String location = created.getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/v1/instances/");

        mvc.perform(get(location).header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REQUESTED"));
        worker.pollOnce();
        mvc.perform(get(location).header("X-Tenant-Id", "acme"))
                .andExpect(jsonPath("$.state").value("AVAILABLE")).andExpect(jsonPath("$.endpoint").isNotEmpty())
                .andExpect(jsonPath("$.memoryGb").value(8));

        mvc.perform(patch(location).header("X-Tenant-Id", "acme").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"large\",\"storageGb\":200}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("UPDATING"));
        mvc.perform(patch(location).header("X-Tenant-Id", "acme").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageGb\":300}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        worker.pollOnce();

        mvc.perform(post(location + "/backups").header("X-Tenant-Id", "acme")).andExpect(status().isAccepted());
        worker.pollOnce();
        mvc.perform(get(location + "/backups").header("X-Tenant-Id", "acme"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        mvc.perform(delete(location).header("X-Tenant-Id", "acme"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("DELETING"));
        worker.pollOnce();
        mvc.perform(get(location).header("X-Tenant-Id", "acme")).andExpect(jsonPath("$.state").value("DELETED"));
        mvc.perform(get("/api/v1/instances").header("X-Tenant-Id", "acme"))
                .andExpect(jsonPath("$.totalElements").value(0)); // deleted instances are hidden from the list
    }

    @Test
    void tenantCannotReadAnotherTenantsInstance() throws Exception {
        String location = createDb("acme", "k1", "orders-db").getResponse().getHeader("Location");
        mvc.perform(get(location).header("X-Tenant-Id", "globex"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(delete(location).header("X-Tenant-Id", "globex")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/instances").header("X-Tenant-Id", "globex"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(location).header("X-Tenant-Id", "acme")).andExpect(status().isOk());
    }

    @Test
    void idempotentReplayReturnsSameIdAndConflictOnDifferentBody() throws Exception {
        String first = createDb("acme", "k1", "orders-db").getResponse().getContentAsString();
        String second = createDb("acme", "k1", "orders-db").getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(createDb("acme", "k1", "different-db").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void quotaExceededIs422AndListIsPaged() throws Exception {
        createDb("acme", "k1", "db-one");
        createDb("acme", "k2", "db-two");
        MvcResult third = createDb("acme", "k3", "db-three");
        assertThat(third.getResponse().getStatus()).isEqualTo(422);
        mvc.perform(get("/api/v1/instances?size=1&page=1").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void actuatorExposesCustomMetrics() throws Exception {
        createDb("acme", "k1", "db-one");
        worker.pollOnce();
        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("pgcp_provisioning_duration_seconds_count")
                .contains("pgcp_instances{").contains("state=\"available\"");
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("json")));
    }
}
