package com.cryptopilot.billing.dto;

import com.cryptopilot.billing.model.enums.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What buying a package would do if paid now (SCR-30). The subscription is worked out again from the one held when the
 * order is paid; this is only what the Trader is shown.
 *
 * @param amount what the order charges: the package's price (no credit, D-146)
 * @param carriedValueVnd the unused value of the subscription held that moves to the new one; 0 for NEW
 * @param bonusDays the days of the new tier that value buys; only an upgrade has them
 * @param startAt when the new subscription would start: now
 * @param endAt when it would end
 */
public record QuoteResponse(
        String packageCode,
        OrderType orderType,
        BigDecimal amount,
        String currency,
        BigDecimal carriedValueVnd,
        int bonusDays,
        Instant startAt,
        Instant endAt) {}
