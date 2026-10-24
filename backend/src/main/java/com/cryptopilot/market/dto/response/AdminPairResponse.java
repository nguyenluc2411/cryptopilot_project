package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.market.model.enums.PairStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One pair as SCR-37 shows it to an administrator: the switches, what the exchange says about each market (NSF-01
 * flags a market that stopped trading) and the filters it sent.
 *
 * <p>Rule: UC-45, UC-46; BR-07; NSF-01; Q-15.
 *
 * @param pairId the pair
 * @param symbol e.g. {@code BTCUSDT}
 * @param baseAsset the base coin's symbol
 * @param quoteAsset the quote coin's symbol
 * @param status ACTIVE or INACTIVE
 * @param spotEnabled the Spot switch
 * @param futuresEnabled the futures switch
 * @param maxLeverage the futures leverage ceiling, or {@code null}
 * @param displayOrder the place in lists, lowest first
 * @param spot the Spot market as the exchange lists it
 * @param futures the futures market as the exchange lists it
 * @param bracketCount the futures leverage brackets stored (SCR-38); a futures plan needs at least one
 * @param lastSyncedAt when NSF-01 last reached the pair, or {@code null}
 * @param updatedAt when the row last changed
 */
public record AdminPairResponse(
        UUID pairId,
        String symbol,
        String baseAsset,
        String quoteAsset,
        PairStatus status,
        boolean spotEnabled,
        boolean futuresEnabled,
        Integer maxLeverage,
        int displayOrder,
        ExchangeMarket spot,
        ExchangeMarket futures,
        int bracketCount,
        Instant lastSyncedAt,
        Instant updatedAt) {

    /**
     * One market of the pair on the exchange.
     *
     * @param exchangeStatus TRADING, NOT_TRADING or DELISTED, or {@code null} when never listed there
     * @param exchangeStatusRaw Binance's own status text, or {@code null}
     * @param tickSize the price increment, or {@code null} before the first synchronisation
     * @param stepSize the quantity increment, or {@code null}
     * @param minNotional the smallest order value, or {@code null}
     */
    public record ExchangeMarket(
            ExchangeStatus exchangeStatus,
            String exchangeStatusRaw,
            BigDecimal tickSize,
            BigDecimal stepSize,
            BigDecimal minNotional) {}
}
