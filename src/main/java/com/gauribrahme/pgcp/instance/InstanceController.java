package com.gauribrahme.pgcp.instance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API. The tenant comes from X-Tenant-Id for demo purposes; a real service derives it from the
 * authenticated principal (token claim) so a caller cannot choose someone else's tenant.
 */
@RestController
@RequestMapping("/api/v1/instances")
public class InstanceController {
    static final String TENANT_REGEX = "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$";

    private final InstanceService service;

    public InstanceController(InstanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InstanceResponse> create(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String idempotencyKey,
            @Valid @RequestBody CreateInstanceRequest request) {
        Instance created = service.create(tenantId, idempotencyKey, request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/instances/" + created.getId()))
                .body(InstanceResponse.from(created));
    }

    @GetMapping("/{id}")
    public InstanceResponse get(@RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId,
                                @PathVariable String id) {
        return InstanceResponse.from(service.get(tenantId, id));
    }

    @GetMapping
    public PageResponse<InstanceResponse> list(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId,
            @RequestParam(required = false) InstanceState state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(service.list(tenantId, state, page, size), InstanceResponse::from);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<InstanceResponse> update(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId,
            @PathVariable String id, @Valid @RequestBody UpdateInstanceRequest request) {
        return ResponseEntity.accepted().body(InstanceResponse.from(service.update(tenantId, id, request)));
    }

    @PostMapping("/{id}/backups")
    public ResponseEntity<BackupResponse> backup(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId, @PathVariable String id) {
        Backup b = service.requestBackup(tenantId, id);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/instances/" + id + "/backups"))
                .body(BackupResponse.from(b));
    }

    @GetMapping("/{id}/backups")
    public List<BackupResponse> backups(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId, @PathVariable String id) {
        return service.listBackups(tenantId, id).stream().map(BackupResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<InstanceResponse> delete(
            @RequestHeader("X-Tenant-Id") @Pattern(regexp = TENANT_REGEX) String tenantId, @PathVariable String id) {
        return ResponseEntity.accepted().body(InstanceResponse.from(service.delete(tenantId, id)));
    }
}
