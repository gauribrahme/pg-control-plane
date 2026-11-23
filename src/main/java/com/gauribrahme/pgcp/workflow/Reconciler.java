package com.gauribrahme.pgcp.workflow;

import com.gauribrahme.pgcp.config.PgcpMetrics;
import com.gauribrahme.pgcp.config.PgcpProperties;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceRepository;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.provisioner.ActualState;
import com.gauribrahme.pgcp.provisioner.DatabaseProvisioner;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level-triggered safety net: periodically compares desired state (DB) with actual state (provisioner.describe).
 * Policy, chosen to be safe rather than clever:
 * <ul>
 *   <li>Resource missing: flag drift and mark the instance FAILED. Auto-recreating would silently lose data.</li>
 *   <li>Plan differs or storage smaller than desired: flag drift and enqueue a RESIZE (self-heal).</li>
 *   <li>Storage larger than desired: flag only (storage cannot shrink).</li>
 *   <li>Matches again: clear the drift flag.</li>
 * </ul>
 * Only AVAILABLE instances are checked; in-flight workflows own the instances they are changing.
 */
@Component
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final InstanceRepository instances;
    private final WorkItemRepository items;
    private final DatabaseProvisioner provisioner;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PgcpProperties props;
    private final PgcpMetrics metrics;

    public Reconciler(InstanceRepository instances, WorkItemRepository items, DatabaseProvisioner provisioner,
                      TransactionTemplate tx, Clock clock, PgcpProperties props, PgcpMetrics metrics) {
        this.instances = instances;
        this.items = items;
        this.provisioner = provisioner;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pgcp.reconciler.interval-ms:30000}")
    public void scheduledRun() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.error("reconcile run failed", e);
        }
    }

    /** One pass over all AVAILABLE instances. Returns how many had drift. */
    public int reconcileOnce() {
        int drifted = 0;
        for (String id : instances.findIdsByState(InstanceState.AVAILABLE)) {
            try (var ignored = MDC.putCloseable("instanceId", id)) {
                if (reconcile(id)) {
                    drifted++;
                }
            } catch (RuntimeException e) {
                log.warn("reconcile failed for instance, will retry next pass: {}", e.toString());
            }
        }
        return drifted;
    }

    private boolean reconcile(String id) {
        Instance snapshot = instances.findById(id).orElse(null);
        if (snapshot == null || snapshot.getState() != InstanceState.AVAILABLE) {
            return false;
        }
        // Remote call outside the transaction.
        Optional<ActualState> actual = provisioner.describe(snapshot.getTenantId(), id);
        return Boolean.TRUE.equals(tx.execute(s -> apply(id, actual)));
    }

    private boolean apply(String id, Optional<ActualState> actual) {
        Instance i = instances.findById(id).orElseThrow();
        if (i.getState() != InstanceState.AVAILABLE) {
            return false; // changed while we were describing; next pass will look again
        }
        Instant now = Instant.now(clock);
        if (actual.isEmpty()) {
            i.setDriftDetected(true, now);
            i.fail("Reconciler: resource missing from provisioner", now);
            metrics.drift("missing");
            log.error("drift: resource missing, instance marked FAILED");
            return true;
        }
        ActualState a = actual.get();
        boolean planDrift = a.plan() != i.getPlan();
        boolean smaller = a.storageGb() < i.getStorageGb();
        if (planDrift || smaller) {
            i.setDriftDetected(true, now);
            i.transitionTo(InstanceState.UPDATING, now);
            items.save(new WorkItem(i.getId(), WorkType.RESIZE, null, props.worker().maxAttempts(), now));
            metrics.drift("spec");
            log.warn("drift: actual plan={} storage={} desired plan={} storage={}; resize enqueued",
                    a.plan(), a.storageGb(), i.getPlan(), i.getStorageGb());
            return true;
        }
        if (a.storageGb() > i.getStorageGb()) {
            if (!i.isDriftDetected()) {
                metrics.drift("storage-larger");
            }
            i.setDriftDetected(true, now);
            return true;
        }
        if (i.isDriftDetected()) {
            i.setDriftDetected(false, now);
            log.info("drift cleared");
        }
        return false;
    }
}
