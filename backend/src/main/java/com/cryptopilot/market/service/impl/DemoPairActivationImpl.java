package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.DemoPairActivation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activates the demo pairs on one market, for the {@code dev} profile only (Q-16).
 *
 * <p>A seed symbol is enabled on a market when the pair exists — NSF-01 creates it — and the exchange lists it
 * there as {@code TRADING}; a pair already enabled there, missing, or not trading is left alone. So a repeated
 * call changes nothing, and a pair the exchange does not trade on a market is never offered on it.
 *
 * <p>This does what an administrator does on SCR-37 (BR-07), so a fresh development database has pairs to show without
 * an administrator; the caller decides whether it runs, and it runs nowhere but in {@code dev}. A demo futures market
 * without a leverage ceiling, one it enables or one an earlier version enabled without it, gets the ceiling SCR-37
 * would ask for: {@link #DEMO_MAX_LEVERAGE}, or the first bracket's maximum when that is lower, so the pair can be
 * edited on SCR-37 without entering one first.
 *
 * <p>Rule: BR-07; NSF-01; Q-16.
 */
@Service
public class DemoPairActivationImpl implements DemoPairActivation {

    /** The leverage ceiling of a demo futures market, as a cautious administrator would set it. */
    static final int DEMO_MAX_LEVERAGE = 20;

    private final CryptoPairRepository pairs;
    private final LeverageBracketRepository brackets;

    public DemoPairActivationImpl(CryptoPairRepository pairs, LeverageBracketRepository brackets) {
        this.pairs = pairs;
        this.brackets = brackets;
    }

    /**
     * Enables these symbols on this market where the exchange trades them.
     *
     * @return the symbols newly enabled, in the order given
     */
    @Transactional
    public List<String> activate(MarketType market, List<String> symbols) {
        List<String> activated = new ArrayList<>();
        for (String symbol : symbols) {
            Optional<CryptoPair> found = pairs.findBySymbol(symbol);
            if (found.isEmpty()) {
                continue;
            }
            CryptoPair pair = found.get();
            boolean changed = false;
            if (pair.exchangeStatus(market) == ExchangeStatus.TRADING && !pair.isEnabledOn(market)) {
                pair.enable(market);
                activated.add(symbol);
                changed = true;
            }
            if (market == MarketType.FUTURES && pair.isFuturesEnabled() && pair.getMaxLeverage() == null) {
                pair.limitLeverage(brackets.findByPair(pair.getId()).stream()
                        .findFirst()
                        .map(first -> Math.min(first.maxLeverage(), DEMO_MAX_LEVERAGE))
                        .orElse(DEMO_MAX_LEVERAGE));
                changed = true;
            }
            if (changed) {
                pairs.save(pair);
            }
        }
        return activated;
    }
}
