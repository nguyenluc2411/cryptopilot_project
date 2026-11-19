package com.cryptopilot.market.model.enums;

/**
 * Why trading on a market was closed (D-158): its exchange data was lost, or an administrator held it closed. The
 * values of {@code market_incident.incident_kind}.
 *
 * <p>Rule: NSF-03; D-158.
 */
public enum IncidentKind {

    /** The exchange data was lost. */
    OUTAGE,

    /** An administrator closed the market on purpose. */
    MAINTENANCE
}
