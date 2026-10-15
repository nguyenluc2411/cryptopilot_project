package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.watchlist.calculator.FundingRateEvaluator;
import com.cryptopilot.watchlist.calculator.IndicatorCondition;
import com.cryptopilot.watchlist.calculator.TriggerPolicy;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import com.cryptopilot.watchlist.service.FundingRateAlertService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates the ACTIVE FUNDING_RATE alerts on the current predicted funding rate of their pair, read from the mark
 * price stream's cache ({@link MarketApi#currentFundingRate}); a pair without a current rate is skipped and its
 * previous rate forgotten. The level is entered in percent and compared as a fraction ({@link FundingRateEvaluator});
 * the conditions are those of every alert (D-88), and the conditional update decides the trigger with the 1h bar of
 * A-40 for ONCE_PER_BAR (D-89).
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.2, 3.4.4; A-40; D-88, D-89; Q-30.
 */
@Service
public class FundingRateAlertServiceImpl implements FundingRateAlertService {

    private static final Logger log = LoggerFactory.getLogger(FundingRateAlertServiceImpl.class);

    private final AlertTriggerService triggers;
    private final MarketApi market;
    private final Clock clock;
    private final FundingRateEvaluator rates;

    public FundingRateAlertServiceImpl(
            AlertTriggerService triggers, MarketApi market, Clock clock, AlertEngineProperties properties) {
        this.triggers = triggers;
        this.market = market;
        this.clock = clock;
        this.rates = new FundingRateEvaluator(properties.staleness());
    }

    @Override
    public int evaluate() {
        Map<UUID, List<IndicatorAlert>> byPair =
                triggers.activeFundingRateAlerts().stream().collect(Collectors.groupingBy(IndicatorAlert::pairId));
        rates.retain(byPair.keySet());
        if (byPair.isEmpty()) {
            return 0;
        }
        Map<UUID, String> symbols = market.pairListings(byPair.keySet()).stream()
                .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant bar = TriggerPolicy.barOpenTime(now);
        int recorded = 0;
        for (Map.Entry<UUID, List<IndicatorAlert>> pair : byPair.entrySet()) {
            String symbol = symbols.get(pair.getKey());
            Optional<BigDecimal> rate = symbol == null ? Optional.empty() : market.currentFundingRate(symbol);
            if (rate.isEmpty()) {
                rates.forget(pair.getKey());
                continue;
            }
            IndicatorReading reading = rates.read(pair.getKey(), rate.get(), now);
            for (IndicatorAlert alert : pair.getValue()) {
                if (IndicatorCondition.met(alert.condition(), FundingRateEvaluator.level(alert.threshold()), reading)
                        && trigger(new IndicatorAlertHit(
                                alert, symbol, FundingRateEvaluator.percent(rate.get()), bar, now))) {
                    recorded++;
                }
            }
        }
        return recorded;
    }

    private boolean trigger(IndicatorAlertHit hit) {
        try {
            return triggers.tryTriggerFundingRate(hit);
        } catch (RuntimeException failure) {
            log.warn("NSF-06 alert {} trigger failed: {}", hit.alert().alertId(), failure.toString());
            return false;
        }
    }
}
