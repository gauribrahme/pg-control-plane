package com.gauribrahme.pgcp.instance;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupRepository extends JpaRepository<Backup, String> {
    List<Backup> findByInstanceIdOrderByCreatedAtDesc(String instanceId);
}
