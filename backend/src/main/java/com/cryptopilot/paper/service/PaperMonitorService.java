package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.response.AdminPaperOverviewResponse;

/**
 * The demo exchange at a glance for the administrator (TR-30).
 *
 * <p>Rule: TR-30; plan section 9.
 */
public interface PaperMonitorService {

    /** Accounts, working orders, open positions, the last 24 hours, the engine's book and the latest liquidations. */
    AdminPaperOverviewResponse overview();
}
