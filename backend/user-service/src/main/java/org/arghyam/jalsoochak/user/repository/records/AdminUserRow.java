package org.arghyam.jalsoochak.user.repository.records;

import org.arghyam.jalsoochak.user.enums.AdminUserStatus;

import java.time.LocalDateTime;

public record AdminUserRow(
        Long id,
        String uuid,
        String email,
        String phoneNumber,
        Integer tenantId,
        Integer adminLevel,       // FK to user_type_master_table — kept for write-side use (createUser)
        String userTypeCName,     // c_name from user_type_master_table — use this for role resolution
        AdminUserStatus status,
        Integer createdBy,
        LocalDateTime createdAt
) {}
