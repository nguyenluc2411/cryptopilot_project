package com.cryptopilot.watchlist.repository;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.entity.Alert;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.PriceAlertRow;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the alert rules. Every lookup names the owner, so another Trader's alert is never loaded. The list has
 * one method per filter combination: no parameter is ever null (D-73).
 *
 * <p>Rule: BR-16, BR-17, BR-19; UC-13, UC-14; NSF-06.
 */
public interface AlertRepository extends Repository<Alert, UUID> {

    /** The select of an alert as the engine holds it, with the pair of its watchlist row. */
    String PRICE_ROW = "select new com.cryptopilot.watchlist.model.PriceAlertRow(a.id, a.userId, a.watchlistId,"
            + " w.pairId, a.market, a.condition, a.threshold, a.triggerMode, a.cooldownMinutes, a.expiresAt,"
            + " a.notifyInApp, a.notifyEmail, a.notifyPush, a.triggerCount, a.lastTriggeredAt, a.lastBarOpenTime,"
            + " a.version) from Alert a join Watchlist w on w.id = a.watchlistId";

    /** The Trader's alert with this key, or empty when there is none or it belongs to someone else. */
    @Transactional(readOnly = true)
    Optional<Alert> findByIdAndUserId(UUID alertId, UUID userId);

    /** How many alerts of the Trader are in this status: ACTIVE is what {@code ACTIVE_ALERT_MAX} counts (BR-17). */
    @Transactional(readOnly = true)
    long countByUserIdAndStatus(UUID userId, AlertStatus status);

