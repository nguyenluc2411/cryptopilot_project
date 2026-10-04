package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.entity.AuditLog;
import com.cryptopilot.admin.repository.AuditLogRepository;
import com.cryptopilot.admin.service.AuditService;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the audit trail in the transaction of the command it records (NSF-18).
 *
 * <p>The write is {@link Propagation#MANDATORY}: it joins the caller's transaction and refuses to run without one. The
 * command and its entry are therefore one unit of work — both commit or neither does — so the log never shows an
 * action that was rolled back, and an action never commits without its entry. A separate transaction, an event handled
 * after commit or an asynchronous writer would each break one of those two guarantees, which is why none is used here.
 * The entity is flushed with the command, so an insert that fails rolls the command back with it.
 *
 * <p>The client address is read from the current HTTP request, as the servlet container reports it; a scheduled job
 * has no request and records none.
 *
 * <p>Rule: BR-57, NSF-18; TECHNICAL_DESIGN section 10 (the audit call is inside the command transaction).
 *
 * <p>Reference: Gray, J. &amp; Reuter, A. (1993). <i>Transaction Processing: Concepts and Techniques</i>. Morgan
 * Kaufmann, ch. 1 and 4 (atomicity). Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley, "Unit of Work". Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 11 ("Audit
 * logging").
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final int IP_ADDRESS_LENGTH = 45;

    private final AuditLogRepository entries;
    private final ObjectMapper json;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void audit(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entries.save(AuditLog.record(
                entry.actorId(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                toJson(entry.oldValue()),
                toJson(entry.newValue()),
                clientAddress()));
    }

    private String toJson(Map<String, Object> values) {
        // Through the tree, so records, beans and arrays inside the values are redacted too.
        return values == null ? null : json.writeValueAsString(AuditValueRedactor.redact(json.valueToTree(values)));
    }

    private static String clientAddress() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) {
            return null;
        }
        String address = request.getRequest().getRemoteAddr();
        return address == null || address.length() <= IP_ADDRESS_LENGTH
                ? address
                : address.substring(0, IP_ADDRESS_LENGTH);
    }
}
