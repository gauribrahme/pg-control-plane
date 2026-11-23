package com.gauribrahme.pgcp.instance;

import com.gauribrahme.pgcp.common.ApiException;
import com.gauribrahme.pgcp.common.IdempotencyRecord;
import com.gauribrahme.pgcp.common.IdempotencyRepository;
import com.gauribrahme.pgcp.common.RequestHash;
import com.gauribrahme.pgcp.config.PgcpProperties;
import com.gauribrahme.pgcp.tenant.Tenant;
import com.gauribrahme.pgcp.tenant.TenantService;
import com.gauribrahme.pgcp.workflow.WorkItem;
import com.gauribrahme.pgcp.workflow.WorkItemRepository;
import com.gauribrahme.pgcp.workflow.WorkType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API-side use cases. Every mutation writes the instance row and its work_item in ONE transaction (outbox),
 * then returns; the actual provisioning happens later in the workflow worker.
 */
@Service
public class InstanceService {
    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final InstanceRepository instances;
    private final BackupRepository backups;
    private final WorkItemRepository workItems;
    private final IdempotencyRepository idempotency;
    private final TenantService tenants;
    private final PgcpProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public InstanceService(InstanceRepository instances, BackupRepository backups, WorkItemRepository workItems,
                           IdempotencyRepository idempotency, TenantService tenants, PgcpProperties props,
                           Clock clock, TransactionTemplate tx) {
        this.instances = instances;
        this.backups = backups;
        this.workItems = workItems;
        this.idempotency = idempotency;
        this.tenants = tenants;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    public Instance create(String tenantId, String idempotencyKey, CreateInstanceRequest req) {
        Plan plan = req.plan();
        int storage = req.storageGb() != null ? req.storageGb() : plan.defaultStorageGb();
        String pgVersion = req.pgVersion() != null ? req.pgVersion() : "16";
        if (storage > plan.maxStorageGb()) {
            throw ApiException.unprocessable("storage-exceeds-plan",
                    "Plan " + plan.json() + " allows at most " + plan.maxStorageGb() + " GB");
        }
        String hash = RequestHash.of(req.name(), plan, storage, pgVersion);
        try {
            return tx.execute(s -> doCreate(tenantId, idempotencyKey, hash, req.name(), plan, storage, pgVersion));
        } catch (DataIntegrityViolationException raced) {
            // Lost a race on the idempotency key (or the first-use tenant row): the winner has committed, so a
            // second pass takes the replay path and returns its instance.
            return tx.execute(s -> doCreate(tenantId, idempotencyKey, hash, req.name(), plan, storage, pgVersion));
        }
    }

    private Instance doCreate(String tenantId, String key, String hash, String name, Plan plan, int storage,
                              String pgVersion) {
        var existing = idempotency.findByTenantIdAndIdemKey(tenantId, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw ApiException.conflict("idempotency-key-reuse",
                        "Idempotency-Key was already used with a different request body");
            }
            return instances.findById(existing.get().getInstanceId()).orElseThrow();
        }
        Tenant tenant = tenants.lockOrCreate(tenantId);
        if (instances.countByTenantIdAndStateNot(tenantId, InstanceState.DELETED) >= tenant.getMaxInstances()) {
            throw ApiException.unprocessable("quota-exceeded",
                    "Tenant quota of " + tenant.getMaxInstances() + " instances reached");
        }
        if (instances.existsByTenantIdAndNameAndStateNot(tenantId, name, InstanceState.DELETED)) {
            throw ApiException.conflict("name-in-use", "An instance named " + name + " already exists");
        }
        Instant now = Instant.now(clock);
        Instance instance = instances.save(new Instance(tenantId, name, plan, storage, pgVersion, now));
        workItems.save(newWork(instance.getId(), WorkType.CREATE, null, now));
        idempotency.saveAndFlush(new IdempotencyRecord(tenantId, key, hash, instance.getId(), now));
        try (var ignored = MDC.putCloseable("instanceId", instance.getId())) {
            log.info("instance requested name={} plan={} storageGb={}", name, plan, storage);
        }
        return instance;
    }

    public Instance get(String tenantId, String id) {
        return tx.execute(s -> find(tenantId, id));
    }

    public Page<Instance> list(String tenantId, InstanceState state, int page, int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return state == null
                ? instances.findByTenantIdAndStateNot(tenantId, InstanceState.DELETED, pr)
                : instances.findByTenantIdAndState(tenantId, state, pr);
    }

    public Instance update(String tenantId, String id, UpdateInstanceRequest req) {
        if (req.plan() == null && req.storageGb() == null) {
            throw ApiException.unprocessable("empty-update", "Provide plan and/or storageGb");
        }
        return tx.execute(s -> {
            Instance i = find(tenantId, id);
            Plan plan = req.plan() != null ? req.plan() : i.getPlan();
            int storage = req.storageGb() != null ? req.storageGb() : i.getStorageGb();
            if (storage < i.getStorageGb()) {
                throw ApiException.unprocessable("storage-shrink",
                        "Storage can only grow (currently " + i.getStorageGb() + " GB)");
            }
            if (storage > plan.maxStorageGb()) {
                throw ApiException.unprocessable("storage-exceeds-plan",
                        "Plan " + plan.json() + " allows at most " + plan.maxStorageGb() + " GB");
            }
            Instant now = Instant.now(clock);
            i.transitionTo(InstanceState.UPDATING, now); // throws 409 unless AVAILABLE
            i.setDesired(plan, storage, now);
            workItems.save(newWork(i.getId(), WorkType.RESIZE, null, now));
            return i;
        });
    }

    public Backup requestBackup(String tenantId, String id) {
        return tx.execute(s -> {
            Instance i = find(tenantId, id);
            if (i.getState() != InstanceState.AVAILABLE) {
                throw ApiException.conflict("illegal-state",
                        "Backups require an AVAILABLE instance, not " + i.getState());
            }
            Instant now = Instant.now(clock);
            Backup b = backups.save(new Backup(i.getId(), tenantId, now));
            workItems.save(newWork(i.getId(), WorkType.BACKUP, b.getId(), now));
            return b;
        });
    }

    public List<Backup> listBackups(String tenantId, String id) {
        find(tenantId, id);
        return backups.findByInstanceIdOrderByCreatedAtDesc(id);
    }

    public Instance delete(String tenantId, String id) {
        return tx.execute(s -> {
            Instance i = find(tenantId, id);
            if (i.getState() == InstanceState.DELETING) {
                return i; // repeated DELETE is idempotent
            }
            Instant now = Instant.now(clock);
            i.transitionTo(InstanceState.DELETING, now);
            workItems.save(newWork(i.getId(), WorkType.DELETE, null, now));
            return i;
        });
    }

    private Instance find(String tenantId, String id) {
        return instances.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Instance", id));
    }

    private WorkItem newWork(String instanceId, WorkType type, String payload, Instant now) {
        return new WorkItem(instanceId, type, payload, props.worker().maxAttempts(), now);
    }
}
