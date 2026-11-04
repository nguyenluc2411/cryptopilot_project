package com.cryptopilot.admin.model.enums;

/**
 * The state of one external service on the health panel of SCR-44.
 *
 * <p>Rule: UC-52 ("views the status of the external APIs and of data ingestion").
 */
public enum ServiceStatus {
    /** Answered, and no failure in the recent window. */
    UP,
    /** Failed in the recent window but answered again after that. */
    DEGRADED,
    /** Failed in the recent window and has not answered since; or Binance has banned this server. */
    DOWN,
    /** Not called since the application started, and no recent failure on record. */
    UNKNOWN
}
