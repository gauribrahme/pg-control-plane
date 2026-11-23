package com.gauribrahme.pgcp.provisioner;

import com.gauribrahme.pgcp.config.PgcpProperties;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default adapter: keeps "databases" in a map, simulates latency, and lets tests inject failures
 * (fail the next N calls of an operation, or always) and drift (mutate or remove actual state directly).
 */
@Component
@Profile("!k8s")
public class InMemoryProvisioner implements DatabaseProvisioner {
    private static final Logger log = LoggerFactory.getLogger(InMemoryProvisioner.class);

    public enum Op { CREATE, RESIZE, BACKUP, DELETE }

    private final Map<String, ActualState> databases = new ConcurrentHashMap<>();
    private final Map<String, String> backups = new ConcurrentHashMap<>();
    private final Map<Op, AtomicInteger> failuresRemaining = new ConcurrentHashMap<>();
    private final Set<Op> alwaysFail = ConcurrentHashMap.newKeySet();
    private final Map<Op, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final long delayMs;

    public InMemoryProvisioner(PgcpProperties props) {
        this.delayMs = props.inMemory().delayMs();
    }

    @Override
    public ProvisionResult create(ProvisionSpec spec) {
        begin(Op.CREATE);
        // computeIfAbsent makes a repeated create a no-op: safe to re-run a half-done provisioning.
        ActualState state = databases.computeIfAbsent(key(spec.tenantId(), spec.instanceId()),
                k -> new ActualState(spec.plan(), spec.storageGb(), true, endpoint(spec.tenantId(), spec.instanceId())));
        log.info("in-memory create done endpoint={}", state.endpoint());
        return new ProvisionResult(state.endpoint());
    }

    @Override
    public Optional<ActualState> describe(String tenantId, String instanceId) {
        return Optional.ofNullable(databases.get(key(tenantId, instanceId)));
    }

    @Override
    public void resize(ProvisionSpec spec) {
        begin(Op.RESIZE);
        String k = key(spec.tenantId(), spec.instanceId());
        if (!databases.containsKey(k)) {
            throw new ProvisioningException("cannot resize: instance does not exist " + spec.instanceId());
        }
        databases.computeIfPresent(k, (key, old) ->
                new ActualState(spec.plan(), Math.max(old.storageGb(), spec.storageGb()), true, old.endpoint()));
    }

    @Override
    public String backup(ProvisionSpec spec, String backupId) {
        begin(Op.BACKUP);
        if (!databases.containsKey(key(spec.tenantId(), spec.instanceId()))) {
            throw new ProvisioningException("cannot back up: instance does not exist " + spec.instanceId());
        }
        return backups.computeIfAbsent(backupId, id -> "memory://backups/" + spec.instanceId() + "/" + id);
    }

    @Override
    public void delete(String tenantId, String instanceId) {
        begin(Op.DELETE);
        databases.remove(key(tenantId, instanceId));
    }

    // ---- test hooks -------------------------------------------------------------------------------

    public void failNext(Op op, int times) { failuresRemaining.put(op, new AtomicInteger(times)); }
    public void failAlways(Op op) { alwaysFail.add(op); }
    public int callCount(Op op) { return calls.computeIfAbsent(op, o -> new AtomicInteger()).get(); }
    public void putActual(String tenantId, String instanceId, ActualState s) { databases.put(key(tenantId, instanceId), s); }
    public void removeActual(String tenantId, String instanceId) { databases.remove(key(tenantId, instanceId)); }
    public int size() { return databases.size(); }

    public void reset() {
        databases.clear();
        backups.clear();
        failuresRemaining.clear();
        alwaysFail.clear();
        calls.clear();
    }

    private void begin(Op op) {
        calls.computeIfAbsent(op, o -> new AtomicInteger()).incrementAndGet();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (alwaysFail.contains(op)) {
            throw new ProvisioningException("injected permanent failure for " + op);
        }
        AtomicInteger left = failuresRemaining.get(op);
        if (left != null && left.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ProvisioningException("injected transient failure for " + op);
        }
    }

    private static String key(String tenantId, String instanceId) { return tenantId + "/" + instanceId; }

    private static String endpoint(String tenantId, String instanceId) {
        return "pg-" + instanceId.substring(0, 8) + "." + tenantId + ".db.local:5432";
    }
}
