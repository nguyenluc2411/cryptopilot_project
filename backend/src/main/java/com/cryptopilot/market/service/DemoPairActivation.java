package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.repository.CryptoPairRepository;
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
 * <p>This does what an administrator would do on SCR-37 (BR-07) and exists only because that screen does not
 * yet; the caller decides whether it runs, and it runs nowhere but in {@code dev}.
 *
 * <p>Rule: BR-07; NSF-01; Q-16.
 */
@Service
public class DemoPairActivation {

    private final CryptoPairRepository pairs;

    public DemoPairActivation(CryptoPairRepository pairs) {
        this.pairs = pairs;
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
            if (pair.exchangeStatus(market) == ExchangeStatus.TRADING && !pair.isEnabledOn(market)) {
                pair.enable(market);
                pairs.save(pair);
                activated.add(symbol);
            }
        }
        return activated;
    }
}
