package com.cryptopilot.admin.service;

import com.cryptopilot.admin.AdminApi;

/**
 * The audit trail writer inside the {@code admin} module; other modules inject {@link AdminApi}. Implemented by
 * {@link com.cryptopilot.admin.service.impl.AuditServiceImpl}.
 *
 * <p>Rule: BR-57, NSF-18; D-48.
 */
public interface AuditService extends AdminApi {}
