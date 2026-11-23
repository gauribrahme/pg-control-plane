package com.gauribrahme.pgcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gauribrahme.pgcp.instance.IllegalStateTransitionException;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.instance.Plan;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InstanceStateMachineTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void happyPathCreateUpdateDelete() {
        Instance i = new Instance("acme", "db1", Plan.SMALL, 20, "16", NOW);
        assertThat(i.getState()).isEqualTo(InstanceState.REQUESTED);
        i.transitionTo(InstanceState.PROVISIONING, NOW);
        i.transitionTo(InstanceState.AVAILABLE, NOW);
        i.transitionTo(InstanceState.UPDATING, NOW);
        i.transitionTo(InstanceState.AVAILABLE, NOW);
        i.transitionTo(InstanceState.DELETING, NOW);
        i.transitionTo(InstanceState.DELETED, NOW);
        assertThat(i.getState()).isEqualTo(InstanceState.DELETED);
    }

    @ParameterizedTest
    @EnumSource(value = InstanceState.class, names = {"REQUESTED", "PROVISIONING", "UPDATING", "DELETING"})
    void anyInProgressStateCanFail(InstanceState inProgress) {
        assertThat(inProgress.isInProgress()).isTrue();
        assertThat(inProgress.canTransitionTo(InstanceState.FAILED)).isTrue();
    }

    @Test
    void allowedTransitionTableIsExactlyAsDesigned() {
        assertThat(InstanceState.REQUESTED.allowedNext()).isEqualTo(Set.of(InstanceState.PROVISIONING, InstanceState.FAILED));
        assertThat(InstanceState.PROVISIONING.allowedNext()).isEqualTo(Set.of(InstanceState.AVAILABLE, InstanceState.FAILED));
        assertThat(InstanceState.AVAILABLE.allowedNext())
                .isEqualTo(Set.of(InstanceState.UPDATING, InstanceState.DELETING, InstanceState.FAILED));
        assertThat(InstanceState.UPDATING.allowedNext()).isEqualTo(Set.of(InstanceState.AVAILABLE, InstanceState.FAILED));
        assertThat(InstanceState.DELETING.allowedNext()).isEqualTo(Set.of(InstanceState.DELETED, InstanceState.FAILED));
        assertThat(InstanceState.FAILED.allowedNext()).isEqualTo(Set.of(InstanceState.DELETING));
        assertThat(InstanceState.DELETED.allowedNext()).isEmpty();
    }

    @Test
    void illegalTransitionsThrowAndLeaveStateUnchanged() {
        Instance i = new Instance("acme", "db1", Plan.SMALL, 20, "16", NOW);
        assertThatThrownBy(() -> i.transitionTo(InstanceState.AVAILABLE, NOW))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("REQUESTED").hasMessageContaining("AVAILABLE");
        assertThat(i.getState()).isEqualTo(InstanceState.REQUESTED);
    }

    @ParameterizedTest
    @EnumSource(InstanceState.class)
    void everyPairOutsideTheTableIsRejected(InstanceState from) {
        Set<InstanceState> illegal = EnumSet.allOf(InstanceState.class);
        illegal.removeAll(from.allowedNext());
        for (InstanceState to : illegal) {
            assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isFalse();
        }
    }

    @Test
    void failRecordsReason() {
        Instance i = new Instance("acme", "db1", Plan.SMALL, 20, "16", NOW);
        i.fail("boom", NOW);
        assertThat(i.getState()).isEqualTo(InstanceState.FAILED);
        assertThat(i.getFailureReason()).isEqualTo("boom");
    }
}
