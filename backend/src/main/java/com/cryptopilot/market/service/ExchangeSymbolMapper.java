package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.client.ExchangeSymbol;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a symbol of the exchange's information into an {@link ExchangeListing}, or says it is not one the
 * system can list. A pure function: no database, no clock, no state, so NSF-01 (T-019) can apply it to a
 * whole exchange information response and decide what to write afterwards.
 *
 * <h2>What makes a symbol unusable</h2>
 *
 * <ul>
 *   <li><b>A futures contract that is not perpetual.</b> The system covers USDⓈ-M perpetual futures only
 *       (the SRS scope: "USDⓈ-M perpetual futures"); a quarterly contract shares the base and quote of the perpetual one and would otherwise
 *       be mistaken for it. A futures symbol with no contract type is treated the same way.
 *   <li><b>Missing or unusable filters.</b> A plan cannot be sized without the tick, the step and the
 *       minimum value (TECHNICAL_DESIGN 7.5). A missing filter, a zero one — which the exchange uses to
 *       mean "not enforced" — or one with more decimals than its column holds all make the symbol
 *       unlistable, because each would make the system enforce a different rule from the exchange's.
 * </ul>
 *
 * <p>An unknown trading status is not a reason to drop a symbol: anything but {@code TRADING} maps to
 * {@code trading = false}, which is exactly what NSF-01 needs to flag the pair. Dropping it instead would
 * make a pair that stopped trading silently vanish from the synchronisation.
 *
 * <p>Precision is carried unchanged: the filters are the exchange's {@code BigDecimal}s, parsed from its
 * strings by the client and never through a {@code double} (TECHNICAL_DESIGN 5.4).
 *
 * <p>Rule: NSF-01; BR-07, BR-09; TECHNICAL_DESIGN 5.4, 7.1.2 and 7.5.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 (the translation at
 * the edge of an anticorruption layer, from the other system's model into this one's).
 */
public final class ExchangeSymbolMapper {

    /** The only futures contract type the system lists. */
    static final String PERPETUAL = "PERPETUAL";

    private ExchangeSymbolMapper() {}

    /**
     * The listing this symbol is on this market, or empty when the system cannot list it (a non-perpetual
     * futures contract, or filters a plan cannot be sized with).
     */
    public static Optional<ExchangeListing> toListing(MarketType market, ExchangeSymbol symbol) {
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (market == MarketType.FUTURES && !PERPETUAL.equals(symbol.contractType())) {
            return Optional.empty();
        }
        return filtersOf(symbol)
                .map(filters -> new ExchangeListing(
                        market, symbol.symbol(), symbol.baseAsset(), symbol.quoteAsset(), symbol.isTrading(), filters));
    }

    private static Optional<PairFilters> filtersOf(ExchangeSymbol symbol) {
        if (symbol.tickSize() == null || symbol.stepSize() == null || symbol.minNotional() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PairFilters(symbol.tickSize(), symbol.stepSize(), symbol.minNotional()));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }
}
