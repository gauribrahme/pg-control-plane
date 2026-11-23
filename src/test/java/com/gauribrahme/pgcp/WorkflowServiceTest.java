package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.gauribrahme.pgcp.instance.Backup;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.instance.UpdateInstanceRequest;
import com.gauribrahme.pgcp.provisioner.InMemoryProvisioner.Op;
import com.gauribrahme.pgcp.provisioner.ProvisionSpec;
import com.gauribrahme.pgcp.workflow.WorkItem;
import com.gauribrahme.pgcp.workflow.WorkStatus;
import com.gauribrahme.pgcp.workflow.WorkflowService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class WorkflowServiceTest extends AbstractIntegrationTest {
    @Autowired WorkflowService worker;
    @Autowired TransactionTemplate tx;

    @Test
    void happyPathProvisionsAndCompletesWorkItem() {
        Instance i = create("acme", "k1", "orders-db");
        assertThat(worker.pollOnce()).isEqualTo(1);

        Instance after = reload(i.getId());
        assertThat(after.getState()).isEqualTo(InstanceState.AVAILABLE);
        assertThat(after.getEndpoint()).isNotBlank();
        assertThat(firstItem(i.getId()).getStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(provisioner.describe("acme", i.getId())).isPresent();
        assertThat(worker.pollOnce()).isZero();
    }

    @Test
    void transientFailuresRetryWithExponentialBackoffThenSucceed() {
        provisioner.failNext(Op.CREATE, 2);
        Instance i = create("acme", "k1", "orders-db");
        Instant t0 = clock.instant();

        assertThat(worker.pollOnce()).isEqualTo(1); // attempt 1 fails
        WorkItem w = firstItem(i.getId());
        assertThat(w.getStatus()).isEqualTo(WorkStatus.PENDING);
        assertThat(w.getAttempts()).isEqualTo(1);
        assertThat(w.getLastError()).contains("injected transient failure");
        assertThat(w.getNextAttemptAt()).isEqualTo(t0.plusSeconds(1)); // base 1s * 2^0
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.PROVISIONING);

        assertThat(worker.pollOnce()).isZero(); // still backing off

        clock.advance(Duration.ofSeconds(1));
        assertThat(worker.pollOnce()).isEqualTo(1); // attempt 2 fails
        w = firstItem(i.getId());
        assertThat(w.getAttempts()).isEqualTo(2);
        assertThat(w.getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(2)); // base 1s * 2^1

        clock.advance(Duration.ofSeconds(1));
        assertThat(worker.pollOnce()).isZero(); // 1s into a 2s backoff
        clock.advance(Duration.ofSeconds(1));
        assertThat(worker.pollOnce()).isEqualTo(1); // attempt 3 succeeds

        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);
        assertThat(firstItem(i.getId()).getStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(provisioner.callCount(Op.CREATE)).isEqualTo(3);
    }

    @Test
    void exhaustingRetriesMarksWorkItemAndInstanceFailed() {
        provisioner.failAlways(Op.CREATE);
        Instance i = create("acme", "k1", "orders-db");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(worker.pollOnce()).as("attempt " + attempt).isEqualTo(1);
            clock.advance(Duration.ofSeconds(30));
        }
        WorkItem w = firstItem(i.getId());
        assertThat(w.getStatus()).isEqualTo(WorkStatus.FAILED);
        assertThat(w.getAttempts()).isEqualTo(3);
        assertThat(w.getLastError()).contains("injected permanent failure");

        Instance after = reload(i.getId());
        assertThat(after.getState()).isEqualTo(InstanceState.FAILED);
        assertThat(after.getFailureReason()).contains("after 3 attempts");
        assertThat(worker.pollOnce()).isZero(); // nothing left to retry
        assertThat(provisioner.callCount(Op.CREATE)).isEqualTo(3);
    }

    @Test
    void workerCrashMidItemIsRecoveredAfterLeaseExpires() {
        Instance i = create("acme", "k1", "orders-db");
        String itemId = firstItem(i.getId()).getId();
        // Simulate a worker that claimed the item and then died: item is IN_PROGRESS with a 30s lease.
        int claimed = tx.execute(s -> workItems.tryClaim(itemId, "dead-worker", clock.instant(),
                clock.instant().plusSeconds(30)));
        assertThat(claimed).isEqualTo(1);

        assertThat(worker.pollOnce()).isZero(); // lease still valid: nobody steals it
        clock.advance(Duration.ofSeconds(31));
        assertThat(worker.pollOnce()).isEqualTo(1); // lease expired: another worker takes over
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);
        assertThat(firstItem(i.getId()).getAttempts()).isEqualTo(2); // the dead attempt still counts
    }

    @Test
    void crashAfterProviderCreateBeforeCommitIsSafeToRerun() {
        Instance i = create("acme", "k1", "orders-db");
        // The previous attempt created the database but died before recording AVAILABLE.
        provisioner.create(ProvisionSpec.from(i));
        assertThat(provisioner.size()).isEqualTo(1);

        worker.pollOnce();
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);
        assertThat(provisioner.size()).isEqualTo(1); // no duplicate resource
    }

    @Test
    void resizeBackupAndDeleteFlows() {
        Instance i = create("acme", "k1", "orders-db");
        worker.pollOnce();

        service.update("acme", i.getId(), new UpdateInstanceRequest(null, 50));
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.UPDATING);
        worker.pollOnce();
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);
        assertThat(provisioner.describe("acme", i.getId()).orElseThrow().storageGb()).isEqualTo(50);

        Backup b = service.requestBackup("acme", i.getId());
        worker.pollOnce();
        Backup done = backups.findById(b.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(Backup.Status.COMPLETED);
        assertThat(done.getLocation()).contains(b.getId());

        service.delete("acme", i.getId());
        service.delete("acme", i.getId()); // repeated DELETE is a no-op while DELETING
        worker.pollOnce();
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.DELETED);
        assertThat(provisioner.describe("acme", i.getId())).isEmpty();
    }

    @Test
    void failedBackupDoesNotFailTheInstance() {
        Instance i = create("acme", "k1", "orders-db");
        worker.pollOnce();
        provisioner.failAlways(Op.BACKUP);
        Backup b = service.requestBackup("acme", i.getId());
        for (int n = 0; n < 3; n++) {
            worker.pollOnce();
            clock.advance(Duration.ofSeconds(30));
        }
        assertThat(backups.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(Backup.Status.FAILED);
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);
    }
}
