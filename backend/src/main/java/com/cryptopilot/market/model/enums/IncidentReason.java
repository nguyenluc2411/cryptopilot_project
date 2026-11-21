package com.cryptopilot.market.model.enums;

/**
 * What closed trading on a market (D-159), as the Traders' banner names it. The values of
 * {@code market_incident.reason}.
 *
 * <p>Rule: NSF-03; D-159.
 */
public enum IncidentReason {

    /** The market's stream sent nothing for longer than the pause threshold. */
    STREAM_LOST,

    /** The stream is silent and the REST API does not answer either. */
    REST_UNAVAILABLE,

    /** The exchange refuses the calls for their weight (429). */
    RATE_LIMITED,

    /** The exchange banned the server's address (418) until a known instant. */
    IP_BANNED,

    /** An administrator closed the market on purpose. */
    MAINTENANCE
}
