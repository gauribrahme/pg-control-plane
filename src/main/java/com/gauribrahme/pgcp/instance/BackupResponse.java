package com.gauribrahme.pgcp.instance;

import java.time.Instant;

public record BackupResponse(String id, String instanceId, Backup.Status status, String location, Instant createdAt) {
    public static BackupResponse from(Backup b) {
        return new BackupResponse(b.getId(), b.getInstanceId(), b.getStatus(), b.getLocation(), b.getCreatedAt());
    }
}
