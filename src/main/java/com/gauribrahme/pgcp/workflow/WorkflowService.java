package com.gauribrahme.pgcp.workflow;

import com.gauribrahme.pgcp.config.PgcpMetrics;
import com.gauribrahme.pgcp.config.PgcpProperties;
import com.gauribrahme.pgcp.instance.Backup;
import com.gauribrahme.pgcp.instance.BackupRepository;
import com.gauribrahme.pgcp.instance.Instance;
import com.gauribrahme.pgcp.instance.InstanceRepository;
import com.gauribrahme.pgcp.instance.InstanceState;
import com.gauribrahme.pgcp.provisioner.DatabaseProvisioner;
import com.gauribrahme.pgcp.provisioner.ProvisionResult;
import com.gauribrahme.pgcp.provisioner.ProvisionSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The asynchronous half of the control plane: claims work_items and drives the provisioner.
 *
 * <p>Delivery is at-least-once. Safety comes from (a) idempotent provisioner calls and (b) state guards:
 * a step only runs if the instance is in the state that step expects, otherwise it is treated as already done.
 * The provisioner call happens OUTSIDE any DB transaction (never hold row locks across slow remote calls);
 * the state change and the work_item completion are committed together afterwards.
 */
@Service
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkItemRepository items;
    private final InstanceRepository instances;
    private final BackupRepository backups;
    private final DatabaseProvisioner provisioner;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PgcpProperties props;
    private final PgcpMetrics metrics;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public WorkflowService(WorkItemRepository items, InstanceRepository instances, BackupRepository backups,
                           DatabaseProvisioner provisioner, TransactionTemplate tx, Clock clock,
                           PgcpProperties props, PgcpMetrics metrics) {
        this.items = items;
        this.instances = instances;
        this.backups = backups;
        this.provisioner = provisioner;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${pgcp.worker.poll-interval-ms:1000}")
    public void scheduledPoll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("worker poll failed", e);
        }
    }

    /** Claim a batch and process it. Returns the number of items processed (tests call this directly). */
    public int pollOnce() {
        List<String> claimed = tx.execute(s -> claim());
        for (String id : claimed) {
            process(id);
        }
        return claimed.size();
    }

    private List<String> claim() {
        Instant now = Instant.now(clock);
        Instant until = now.plusSeconds(props.worker().leaseSeconds());
        int limit = props.worker().batchSize();
        List<String> candidates = props.worker().skipLocked()
                ? items.findClaimableSkipLocked(now, limit)
                : items.findClaimablePortable(now, limit);
        List<String> claimed = new ArrayList<>();
        for (String id : candidates) {
            if (items.tryClaim(id, workerId, now, until) == 1) {
                claimed.add(id);
            }
        }
        return claimed;
    }

    void process(String itemId) {
        WorkItem item = tx.execute(s -> items.findById(itemId).orElse(null));
        if (item == null) {
            return;
        }
        try (var i = MDC.putCloseable("instanceId", item.getInstanceId());
             var w = MDC.putCloseable("workItem", item.getId().substring(0, 8))) {
            if (item.getAttempts() > item.getMaxAttempts()) {
                // Only reachable after repeated lease expiry (worker crashed mid-item every time).
                onFailure(itemId, new IllegalStateException("lease expired too many times"));
                return;
            }
            try {
                switch (item.getType()) {
                    case CREATE -> create(item);
                    case RESIZE -> resize(item);
                    case BACKUP -> backup(item);
                    case DELETE -> delete(item);
                }
            } catch (RuntimeException e) {
                onFailure(itemId, e);
            }
        }
    }

    private void create(WorkItem item) {
        Instance inst = load(item);
        if (inst.getState() == InstanceState.REQUESTED) {
            transition(inst.getId(), InstanceState.PROVISIONING);
        } else if (inst.getState() != InstanceState.PROVISIONING) {
            skip(item, inst); // e.g. a previous attempt finished but crashed before marking the item done
            return;
        }
        ProvisionResult result = provisioner.create(ProvisionSpec.from(inst));
        tx.executeWithoutResult(s -> {
            Instance i = instances.findById(inst.getId()).orElseThrow();
            Instant now = Instant.now(clock);
            i.setEndpoint(result.endpoint());
            i.transitionTo(InstanceState.AVAILABLE, now);
            done(item.getId(), now);
            metrics.provisioned(Duration.between(i.getCreatedAt(), now));
        });
        log.info("instance provisioned endpoint={}", result.endpoint());
    }

    private void resize(WorkItem item) {
        Instance inst = load(item);
        if (inst.getState() != InstanceState.UPDATING) {
            skip(item, inst);
            return;
        }
        provisioner.resize(ProvisionSpec.from(inst));
        tx.executeWithoutResult(s -> {
            Instant now = Instant.now(clock);
            instances.findById(inst.getId()).orElseThrow().transitionTo(InstanceState.AVAILABLE, now);
            done(item.getId(), now);
        });
    }

    private void backup(WorkItem item) {
        Instance inst = load(item);
        String location = provisioner.backup(ProvisionSpec.from(inst), item.getPayload());
        tx.executeWithoutResult(s -> {
            Instant now = Instant.now(clock);
            backups.findById(item.getPayload()).orElseThrow().complete(location, now);
            done(item.getId(), now);
        });
    }

    private void delete(WorkItem item) {
        Instance inst = load(item);
        if (inst.getState() != InstanceState.DELETING) {
            skip(item, inst);
            return;
        }
        provisioner.delete(inst.getTenantId(), inst.getId());
        tx.executeWithoutResult(s -> {
            Instant now = Instant.now(clock);
            instances.findById(inst.getId()).orElseThrow().transitionTo(InstanceState.DELETED, now);
            done(item.getId(), now);
        });
    }

    /** Retry with exponential backoff, or give up: item FAILED and the instance FAILED with the reason. */
    void onFailure(String itemId, Exception error) {
        tx.executeWithoutResult(s -> {
            WorkItem item = items.findById(itemId).orElseThrow();
            Instant now = Instant.now(clock);
            String message = truncate(error.getClass().getSimpleName() + ": " + error.getMessage());
            boolean permanent = item.getAttempts() >= item.getMaxAttempts();
            metrics.workflowFailure(item.getType().name(), permanent);
            if (!permanent) {
                long exp = props.worker().baseBackoffMs() * (1L << Math.min(item.getAttempts() - 1, 20));
                long delay = Math.min(props.worker().maxBackoffMs(), exp);
                item.scheduleRetry(message, now.plusMillis(delay), now);
                log.warn("work item failed attempt={}/{} retryInMs={} error={}",
                        item.getAttempts(), item.getMaxAttempts(), delay, message);
                return;
            }
            item.markFailed(message, now);
            log.error("work item failed permanently attempts={} error={}", item.getAttempts(), message);
            if (item.getType() == WorkType.BACKUP) {
                backups.findById(item.getPayload()).ifPresent(b -> b.fail(now));
            } else {
                instances.findById(item.getInstanceId()).ifPresent(i -> {
                    if (i.getState().isInProgress()) {
                        i.fail(item.getType() + " failed after " + item.getAttempts() + " attempts: " + message, now);
                    }
                });
            }
        });
    }

    private Instance load(WorkItem item) {
        return tx.execute(s -> instances.findById(item.getInstanceId()).orElseThrow());
    }

    private void transition(String instanceId, InstanceState next) {
        tx.executeWithoutResult(s ->
                instances.findById(instanceId).orElseThrow().transitionTo(next, Instant.now(clock)));
    }

    private void skip(WorkItem item, Instance inst) {
        log.info("work item superseded type={} instanceState={}; marking done", item.getType(), inst.getState());
        tx.executeWithoutResult(s -> done(item.getId(), Instant.now(clock)));
    }

    private void done(String itemId, Instant now) {
        items.findById(itemId).orElseThrow().markDone(now);
    }

    private static String truncate(String s) {
        return s.length() > 1900 ? s.substring(0, 1900) : s;
    }
}
