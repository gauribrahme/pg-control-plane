package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gauribrahme.pgcp.common.ApiException;
import com.gauribrahme.pgcp.instance.CreateInstanceRequest;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.Plan;
import com.gauribrahme.pgcp.instance.UpdateInstanceRequest;
import com.gauribrahme.pgcp.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class InstanceServiceTest extends AbstractIntegrationTest {
    @Autowired WorkflowService worker;

    private void assertApi(Throwable t, HttpStatus status, String code) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void sameKeyAndBodyReturnsSameInstanceAndOnlyOneWorkItem() {
        Instance a = create("acme", "key-1", "orders-db");
        Instance b = create("acme", "key-1", "orders-db");
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(instances.count()).isEqualTo(1);
        assertThat(workItems.count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs409() {
        create("acme", "key-1", "orders-db");
        assertThatThrownBy(() -> create("acme", "key-1", "other-db"))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT, "idempotency-key-reuse"));
        assertThat(instances.count()).isEqualTo(1);
    }

    @Test
    void sameKeyIsScopedPerTenant() {
        Instance a = create("acme", "key-1", "orders-db");
        Instance b = create("globex", "key-1", "orders-db");
        assertThat(b.getId()).isNotEqualTo(a.getId());
    }

    @Test
    void quotaIsEnforcedPerTenantAndFreedByDelete() {
        create("acme", "k1", "db-one");
        Instance two = create("acme", "k2", "db-two");
        assertThatThrownBy(() -> create("acme", "k3", "db-three"))
                .satisfies(t -> assertApi(t, HttpStatus.UNPROCESSABLE_ENTITY, "quota-exceeded"));
        // another tenant is unaffected
        create("globex", "k1", "db-one");

        worker.pollOnce(); // provision acme's first two (and globex)
        worker.pollOnce();
        service.delete("acme", two.getId());
        worker.pollOnce();
        create("acme", "k3", "db-three"); // slot freed
        assertThat(instances.count()).isEqualTo(4);
    }

    @Test
    void otherTenantsInstanceIsNotFound() {
        Instance a = create("acme", "k1", "orders-db");
        assertThatThrownBy(() -> service.get("globex", a.getId()))
                .satisfies(t -> assertApi(t, HttpStatus.NOT_FOUND, "not-found"));
        assertThatThrownBy(() -> service.delete("globex", a.getId()))
                .satisfies(t -> assertApi(t, HttpStatus.NOT_FOUND, "not-found"));
        assertThatThrownBy(() -> service.update("globex", a.getId(), new UpdateInstanceRequest(null, 50)))
                .satisfies(t -> assertApi(t, HttpStatus.NOT_FOUND, "not-found"));
        assertThat(service.list("globex", null, 0, 20).getTotalElements()).isZero();
        assertThat(service.list("acme", null, 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void duplicateNameInTenantIs409() {
        create("acme", "k1", "orders-db");
        assertThatThrownBy(() -> create("acme", "k2", "orders-db"))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT, "name-in-use"));
    }

    @Test
    void storageBeyondPlanLimitIs422() {
        assertThatThrownBy(() -> service.create("acme", "k1", new CreateInstanceRequest("big-db", Plan.SMALL, 600, null)))
                .satisfies(t -> assertApi(t, HttpStatus.UNPROCESSABLE_ENTITY, "storage-exceeds-plan"));
    }

    @Test
    void resizeRequiresAvailableAndCannotShrink() {
        Instance i = create("acme", "k1", "orders-db");
        assertThatThrownBy(() -> service.update("acme", i.getId(), new UpdateInstanceRequest(null, 50)))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT, "illegal-state-transition")); // still REQUESTED
        worker.pollOnce();
        assertThatThrownBy(() -> service.update("acme", i.getId(), new UpdateInstanceRequest(null, 10)))
                .satisfies(t -> assertApi(t, HttpStatus.UNPROCESSABLE_ENTITY, "storage-shrink"));
        assertThatThrownBy(() -> service.update("acme", i.getId(), new UpdateInstanceRequest(null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.UNPROCESSABLE_ENTITY, "empty-update"));
    }

    @Test
    void backupRequiresAvailable() {
        Instance i = create("acme", "k1", "orders-db");
        assertThatThrownBy(() -> service.requestBackup("acme", i.getId()))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT, "illegal-state"));
    }
}
