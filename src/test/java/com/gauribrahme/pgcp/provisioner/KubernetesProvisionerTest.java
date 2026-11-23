package com.gauribrahme.pgcp.provisioner;

import static org.assertj.core.api.Assertions.assertThat;

import com.gauribrahme.pgcp.instance.Plan;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

/** Uses fabric8's in-process mock API server in CRUD mode: no Docker, no cluster. */
@EnableKubernetesMockClient(crud = true)
class KubernetesProvisionerTest {
    KubernetesClient client;

    private static final String INSTANCE = "0b9f4f0e-1111-2222-3333-444455556666";
    private final ProvisionSpec spec = new ProvisionSpec("acme", INSTANCE, "orders-db", Plan.SMALL, 20, "16");

    @Test
    void createBuildsNamespaceSecretServiceAndStatefulSet() {
        KubernetesProvisioner p = new KubernetesProvisioner(client);
        ProvisionResult result = p.create(spec);

        assertThat(result.endpoint()).isEqualTo("pg-" + INSTANCE + ".tenant-acme.svc.cluster.local:5432");
        assertThat(client.namespaces().withName("tenant-acme").get()).isNotNull();
        assertThat(client.secrets().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get()).isNotNull();
        assertThat(client.services().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get()).isNotNull();

        StatefulSet sts = client.apps().statefulSets().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get();
        assertThat(sts.getSpec().getTemplate().getSpec().getContainers().get(0).getImage()).isEqualTo("postgres:16");
        assertThat(sts.getSpec().getVolumeClaimTemplates().get(0).getSpec().getResources().getRequests()
                .get("storage").toString()).isEqualTo("20Gi");
    }

    @Test
    void createIsIdempotentAndKeepsTheOriginalPassword() {
        KubernetesProvisioner p = new KubernetesProvisioner(client);
        p.create(spec);
        Secret first = client.secrets().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get();
        String password = first.getData() != null ? first.getData().get("password") : first.getStringData().get("password");

        p.create(spec); // simulate a retry after a crash
        Secret second = client.secrets().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get();
        String again = second.getData() != null ? second.getData().get("password") : second.getStringData().get("password");

        assertThat(again).isEqualTo(password);
        assertThat(client.apps().statefulSets().inNamespace("tenant-acme").list().getItems()).hasSize(1);
    }

    @Test
    void describeReflectsPlanAndStorageAndMissingIsEmpty() {
        KubernetesProvisioner p = new KubernetesProvisioner(client);
        assertThat(p.describe("acme", INSTANCE)).isEmpty();
        p.create(spec);
        ActualState state = p.describe("acme", INSTANCE).orElseThrow();
        assertThat(state.plan()).isEqualTo(Plan.SMALL);
        assertThat(state.storageGb()).isEqualTo(20);
        assertThat(state.ready()).isFalse(); // the mock never runs pods
    }

    @Test
    void resizeUpdatesPlanAndStorageButNeverShrinks() {
        KubernetesProvisioner p = new KubernetesProvisioner(client);
        p.create(spec);
        p.resize(new ProvisionSpec("acme", INSTANCE, "orders-db", Plan.LARGE, 100, "16"));
        ActualState grown = p.describe("acme", INSTANCE).orElseThrow();
        assertThat(grown.plan()).isEqualTo(Plan.LARGE);
        assertThat(grown.storageGb()).isEqualTo(100);

        p.resize(new ProvisionSpec("acme", INSTANCE, "orders-db", Plan.LARGE, 50, "16"));
        assertThat(p.describe("acme", INSTANCE).orElseThrow().storageGb()).isEqualTo(100);
    }

    @Test
    void backupCreatesJobOnceAndDeleteIsIdempotent() {
        KubernetesProvisioner p = new KubernetesProvisioner(client);
        p.create(spec);
        String loc1 = p.backup(spec, "b1");
        String loc2 = p.backup(spec, "b1");
        assertThat(loc1).isEqualTo(loc2);
        assertThat(client.batch().v1().jobs().inNamespace("tenant-acme").list().getItems()).hasSize(1);

        p.delete("acme", INSTANCE);
        assertThat(p.describe("acme", INSTANCE)).isEmpty();
        assertThat(client.secrets().inNamespace("tenant-acme").withName("pg-" + INSTANCE).get()).isNull();
        p.delete("acme", INSTANCE); // second delete must not throw
    }
}
