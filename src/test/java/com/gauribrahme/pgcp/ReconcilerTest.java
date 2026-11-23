package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.instance.Plan;
import com.gauribrahme.pgcp.provisioner.ActualState;
import com.gauribrahme.pgcp.workflow.Reconciler;
import com.gauribrahme.pgcp.workflow.WorkType;
import com.gauribrahme.pgcp.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconcilerTest extends AbstractIntegrationTest {
    @Autowired Reconciler reconciler;
    @Autowired WorkflowService worker;

    private Instance available() {
        Instance i = create("acme", "k1", "orders-db");
        worker.pollOnce();
        return reload(i.getId());
    }

    @Test
    void noDriftMeansNoChange() {
        Instance i = available();
        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(reload(i.getId()).isDriftDetected()).isFalse();
    }

    @Test
    void planDriftIsHealedByEnqueuedResize() {
        Instance i = available();
        // Someone resized the real database behind the control plane's back.
        provisioner.putActual("acme", i.getId(), new ActualState(Plan.LARGE, i.getStorageGb(), true, i.getEndpoint()));

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        Instance drifted = reload(i.getId());
        assertThat(drifted.isDriftDetected()).isTrue();
        assertThat(drifted.getState()).isEqualTo(InstanceState.UPDATING);
        assertThat(workItems.findByInstanceIdOrderByCreatedAt(i.getId()))
                .extracting(w -> w.getType()).contains(WorkType.RESIZE);

        worker.pollOnce(); // the resize brings actual back to desired (SMALL)
        assertThat(provisioner.describe("acme", i.getId()).orElseThrow().plan()).isEqualTo(Plan.SMALL);
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.AVAILABLE);

        assertThat(reconciler.reconcileOnce()).isZero(); // converged
        assertThat(reload(i.getId()).isDriftDetected()).isFalse(); // flag cleared
    }

    @Test
    void missingResourceIsFlaggedAndInstanceFailed() {
        Instance i = available();
        provisioner.removeActual("acme", i.getId());

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        Instance after = reload(i.getId());
        assertThat(after.getState()).isEqualTo(InstanceState.FAILED);
        assertThat(after.isDriftDetected()).isTrue();
        assertThat(after.getFailureReason()).contains("missing");
    }

    @Test
    void largerThanDesiredStorageIsOnlyFlagged() {
        Instance i = available();
        provisioner.putActual("acme", i.getId(), new ActualState(Plan.SMALL, 200, true, i.getEndpoint()));
        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        Instance after = reload(i.getId());
        assertThat(after.isDriftDetected()).isTrue();
        assertThat(after.getState()).isEqualTo(InstanceState.AVAILABLE); // cannot shrink, so no auto-fix
    }

    @Test
    void instancesInFlightAreLeftAlone() {
        Instance i = create("acme", "k1", "orders-db"); // still REQUESTED, nothing provisioned
        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(reload(i.getId()).getState()).isEqualTo(InstanceState.REQUESTED);
    }
}
