package com.nexum.skeleton;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeRecordRepository extends JpaRepository<ProbeRecord, UUID> {
}
