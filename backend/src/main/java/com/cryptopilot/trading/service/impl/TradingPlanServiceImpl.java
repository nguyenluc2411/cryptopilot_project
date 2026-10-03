package com.cryptopilot.trading.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.PlanInputs;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.CalculatedPlan;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.PlanTerms;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskProfileLimits;
import com.cryptopilot.trading.model.enums.EntryType;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading plan use cases. Each method is one transaction that loads what it needs from the other modules through
 * their APIs, lets the calculations and the {@link TradingPlan} aggregate decide, and saves. An activation takes the
 * D-63 lock on the Trader before it counts the ACTIVE plans, so the count and the activation it allows cannot
 * interleave with another one.
 *
 * <p>Rule: UC-16 to UC-20; BR-21 to BR-33, BR-62, BR-66; CR-04; D-63, D-70, D-71, D-72.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service
 * Layer".
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew: a
 * count followed by an insert needs a lock).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingPlanServiceImpl implements TradingPlanService {

    /** How long an unfilled LIMIT plan stays ACTIVE when the Trader sets no expiry (SRS 3.5.1). */
    static final Duration LIMIT_EXPIRY = Duration.ofDays(7);

    /** Rows per page of the plan list (CR-04). */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Excludes no plan from the other open risk, and stands for "any pair" in the list: no row has this key. */
    private static final UUID NO_PLAN = new UUID(0L, 0L);

    /** The bounds of an unfiltered period: before any plan and after any plan. */
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");

    private static final Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    private final TradingPlanRepository plans;
    private final PlanCalculationAssembler calculations;
    private final ActivePlanLock activePlanLock;
    private final MarketApi market;
    private final RiskProfileApi riskProfiles;
    private final EntitlementApi entitlements;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public PlanCalculationResponse calculate(UUID userId, CreateTradingPlanRequest request) {
        return PlanResponses.calculation(calculate(userId, request.pairId(), terms(request), NO_PLAN));
    }

    @Override
    @Transactional
    public TradingPlanResponse create(UUID userId, CreateTradingPlanRequest request) {
        Instant now = clock.instant();
        boolean activate = Boolean.TRUE.equals(request.activate());
        if (activate) {
            activePlanLock.lock(userId);
        }
        CalculatedPlan calculated = calculate(userId, request.pairId(), terms(request), NO_PLAN);
        TradingPlan plan = TradingPlan.draft(
                userId, request.pairId(), calculated.calculation(), details(request, now), calculated.warnings());
        if (activate) {
            requireRoomForAnActivePlan(userId);
            plan.activate(calculated.calculation(), calculated.warnings(), now);
            publishActivated(plan);
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
        CalculatedPlan calculated = calculate(userId, plan.getPairId(), terms(request), plan.getId());
        plan.updateDraft(calculated.calculation(), details(request, now), calculated.warnings());
        if (activate) {
            requireRoomForAnActivePlan(userId);
            plan.activate(calculated.calculation(), calculated.warnings(), now);
            publishActivated(plan);
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
        CalculatedPlan calculated = calculate(userId, plan.getPairId(), terms(plan), plan.getId());
        plan.activate(calculated.calculation(), calculated.warnings(), now);
        publishActivated(plan);
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
    @Transactional(readOnly = true)
    public TradingPlanResponse get(UUID userId, UUID planId) {
        return PlanResponses.detail(owned(userId, planId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TradingPlanSummaryResponse> list(UUID userId, PlanListQuery query) {
        int page = query.page() == null ? 1 : query.page();
        int pageSize = query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize();
        if (page < 1) {
            throw new FieldValidationException("page must be at least 1, was " + page, Map.of("page", "MSG15"));
        }
        if (pageSize < 1 || pageSize > PageResponse.MAX_PAGE_SIZE) {
            throw new FieldValidationException(
                    "pageSize must be between 1 and " + PageResponse.MAX_PAGE_SIZE + ", was " + pageSize,
                    Map.of("pageSize", "MSG15"));
        }
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
                query.from() == null ? EARLIEST : query.from(),
                query.to() == null ? LATEST : query.to(),
                PageRequest.of(page - 1, pageSize));
        return new PageResponse<>(
                found.map(PlanResponses::summary).getContent(), page, pageSize, found.getTotalElements());
    }

    /**
     * The plan calculated with its defaults applied and the current data of the other modules: the pair's filters,
     * the last price for a MARKET entry, the risk profile, the other open risk.
     */
    private CalculatedPlan calculate(UUID userId, UUID pairId, PlanTerms terms, UUID excludedPlan) {
        if (terms.market() == MarketType.FUTURES) {
            entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        }
        TradablePair pair = market.tradablePair(pairId, terms.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", pairId));
        PlanDefaults defaults = riskProfiles.planDefaultsOf(userId);
        BigDecimal capital = terms.capital() != null ? terms.capital() : defaults.defaultCapital();
        if (capital == null) {
            throw new FieldValidationException("no capital entered and no default capital", Map.of("capital", "MSG01"));
        }
        RiskInput input = new RiskInput(
                terms.market(),
                terms.direction(),
                entryPrice(pair, terms),
                tick(pair, terms.stopLoss()),
                tick(pair, terms.takeProfit()),
                capital,
                riskPercent(terms, defaults),
                terms.leverage() == null ? 1 : terms.leverage(),
                pair.filters(),
                limits(defaults.riskProfile()),
                plans.activeRiskExcluding(userId, excludedPlan).add(openPositionRisk(userId)));
        return calculations.calculate(pair, input);
    }

    /** MARKET: the current last price (BR-33); LIMIT: the price entered, rounded to the tick (BR-30). */
    private BigDecimal entryPrice(TradablePair pair, PlanTerms terms) {
        if (terms.entryType() == EntryType.MARKET) {
            return market.currentLastPrice(pair.market(), pair.symbol())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.MARKET_PRICE_UNAVAILABLE,
                            "no current last price for " + pair.symbol() + " on " + pair.market()));
        }
        if (terms.entryPrice() == null) {
            throw new FieldValidationException("a LIMIT plan needs an entry price", Map.of("entryPrice", "MSG01"));
        }
        return tick(pair, terms.entryPrice());
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

    /** A LIMIT plan without an expiry expires after {@link #LIMIT_EXPIRY}; a MARKET plan has none by default. */
    private static PlanDetails details(PlanInputs request, Instant now) {
        Instant expiresAt = request.expiresAt();
        if (expiresAt == null && request.entryType() == EntryType.LIMIT) {
            expiresAt = now.plus(LIMIT_EXPIRY);
        }
        return new PlanDetails(request.entryType(), expiresAt, request.note());
    }

    private void publishActivated(TradingPlan plan) {
        events.publishEvent(new TradingPlanActivated(
                plan.getId(),
                plan.getMarket(),
                plan.getPairId(),
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice()));
    }
}
