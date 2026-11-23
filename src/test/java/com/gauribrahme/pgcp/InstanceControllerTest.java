package com.gauribrahme.pgcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gauribrahme.pgcp.common.ApiException;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceController;
import com.gauribrahme.pgcp.instance.InstanceService;
import com.gauribrahme.pgcp.instance.Plan;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer slice: status codes, headers, validation and problem+json shape. The service is mocked. */
@WebMvcTest(InstanceController.class)
class InstanceControllerTest {
    private static final String BODY = "{\"name\":\"orders-db\",\"plan\":\"small\"}";

    @Autowired MockMvc mvc;
    @MockBean InstanceService service;

    private Instance sample() {
        return new Instance("acme", "orders-db", Plan.SMALL, 20, "16", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void createReturns202WithLocation() throws Exception {
        Instance i = sample();
        when(service.create(eq("acme"), eq("k-1"), any())).thenReturn(i);
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/instances/" + i.getId()))
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.plan").value("small"))
                .andExpect(jsonPath("$.cpu").value(1));
    }

    @Test
    void missingIdempotencyKeyIs400ProblemJson() throws Exception {
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void missingTenantHeaderIs400() throws Exception {
        mvc.perform(get("/api/v1/instances")).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void bodyValidationFailureListsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"plan\":\"small\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void unknownPlanIs400() throws Exception {
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"orders-db\",\"plan\":\"huge\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void notFoundIs404ProblemJson() throws Exception {
        when(service.get("acme", "nope")).thenThrow(ApiException.notFound("Instance", "nope"));
        mvc.perform(get("/api/v1/instances/nope").header("X-Tenant-Id", "acme"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void conflictIs409AndQuotaIs422() throws Exception {
        when(service.create(eq("acme"), eq("dup"), any())).thenThrow(ApiException.conflict("idempotency-key-reuse", "x"));
        when(service.create(eq("acme"), eq("full"), any())).thenThrow(ApiException.unprocessable("quota-exceeded", "x"));
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme").header("Idempotency-Key", "dup")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency-key-reuse"));
        mvc.perform(post("/api/v1/instances").header("X-Tenant-Id", "acme").header("Idempotency-Key", "full")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("quota-exceeded"));
    }

    @Test
    void patchAndDeleteReturn202() throws Exception {
        Instance i = sample();
        when(service.update(eq("acme"), eq(i.getId()), any())).thenReturn(i);
        when(service.delete("acme", i.getId())).thenReturn(i);
        mvc.perform(patch("/api/v1/instances/" + i.getId()).header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storageGb\":50}"))
                .andExpect(status().isAccepted());
        mvc.perform(delete("/api/v1/instances/" + i.getId()).header("X-Tenant-Id", "acme"))
                .andExpect(status().isAccepted());
    }

    @Test
    void pageSizeAboveLimitIs400() throws Exception {
        mvc.perform(get("/api/v1/instances?size=1000").header("X-Tenant-Id", "acme"))
                .andExpect(status().isBadRequest());
    }
}
