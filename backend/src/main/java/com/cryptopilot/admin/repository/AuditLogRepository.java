package com.cryptopilot.admin.repository;

import com.cryptopilot.admin.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The gateway to the audit trail. Append only: it declares {@code save} and nothing that could change or remove a
 * row. The read side of SCR-44 (UC-52) adds its queries with the task that builds the screen.
 *
 * <p>Rule: BR-57, NSF-18.
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    /** Writes an entry. Not transactional here; the unit of work is the audited command's. */
    AuditLog save(AuditLog entry);
}
