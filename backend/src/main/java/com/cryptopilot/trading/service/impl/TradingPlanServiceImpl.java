package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.trading.calculator.PlanValidityRule;
import com.cryptopilot.trading.calculator.SlippageCalculator;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.PlanInputs;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.matching.EntryFillRule;
import com.cryptopilot.trading.model.CalculatedPlan;
import com.cryptopilot.trading.model.EntryExpectation;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.PlanTerms;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskProfileLimits;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.TradingPlanService;
import com.cryptopilot.user.PlanDefaults;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.RiskProfileParameters;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading plan use cases. Each method is one transaction that loads what it needs from the other modules through
 * their APIs, lets the calculations and the {@link TradingPlan} aggregate decide, and saves. An activation takes the
 * D-63 lock on the Trader before it counts the ACTIVE plans, so the count and the activation it allows cannot
 * interleave with another one.
 *
 * <p>Rule: UC-16 to UC-20; BR-21 to BR-33, BR-62, BR-66, BR-68, BR-69, BR-70; CR-04; D-63, D-70, D-71, D-72, D-111,
 * D-112.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service
 * Layer".
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew: a
 * count followed by an insert needs a lock).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingPlanServiceImpl implements TradingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TradingPlanServiceImpl.class);

    /** How long an unfilled LIMIT or STOP plan stays ACTIVE when the Trader sets no expiry (SRS 3.5.1, D-71). */
    static final Duration LIMIT_EXPIRY = Duration.ofDays(7);

    /** The setting a plan's own entry deviation limit may not exceed (BR-69). */
    static final String ENTRY_DEVIATION_BLOCK_R = "ENTRY_DEVIATION_BLOCK_R";

    /** The simulated slippage of taker fills, read on every calculation and every fill (BR-36; D-111 E5). */
    static final String SLIPPAGE_BPS = "SLIPPAGE_BPS";

    /** Excludes no plan from the other open risk, and stands for "any pair" in the list: no row has this key. */
    private static final UUID NO_PLAN = new UUID(0L, 0L);

    private final TradingPlanRepository plans;
    private final PlanCalculationAssembler calculations;
    private final UserLock activePlanLock;
    private final MarketApi market;
    private final RiskProfileApi riskProfiles;
    private final EntitlementApi entitlements;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final SystemSettingApi settings;
    /** The Trader's style preset, implemented by the profile's owner; absent means no preset (D-53). */
    private final ObjectProvider<SetupStyleSource> styles;

    @Override
    @Transactional(readOnly = true)
    public PlanCalculationResponse calculate(UUID userId, CreateTradingPlanRequest request) {
        PlanTerms terms = terms(request);
        TradablePair pair = pair(userId, request.pairId(), terms.market());
        Optional<BigDecimal> last = lastPriceRead(pair, terms.entryType(), request.invalidationPrice(), false);
        CalculatedPlan calculated = calculate(userId, pair, terms, NO_PLAN, true, last);
        // The save's checks too, so the panel accepts only what create accepts.
        PlanDetails details = details(userId, request, calculated, clock.instant());
        requireValidAtSave(pair, calculated, details, false, last);
        TradingPlan.requireValidDetails(details);
        return PlanResponses.calculation(calculated);
    }

    @Override
    @Transactional
    public TradingPlanResponse create(UUID userId, CreateTradingPlanRequest request) {
        Instant now = clock.instant();
        boolean activate = Boolean.TRUE.equals(request.activate());
        if (activate) {
            activePlanLock.lock(userId);
        }
        PlanTerms terms = terms(request);
        TradablePair pair = pair(userId, request.pairId(), terms.market());
        Optional<BigDecimal> last = lastPriceRead(pair, terms.entryType(), request.invalidationPrice(), activate);
        CalculatedPlan calculated = calculate(userId, pair, terms, NO_PLAN, true, last);
        PlanDetails details = details(userId, request, calculated, now);
        requireValidAtSave(pair, calculated, details, activate, last);
        TradingPlan plan = TradingPlan.draft(userId, request.pairId(), calculated, details);
        if (activate) {
            requireRoomForAnActivePlan(userId);
            activate(plan, calculated, now, last);
        }
        return PlanResponses.detail(plans.save(plan));
    }

    @Override
    @Transactional
    public TradingPlanResponse update(UUID userId, UUID planId, UpdateTradingPlanRequest request) {
        Instant now = clock.instant();
        boolean activate = Boolean.TRUE.equals(request.activate());
        if (activate) {
            activePlanLock.lock(userId);
        }
        TradingPlan plan = owned(userId, planId);
        if (!plan.isEditable()) {
            // Before any calculation, so a plan that is not a draft answers 409 and not a validation error.
            throw new IllegalPlanStateException(plan.getId(), plan.getStatus(), "edit");
        }
        PlanTerms terms = terms(request);
        TradablePair pair = pair(userId, plan.getPairId(), terms.market());
        Optional<BigDecimal> last = lastPriceRead(pair, terms.entryType(), request.invalidationPrice(), activate);
        CalculatedPlan calculated = calculate(userId, pair, terms, plan.getId(), true, last);
        PlanDetails details = details(userId, request, calculated, now);
        requireValidAtSave(pair, calculated, details, activate, last);
        plan.updateDraft(calculated, details);
        if (activate) {
            requireRoomForAnActivePlan(userId);
            activate(plan, calculated, now, last);
        }
        return PlanResponses.detail(plans.save(plan));
    }

    @Override
    @Transactional
    public TradingPlanResponse activate(UUID userId, UUID planId) {
        Instant now = clock.instant();
        activePlanLock.lock(userId);
        TradingPlan plan = owned(userId, planId);
        if (!plan.getStatus().canTransitionTo(PlanStatus.ACTIVE)) {
            // Before the count, so activating twice is a state error and not a limit error.
            throw new IllegalPlanStateException(plan.getId(), plan.getStatus(), "move to " + PlanStatus.ACTIVE);
        }
        requireRoomForAnActivePlan(userId);
        TradablePair pair = pair(userId, plan.getPairId(), plan.getMarket());
        Optional<BigDecimal> last = lastPriceRead(pair, plan.getEntryType(), plan.getInvalidationPrice(), true);
        // Stored prices are checked against the current tick, not rounded to it (BR-30, BR-31).
        CalculatedPlan calculated = calculate(userId, pair, terms(plan), plan.getId(), false, last);
        // After the calculation and its entitlement check, which answers MSG29 before a missing price answers 503.
        requireValidAtActivation(plan, last);
        activate(plan, calculated, now, last);
        return PlanResponses.detail(plans.save(plan));
    }

    @Override
    @Transactional
    public TradingPlanResponse cancel(UUID userId, UUID planId) {
        // Row lock: a fill committing first is seen here as EXECUTED, so the loser gets MSG43, not a lost update.
        TradingPlan plan = plans.findForUpdate(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingPlan", planId));
        plan.cancel(clock.instant());
        events.publishEvent(new TradingPlanCancelled(plan.getId(), plan.getMarket(), plan.getPairId()));
        return PlanResponses.detail(plans.save(plan));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelForDisabledMarket(UUID pairId, String symbol, MarketType market, Instant at) {
        List<TradingPlan> active = plans.findActiveForUpdate(pairId, market);
        for (TradingPlan plan : active) {
            plan.cancel(at);
            plans.save(plan);
            events.publishEvent(new TradingPlanCancelled(plan.getId(), plan.getMarket(), plan.getPairId()));
            events.publishEvent(new TradingPlanWithdrawn(plan.getId(), plan.getUserId(), symbol, market, at));
        }
        if (!active.isEmpty()) {
            log.info(
                    "BR-59: {} ACTIVE plans of {} {} cancelled, the market was disabled",
                    active.size(),
                    symbol,
                    market);
        }
        return active.size();
    }

    @Override
    @Transactional(readOnly = true)
    public TradingPlanResponse get(UUID userId, UUID planId) {
        return PlanResponses.detail(owned(userId, planId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TradingPlanSummaryResponse> list(UUID userId, PlanListQuery query) {
        PageRequest request = Paging.of(query.page(), query.pageSize());
        Set<PlanStatus> statuses = query.tab() == null
                ? EnumSet.allOf(PlanStatus.class)
                : query.tab().statuses();
        Set<MarketType> markets = query.market() == null ? EnumSet.allOf(MarketType.class) : EnumSet.of(query.market());
        Page<TradingPlan> found = plans.search(
                userId,
                statuses,
                markets,
                query.pairId() == null,
                query.pairId() == null ? NO_PLAN : query.pairId(),
                TimeBounds.fromOrEarliest(query.from()),
                TimeBounds.toOrLatest(query.to()),
                request);
        return PageResponse.of(found, PlanResponses::summary);
    }

    /**
     * The pair of a plan on its market, after the Futures entitlement (MSG29), which answers before anything about the
     * pair or its prices.
     */
    private TradablePair pair(UUID userId, UUID pairId, MarketType marketType) {
        if (marketType == MarketType.FUTURES) {
            entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        }
        return market.tradablePair(pairId, marketType)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", pairId));
    }

    /**
     * The plan calculated with its defaults applied and the current data of the other modules: the pair's filters,
     * the last price for a MARKET entry, the risk profile, the other open risk, and the expected fees and slippage of
     * the entry ({@code last}, the request's one read of the last price, decides whether a LIMIT is expected to take
     * or to supply liquidity). With {@code roundPrices} the prices are rounded to the pair's tick, as for values just
     * entered (BR-30); without it they are passed as stored, so a price that is no longer a multiple of the current
     * tick is refused by the calculation on its field (MSG15) instead of being moved to a price the Trader never
     * entered.
     *
     * <p>Rule: BR-23, BR-29, BR-30, BR-31, BR-36; D-111 E1, E5; D-112 R6; D-116.
     */
    private CalculatedPlan calculate(
            UUID userId,
            TradablePair pair,
            PlanTerms terms,
            UUID excludedPlan,
            boolean roundPrices,
            Optional<BigDecimal> last) {
        PlanDefaults defaults = riskProfiles.planDefaultsOf(userId);
        BigDecimal capital = terms.capital() != null ? terms.capital() : defaults.defaultCapital();
        if (capital == null) {
            throw new FieldValidationException("no capital entered and no default capital", Map.of("capital", "MSG01"));
        }
        int slippageBps = settings.integer(SLIPPAGE_BPS);
        BigDecimal reference = terms.entryType() == EntryType.MARKET
                ? currentReferencePrice(pair).orElseThrow(() -> priceUnavailable(pair))
                : null;
        // A MARKET entry is sized on the price it is expected to pay, slippage included (D-112 R6).
        BigDecimal entryPrice = reference != null
                ? SlippageCalculator.takerFill(
                        reference,
                        SlippageCalculator.buysOnEntry(terms.direction()),
                        slippageBps,
                        pair.filters().tickSize())
                : enteredPrice(pair, terms, roundPrices);
        RiskInput input = new RiskInput(
                terms.market(),
                terms.direction(),
                entryPrice,
                roundPrices ? tick(pair, terms.stopLoss()) : terms.stopLoss(),
                roundPrices ? tick(pair, terms.takeProfit()) : terms.takeProfit(),
                capital,
                riskPercent(terms, defaults),
                terms.leverage() == null ? 1 : terms.leverage(),
                pair.filters(),
                limits(defaults.riskProfile()),
                plans.activeRiskExcluding(userId, excludedPlan).add(openPositionRisk(userId)));
        LiquidityRole role = EntryFillRule.expectedLiquidity(terms.entryType(), terms.direction(), entryPrice, last);
        BigDecimal slipsFrom =
                switch (terms.entryType()) {
                    case MARKET -> reference;
                    case STOP -> entryPrice;
                    case LIMIT -> role == LiquidityRole.TAKER ? last.orElseThrow() : null;
                };
        return calculations.calculate(
                pair, input, new EntryExpectation(terms.entryType(), role, slipsFrom, slippageBps));
    }

    /** LIMIT and STOP: the price entered (for STOP the stop price), rounded to the tick when {@code roundPrice}. */
    private static BigDecimal enteredPrice(TradablePair pair, PlanTerms terms, boolean roundPrice) {
        if (terms.entryPrice() == null) {
            throw new FieldValidationException(
                    "a " + terms.entryType() + " plan needs an entry price", Map.of("entryPrice", "MSG01"));
        }
        return roundPrice ? tick(pair, terms.entryPrice()) : terms.entryPrice();
    }

    /** The risk % entered, else the profile screen's default, else the risk per trade of the risk profile. */
    private static BigDecimal riskPercent(PlanTerms terms, PlanDefaults defaults) {
        if (terms.riskPercent() != null) {
            return terms.riskPercent();
        }
        return defaults.defaultRiskPercent() != null
                ? defaults.defaultRiskPercent()
                : defaults.riskProfile().riskPerTradePercent();
    }

    private static BigDecimal tick(TradablePair pair, BigDecimal price) {
        return pair.filters().roundPrice(price);
    }

    private static RiskProfileLimits limits(RiskProfileParameters profile) {
        return new RiskProfileLimits(
                profile.riskPerTradePercent(), profile.maxFuturesLeverage(), profile.maxTotalOpenRiskPercent());
    }

    /** {@code ACTIVE_PLAN_MAX} counts ACTIVE plans and open positions together (BR-62); MSG27 when it is reached. */
    private void requireRoomForAnActivePlan(UUID userId) {
        entitlements.requireWithinLimit(
                userId, Feature.ACTIVE_PLAN_MAX, plans.countActive(userId) + openPositions(userId));
    }

    /** Open simulated positions; none exist until the journal records them (T-042). */
    private static long openPositions(UUID userId) {
        return 0L;
    }

    /** The risk of the open simulated positions; none until the journal records them (T-042). */
    private static BigDecimal openPositionRisk(UUID userId) {
        return BigDecimal.ZERO;
    }

    private TradingPlan owned(UUID userId, UUID planId) {
        return plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingPlan", planId));
    }

    private static PlanTerms terms(PlanInputs request) {
        return new PlanTerms(
                request.market(),
                request.direction(),
                request.entryType(),
                request.entryPrice(),
                request.stopLoss(),
                request.takeProfit(),
                request.capital(),
                request.riskPercent(),
                request.leverage());
    }

    /** The stored plan's values, calculated again at activation (BR-31); a MARKET entry takes the price of now. */
    private static PlanTerms terms(TradingPlan plan) {
        return new PlanTerms(
                plan.getMarket(),
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getCapital(),
                plan.getRiskPercent(),
                plan.getLeverage());
    }

    /**
     * The request's details with their defaults: a LIMIT or STOP plan without an expiry expires after
     * {@link #LIMIT_EXPIRY} (a MARKET plan has none by default), and without an analysis timeframe the plan takes the
     * one of the Trader's style preset. A timeframe outside BR-08 is MSG15. The invalidation price is rounded to the
     * pair's tick like the other prices.
     *
     * <p>Rule: BR-08, BR-30, BR-70; SRS 3.5.1; D-71, D-111 (M1), D-112.
     */
    private PlanDetails details(UUID userId, PlanInputs request, CalculatedPlan calculated, Instant now) {
        Instant expiresAt = request.expiresAt();
        if (expiresAt == null && request.entryType() != EntryType.MARKET) {
            expiresAt = now.plus(LIMIT_EXPIRY);
        }
        return new PlanDetails(
                request.entryType(),
                expiresAt,
                request.note(),
                request.invalidationPrice() == null
                        ? null
                        : calculated.calculation().plan().filters().roundPrice(request.invalidationPrice()),
                request.maxEntryDeviationR(),
                analysisTimeframe(userId, request.analysisTimeframe()));
    }

    private AnalysisTimeframe analysisTimeframe(UUID userId, String requested) {
        if (requested == null) {
            SetupStyleSource source = styles.getIfAvailable();
            return AnalysisTimeframe.defaultFor(source == null ? Optional.empty() : source.styleOf(userId));
        }
        return AnalysisTimeframe.fromCode(requested)
                .orElseThrow(() -> new FieldValidationException(
                        "the analysis timeframe " + requested + " is not one of BR-08",
                        Map.of("analysisTimeframe", "MSG15")));
    }

    /**
     * BR-69: a plan's own deviation limit within the system's block value; BR-68: the invalidation price on the far
     * side of the current price (a MARKET plan carrying one is the aggregate's error); when the save also activates,
     * BR-33: a STOP not already triggered. {@code last} is the request's one read of the last price.
     */
    private void requireValidAtSave(
            TradablePair pair,
            CalculatedPlan calculated,
            PlanDetails details,
            boolean activating,
            Optional<BigDecimal> last) {
        List<InputViolation> violations = new ArrayList<>();
        PlanValidityRule.maxEntryDeviation(details.maxEntryDeviationR(), settings.decimal(ENTRY_DEVIATION_BLOCK_R))
                .ifPresent(violations::add);
        RiskInput plan = calculated.calculation().plan();
        violations.addAll(priceViolations(
                pair.pairId(),
                plan.market(),
                details.entryType(),
                plan.direction(),
                plan.entryPrice(),
                details.invalidationPrice(),
                activating,
                last));
        requireNone(violations);
    }

    /**
     * BR-68 and BR-33 again at activation (BR-31): the price may have moved since the save. {@code last} is the
     * request's one read of the last price.
     */
    private static void requireValidAtActivation(TradingPlan plan, Optional<BigDecimal> last) {
        requireNone(priceViolations(
                plan.getPairId(),
                plan.getMarket(),
                plan.getEntryType(),
                plan.getDirection(),
                plan.getEntryPrice(),
                plan.getInvalidationPrice(),
                true,
                last));
    }

    /**
     * The one read of the pair's current last price, never the mark price (D-112), that a calculation estimates a
     * LIMIT's liquidity role with, that a save or an activation checks against and that an activation decides an
     * immediate LIMIT fill with, so every step sees the same price. MARKET reads none here: it is calculated and fills
     * at its own read, see {@link #currentReferencePrice}. A STOP that is not being activated reads it only to check
     * an invalidation price. Empty when not read, or when the cache holds no current one.
     *
     * <p>Rule: BR-33, BR-36, BR-68; D-111 E1, E2/E3; D-112.
     */
    private Optional<BigDecimal> lastPriceRead(
            TradablePair pair, EntryType entryType, BigDecimal invalidation, boolean activating) {
        if (entryType == EntryType.MARKET || (entryType == EntryType.STOP && invalidation == null && !activating)) {
            return Optional.empty();
        }
        return market.currentLastPrice(pair.market(), pair.symbol());
    }

    /**
     * The invalidation price and, at activation, the stop price of a STOP against the request's one read of the last
     * price, see {@link #lastPriceRead}. Without a current last price these cannot be checked, so the save or
     * activation is refused (MSG43).
     */
    private static List<InputViolation> priceViolations(
            UUID pairId,
            MarketType marketType,
            EntryType entryType,
            Direction direction,
            BigDecimal entryPrice,
            BigDecimal invalidation,
            boolean activating,
            Optional<BigDecimal> lastRead) {
        boolean stopAtActivation = activating && entryType == EntryType.STOP;
        if (entryType == EntryType.MARKET || (invalidation == null && !stopAtActivation)) {
            return List.of();
        }
        BigDecimal last = lastRead.orElseThrow(() -> new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE,
                "no current last price for pair " + pairId + " on " + marketType
                        + " to check the invalidation or stop price"));
        List<InputViolation> violations = new ArrayList<>();
        if (stopAtActivation) {
            PlanValidityRule.stopAlreadyTriggered(direction, entryPrice, last).ifPresent(violations::add);
        }
        PlanValidityRule.invalidationPrice(entryType, direction, invalidation, last)
                .ifPresent(violations::add);
        return violations;
    }

    private static void requireNone(List<InputViolation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        violations.forEach(violation -> errors.putIfAbsent(violation.field(), violation.messageCode()));
        throw new FieldValidationException("the plan's validity values are rejected: " + violations, errors);
    }

    /**
     * Activates the plan and fills its entry at once when it is MARKET, or when it is LIMIT and the current last price
     * already reaches it (BR-33); otherwise it waits for the matching engine. A MARKET entry fills at the price the
     * activation was calculated with: the last price on both markets (D-111 E2/E3), moved by the slippage. A LIMIT
     * fills at the last price moved by the slippage, but never past its limit (D-112 note). A fill at activation took
     * liquidity, so it is TAKER (D-111 E1, E5).
     * A STOP waits: its trigger is the matching engine's (TP-B). The journal record of the fill is T-048. The setup
     * score of the moment is kept beside the plan, see {@link #recordSetupScore}. {@code last} is the request's one read
     * of the last price, see {@link #lastPriceRead}.
     */
    private void activate(TradingPlan plan, CalculatedPlan calculated, Instant now, Optional<BigDecimal> last) {
        plan.activate(calculated.calculation(), calculated.warnings(), now);
        recordSetupScore(plan, now);
        Optional<BigDecimal> fillPrice = plan.getEntryType() == EntryType.STOP
                ? Optional.empty()
                : EntryFillRule.atActivation(
                                plan.getEntryType(),
                                plan.getDirection(),
                                plan.getEntryPrice(),
                                fillDecisionPrice(plan, last))
                        .map(price -> takerFill(plan, price, calculated));
        if (fillPrice.isPresent()) {
            plan.markExecuted(now, fillPrice.get(), LiquidityRole.TAKER);
            log.info("NSF-07 plan {} filled at activation at {}", plan.getId(), fillPrice.get());
        } else {
            publishActivated(plan);
        }
    }

    /**
     * The setup score the Trader sees on the analysis screen at the activation: the preset their profile selects now
     * (not at the draft save), with its current version, read on that preset's own timeframe, whatever the plan's
     * analysis timeframe is (D-53, D-112). The plan's analysis timeframe serves the chart and the indicators of the
     * fill, not this score. The score is read at {@code now}, the plan's {@code activated_at}, so the candle it came
     * from is not stored: it is the one before the candle holding {@code activated_at} on the preset's timeframe. A score that cannot be read is stored as {@code null} beside the preset
     * and never refuses the activation. When the read itself fails, the score and the preset both stay {@code null},
     * a warning names the plan, and the activation goes on.
     *
     * <p>Rule: BR-13, BR-70; D-53, D-111 (M4), D-112; Q-74, Q-77.
     */
    private void recordSetupScore(TradingPlan plan, Instant now) {
        SetupStyleSource source = styles.getIfAvailable();
        SetupStyle style =
                source == null ? null : source.styleOf(plan.getUserId()).orElse(null);
        SetupScoreReading reading;
        try {
            reading = market.closedCandleSetupScore(
                    plan.getPairId(), plan.getMarket(), market.presetTimeframe(style), style, null, now);
        } catch (RuntimeException e) {
            log.warn("BR-70 plan {} activated without a setup score: the read failed", plan.getId(), e);
            return;
        }
        // At the column's scale, so the response of this request reads as the stored value does later.
        plan.recordSetupScoreAtPlan(
                reading.score() == null
                        ? null
                        : BigDecimal.valueOf(reading.score()).setScale(2),
                reading.preset());
    }

    /**
     * The price a fill at activation pays. MARKET: the price it was calculated with, which already holds the slippage
     * (D-112 R6). LIMIT: the last price moved by {@code SLIPPAGE_BPS}, read for this fill, and capped at the limit.
     *
     * <p>Rule: BR-33, BR-36; D-111 E5; D-112 R6 and its note.
     */
    private BigDecimal takerFill(TradingPlan plan, BigDecimal price, CalculatedPlan calculated) {
        if (plan.getEntryType() == EntryType.MARKET) {
            return price;
        }
        return SlippageCalculator.marketableLimitFill(
                price,
                plan.getEntryPrice(),
                plan.getDirection(),
                settings.integer(SLIPPAGE_BPS),
                calculated.calculation().plan().filters().tickSize());
    }

    /**
     * MARKET: the price the activation was just calculated with. LIMIT: the last price the validity checks read, or
     * empty when the cache held no current one; the entry then waits for the matching engine.
     */
    private static Optional<BigDecimal> fillDecisionPrice(TradingPlan plan, Optional<BigDecimal> last) {
        return plan.getEntryType() == EntryType.MARKET ? Optional.of(plan.getEntryPrice()) : last;
    }

    /**
     * The price a MARKET entry executes at: the last trade price, on Futures the close of the latest 1-minute candle
     * update, only while the cache holds a current one. The mark price stays for liquidation and unrealized P/L.
     *
     * <p>Rule: BR-33; D-111 E2/E3 (amends D-84 and A-41).
     */
    private Optional<BigDecimal> currentReferencePrice(TradablePair pair) {
        return market.currentLastPrice(pair.market(), pair.symbol());
    }

    private static BusinessException priceUnavailable(TradablePair pair) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE,
                "no current last price for " + pair.symbol() + " on " + pair.market());
    }

    private void publishActivated(TradingPlan plan) {
        events.publishEvent(new TradingPlanActivated(
                plan.getId(),
                plan.getMarket(),
                plan.getPairId(),
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice(),
                plan.getActivatedAt()));
    }
}
