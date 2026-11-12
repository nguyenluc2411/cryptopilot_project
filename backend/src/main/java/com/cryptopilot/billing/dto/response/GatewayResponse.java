package com.cryptopilot.billing.dto.response;

import com.cryptopilot.billing.model.enums.GatewayCode;

/**
 * A payment gateway and whether a new order may be paid through it now (D-147).
 *
 * @param enabled false when an administrator switched it off or this deployment has no credentials for it
 */
public record GatewayResponse(GatewayCode code, boolean enabled) {}