    @Transactional(readOnly = true)
    Page<Alert> findByUserId(UUID userId, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndStatus(UUID userId, AlertStatus status, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndType(UUID userId, AlertType type, Pageable page);

    @Transactional(readOnly = true)
    Page<Alert> findByUserIdAndStatusAndType(UUID userId, AlertStatus status, AlertType type, Pageable page);

    /** The ACTIVE PRICE alerts with the pair of their watchlist row: what the alert engine loads (NSF-06). */
    @Transactional(readOnly = true)
    @Query(PRICE_ROW + " where a.status = com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE"
            + " and a.type = com.cryptopilot.watchlist.model.enums.AlertType.PRICE")
    List<PriceAlertRow> findActivePriceAlerts();

    /** One alert as {@link #findActivePriceAlerts()} gives it, or empty when it is not an ACTIVE PRICE alert. */
    @Transactional(readOnly = true)
    @Query(PRICE_ROW + " where a.id = :alertId"
            + " and a.status = com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE"
            + " and a.type = com.cryptopilot.watchlist.model.enums.AlertType.PRICE")
    Optional<PriceAlertRow> findActivePriceAlert(@Param("alertId") UUID alertId);

    /**
     * The ACTIVE INDICATOR alerts of these indicators on one candle series: what a closed candle of the series is
     * evaluated against (NSF-06).
     */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.watchlist.model.IndicatorAlert(a.id, a.userId, a.watchlistId, w.pairId,"
            + " a.market, a.indicator, a.timeframe, a.condition, a.threshold, a.triggerMode, a.cooldownMinutes,"
            + " a.expiresAt, a.notifyInApp, a.notifyEmail, a.notifyPush, a.triggerCount, a.version)"
            + " from Alert a join Watchlist w on w.id = a.watchlistId"
            + " where a.status = com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE"
            + " and a.type = com.cryptopilot.watchlist.model.enums.AlertType.INDICATOR"
            + " and w.pairId = :pairId and a.market = :market and a.timeframe = :timeframe"
            + " and a.indicator in :indicators")
    List<IndicatorAlert> findActiveIndicatorAlerts(
            @Param("pairId") UUID pairId,
            @Param("market") MarketType market,
            @Param("timeframe") String timeframe,
            @Param("indicators") Collection<AlertIndicator> indicators);

    /** The ACTIVE FUNDING_RATE alerts of every Trader, with the pair of their watchlist row (NSF-06). */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.watchlist.model.IndicatorAlert(a.id, a.userId, a.watchlistId, w.pairId,"
            + " a.market, a.indicator, a.timeframe, a.condition, a.threshold, a.triggerMode, a.cooldownMinutes,"
            + " a.expiresAt, a.notifyInApp, a.notifyEmail, a.notifyPush, a.triggerCount, a.version)"
            + " from Alert a join Watchlist w on w.id = a.watchlistId"
            + " where a.status = com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE"
            + " and a.type = com.cryptopilot.watchlist.model.enums.AlertType.INDICATOR"
            + " and a.indicator = com.cryptopilot.watchlist.model.enums.AlertIndicator.FUNDING_RATE")
    List<IndicatorAlert> findActiveFundingRateAlerts();

    /** The ACTIVE OPEN_INTEREST_CHANGE alerts on one pair, with the pair of their watchlist row (NSF-06). */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.watchlist.model.IndicatorAlert(a.id, a.userId, a.watchlistId, w.pairId,"
            + " a.market, a.indicator, a.timeframe, a.condition, a.threshold, a.triggerMode, a.cooldownMinutes,"
            + " a.expiresAt, a.notifyInApp, a.notifyEmail, a.notifyPush, a.triggerCount, a.version)"
            + " from Alert a join Watchlist w on w.id = a.watchlistId"
            + " where a.status = com.cryptopilot.watchlist.model.enums.AlertStatus.ACTIVE"
            + " and a.type = com.cryptopilot.watchlist.model.enums.AlertType.INDICATOR"
            + " and a.indicator = com.cryptopilot.watchlist.model.enums.AlertIndicator.OPEN_INTEREST_CHANGE"
            + " and w.pairId = :pairId")
    List<IndicatorAlert> findActiveOpenInterestAlerts(@Param("pairId") UUID pairId);

    /** How many alerts are in this status and of this type, all Traders together. */
    @Transactional(readOnly = true)
    long countByStatusAndType(AlertStatus status, AlertType type);

    /** The keys of a watchlist row's alerts, whatever their status: those its removal deletes (BR-16). */
    @Transactional(readOnly = true)
    @Query("select a.id from Alert a where a.watchlistId = :watchlistId")
    List<UUID> findIdsByWatchlistId(@Param("watchlistId") UUID watchlistId);

    /**
     * Records one trigger of a PRICE alert, if the row still allows it, and answers whether it did. The engine decides
     * from memory; this statement decides in the database, so two evaluations of the same alert, a pause or an edit
     * committed meanwhile, or a cooldown not yet over make it change nothing. The row must still be at the version the
     * engine read, so a rule edited since is never fired on its old threshold.
     *
     * <p>A ONCE alert becomes TRIGGERED; ONCE_PER_BAR is refused within the candle it last fired on, EVERY_TIME before
     * its cooldown has passed (BR-19). {@code updated_at} only moves forward, which {@code ck_alert_expiry_window}
     * relies on.
     *
     * <p>Rule: NSF-06, BR-19; SRS 3.4.4.
     *
     * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 ("Preventing
     * Lost Updates": an atomic conditional update, compare-and-set).
     *
     * @return 1 when this call recorded the trigger, 0 when the row did not allow it
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update alert
                    set trigger_count = trigger_count + 1,
                        last_triggered_at = :now,
                        last_evaluated_value = :value,
                        last_bar_open_time = :bar,
                        alert_status = case when trigger_mode = 'ONCE' then 'TRIGGERED' else alert_status end,
                        version = version + 1,
                        updated_at = greatest(updated_at, :now)
                    where alert_id = :alertId
                      and version = :version
                      and alert_status = 'ACTIVE'
                      and alert_type = 'PRICE'
                      and (expires_at is null or expires_at > :now)
                      and (trigger_mode <> 'EVERY_TIME'
                           or last_triggered_at is null
                           or last_triggered_at + make_interval(mins => coalesce(cooldown_minutes, 0)) <= :now)
                      and (trigger_mode <> 'ONCE_PER_BAR'
                           or last_bar_open_time is null
                           or last_bar_open_time <> :bar)""", nativeQuery = true)
    int recordTrigger(
            @Param("alertId") UUID alertId,
            @Param("version") long version,
            @Param("value") BigDecimal value,
            @Param("bar") Instant barOpenTime,
            @Param("now") Instant now);

    /**
     * Records one trigger of an INDICATOR alert on a closed candle, if the row still allows it, and answers whether it
     * did. The same conditions as {@link #recordTrigger}, with the candle in place of the 1h bar: whatever the trigger
     * mode, an alert fires at most once per candle and never on a candle older than the one it last fired on, so the
     * same candle delivered twice, or read by two evaluations at once, records one trigger.
     *
     * <p>Rule: NSF-06, BR-19; SRS 3.4.4; D-89.
     *
     * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 ("Preventing
     * Lost Updates": an atomic conditional update, compare-and-set).
     *
     * @return 1 when this call recorded the trigger, 0 when the row did not allow it
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update alert
                    set trigger_count = trigger_count + 1,
                        last_triggered_at = :now,
                        last_evaluated_value = :value,
                        last_bar_open_time = :bar,
                        alert_status = case when trigger_mode = 'ONCE' then 'TRIGGERED' else alert_status end,
                        version = version + 1,
                        updated_at = greatest(updated_at, :now)
                    where alert_id = :alertId
                      and version = :version
                      and alert_status = 'ACTIVE'
                      and alert_type = 'INDICATOR'
                      and (expires_at is null or expires_at > :now)
                      and (trigger_mode <> 'EVERY_TIME'
                           or last_triggered_at is null
                           or last_triggered_at + make_interval(mins => coalesce(cooldown_minutes, 0)) <= :now)
                      and (last_bar_open_time is null or last_bar_open_time < :bar)""", nativeQuery = true)
    int recordIndicatorTrigger(
            @Param("alertId") UUID alertId,
            @Param("version") long version,
            @Param("value") BigDecimal value,
            @Param("bar") Instant barOpenTime,
            @Param("now") Instant now);

    /**
     * Records one trigger of a FUNDING_RATE alert, if the row still allows it, and answers whether it did. The same
     * conditions as {@link #recordTrigger} for a PRICE alert, on an INDICATOR row: ONCE_PER_BAR is refused within the
     * 1h candle it last fired on (A-40), EVERY_TIME before its cooldown has passed.
     *
     * <p>Rule: NSF-06, BR-19; SRS 3.4.4; A-40; D-89.
     *
     * @return 1 when this call recorded the trigger, 0 when the row did not allow it
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update alert
                    set trigger_count = trigger_count + 1,
                        last_triggered_at = :now,
                        last_evaluated_value = :value,
                        last_bar_open_time = :bar,
                        alert_status = case when trigger_mode = 'ONCE' then 'TRIGGERED' else alert_status end,
                        version = version + 1,
                        updated_at = greatest(updated_at, :now)
                    where alert_id = :alertId
                      and version = :version
                      and alert_status = 'ACTIVE'
                      and alert_type = 'INDICATOR'
                      and indicator_name = 'FUNDING_RATE'
                      and (expires_at is null or expires_at > :now)
                      and (trigger_mode <> 'EVERY_TIME'
                           or last_triggered_at is null
                           or last_triggered_at + make_interval(mins => coalesce(cooldown_minutes, 0)) <= :now)
                      and (trigger_mode <> 'ONCE_PER_BAR'
                           or last_bar_open_time is null
                           or last_bar_open_time <> :bar)""", nativeQuery = true)
    int recordFundingRateTrigger(
            @Param("alertId") UUID alertId,
            @Param("version") long version,
            @Param("value") BigDecimal value,
            @Param("bar") Instant barOpenTime,
            @Param("now") Instant now);

    /**
     * Records one trigger of an OPEN_INTEREST_CHANGE alert on an open interest reading, if the row still allows it,
     * and answers whether it did. {@code last_bar_open_time} holds the instant of the reading it last fired on: an
     * alert fires at most once per reading and never on a reading older than that one, whatever the mode, so the same
     * reading evaluated twice records one trigger. ONCE_PER_BAR is refused within the UTC hour of that reading (A-40),
     * EVERY_TIME before its cooldown has passed.
     *
     * <p>Rule: NSF-06, BR-19; SRS 3.4.4; A-40; D-89; Q-45.
     *
     * @return 1 when this call recorded the trigger, 0 when the row did not allow it
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update alert
                    set trigger_count = trigger_count + 1,
                        last_triggered_at = :now,
                        last_evaluated_value = :value,
                        last_bar_open_time = :sample,
                        alert_status = case when trigger_mode = 'ONCE' then 'TRIGGERED' else alert_status end,
                        version = version + 1,
                        updated_at = greatest(updated_at, :now)
                    where alert_id = :alertId
                      and version = :version
                      and alert_status = 'ACTIVE'
                      and alert_type = 'INDICATOR'
                      and indicator_name = 'OPEN_INTEREST_CHANGE'
                      and (expires_at is null or expires_at > :now)
                      and (last_bar_open_time is null or last_bar_open_time < :sample)
                      and (trigger_mode <> 'EVERY_TIME'
                           or last_triggered_at is null
                           or last_triggered_at + make_interval(mins => coalesce(cooldown_minutes, 0)) <= :now)
                      and (trigger_mode <> 'ONCE_PER_BAR'
                           or last_bar_open_time is null
                           or date_trunc('hour', last_bar_open_time, 'UTC') < date_trunc('hour', :sample, 'UTC'))""", nativeQuery = true)
    int recordOpenInterestTrigger(
            @Param("alertId") UUID alertId,
            @Param("version") long version,
            @Param("value") BigDecimal value,
            @Param("sample") Instant sampleTime,
            @Param("now") Instant now);

    /**
     * Locks the ACTIVE and PAUSED alerts whose expiry has passed and answers their keys; a TRIGGERED alert is left
     * alone. Followed by {@link #expire} in the same transaction.
     */
    @Query(
            value = "select alert_id from alert where alert_status in ('ACTIVE', 'PAUSED') and expires_at <= :now"
                    + " for update",
            nativeQuery = true)
    List<UUID> lockDueForExpiry(@Param("now") Instant now);

    /** Sets these alerts EXPIRED (BR-19), where they are still ACTIVE or PAUSED and past their expiry. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    update alert
                    set alert_status = 'EXPIRED', version = version + 1, updated_at = greatest(updated_at, :now)
                    where alert_id in (:alertIds)
                      and alert_status in ('ACTIVE', 'PAUSED')
                      and expires_at <= :now""", nativeQuery = true)
    int expire(@Param("alertIds") Collection<UUID> alertIds, @Param("now") Instant now);

    /** Writes an alert. Not transactional here; the unit of work is the calling service's. */
    Alert save(Alert alert);

    void delete(Alert alert);
}
