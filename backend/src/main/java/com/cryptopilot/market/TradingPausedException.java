package com.cryptopilot.market;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request refused because trading on its market is paused (D-158). The problem detail carries {@code market},
 * {@code state} and {@code since}, so the client can show the pause banner rather than an error of one pair.
 *
 * <p>Rule: NSF-03; D-158.
 */
public class TradingPausedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final MarketType market;
    private final MarketHealthState state;
    private final Instant since;

    public TradingPausedException(MarketType market, MarketHealthState state, Instant since) {
        super(ErrorCode.TRADING_PAUSED, "trading on " + market + " is paused (" + state + ")", market.name());
        this.market = market;
        this.state = state;
        this.since = since;
    }

    public MarketType market() {
        return market;
    }

    public MarketHealthState state() {
        return state;
    }

    /** When the market stopped being live; {@code null} when not known. */
    public Instant since() {
        return since;
    }

    @Override
    public Map<String, Object> problemProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("market", market.name());
        properties.put("state", state.name());
        if (since != null) {
            properties.put("since", since.toString());
        }
        return properties;
    }
}
