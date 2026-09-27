package com.cryptopilot.billing.service;

import com.cryptopilot.billing.EntitlementApi;

/**
 * The entitlement checks of UC-53 inside the {@code billing} module; other modules inject {@link EntitlementApi}.
 * Implemented by {@link com.cryptopilot.billing.service.impl.EntitlementServiceImpl}.
 *
 * <p>Rule: BR-62; SRS v1.1 UC-53; D-48, D-54.
 */
public interface EntitlementService extends EntitlementApi {}
