package com.cryptopilot.market.repository;

import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code market_incident} table: one row per period trading on a market was closed (D-159), at most one open per
 * market.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than a JPA entity, as for {@code dropped_backfill_gap}: operational
 * state written by single statements outside any business transaction (TECHNICAL_DESIGN 5.5).
 *
 * <p>Every update names the version it read and bumps it, so of two writes from the same state only the first changes
 * the row; the second throws and its transaction rolls back. Reference: Fowler, M. (2002). Patterns of Enterprise
 * Application Architecture. Addison-Wesley, "Optimistic Offline Lock".
 *
 * <p>Rule: NSF-03; D-159.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketIncidentRepository {

    private static final String COLUMNS = """
            market_incident_id, market_type, incident_kind, reason, last_live_at, paused_at, recovery_started_at,
            resumed_at, message, created_by, expected_end_at, version""";

    private final JdbcClient sql;

    /** Stores a new open incident; refused by the database when the market already has one open. */
    public void open(MarketIncident incident) {
        int rows = sql.sql("insert into market_incident (" + COLUMNS + """
                        ) values (:id, :market, :kind, :reason, :lastLiveAt, :pausedAt, null, null, :message, :createdBy,
                                  :expectedEndAt, 0)""")
                .param("id", incident.id())
                .param("market", incident.market().name())
                .param("kind", incident.kind().name())
                .param("reason", incident.reason().name())
                .param("lastLiveAt", utc(incident.lastLiveAt()))
                .param("pausedAt", utc(incident.pausedAt()))
                .param("message", incident.message())
                .param("createdBy", incident.createdBy())
                .param("expectedEndAt", incident.expectedEndAt() == null ? null : utc(incident.expectedEndAt()))
                .update();
        changed(rows, incident.id(), "open");
    }

    /** The open incident of the market, if any. */
    public Optional<MarketIncident> findOpen(MarketType market) {
        return sql.sql("select " + COLUMNS + " from market_incident where market_type = :market and resumed_at is null")
                .param("market", market.name())
                .query(MarketIncidentRepository::map)
                .optional();
    }

    /** Every open incident, one per market at most. */
    public List<MarketIncident> findAllOpen() {
        return sql.sql("select " + COLUMNS + " from market_incident where resumed_at is null order by paused_at")
                .query(MarketIncidentRepository::map)
                .list();
    }

    /** The latest incidents of the market, newest first. */
    public List<MarketIncident> findRecent(MarketType market, int limit) {
        return sql.sql("select " + COLUMNS + """
                         from market_incident where market_type = :market
                        order by paused_at desc, market_incident_id desc limit :limit""")
                .param("market", market.name())
                .param("limit", limit)
                .query(MarketIncidentRepository::map)
                .list();
    }

    /** Turns an open incident into maintenance, with the administrator's message and expected end. */
    public void toMaintenance(UUID id, int version, String message, Instant expectedEndAt, UUID by) {
        int rows = sql.sql("""
                        update market_incident
                           set incident_kind = 'MAINTENANCE', reason = 'MAINTENANCE', message = :message,
                               expected_end_at = :expectedEndAt, created_by = coalesce(:by, created_by),
                               recovery_started_at = null, version = version + 1
                         where market_incident_id = :id and resumed_at is null and version = :version""")
                .param("id", id)
                .param("version", version)
                .param("message", message)
                .param("expectedEndAt", expectedEndAt == null ? null : utc(expectedEndAt))
                .param("by", by)
                .update();
        changed(rows, id, "toMaintenance");
    }

    /** Records that an open incident's data came back and the replay began. */
    public void recovering(UUID id, int version, Instant at) {
        int rows = sql.sql("""
                        update market_incident set recovery_started_at = :at, version = version + 1
                         where market_incident_id = :id and resumed_at is null and version = :version""")
                .param("id", id)
                .param("version", version)
                .param("at", utc(at))
                .update();
        changed(rows, id, "recovering");
    }

    /** Records that the data was lost again while recovering: the same incident, waiting for its data again. */
    public void relapse(UUID id, int version) {
        int rows = sql.sql("""
                        update market_incident set recovery_started_at = null, version = version + 1
                         where market_incident_id = :id and resumed_at is null and version = :version""").param("id", id).param("version", version).update();
        changed(rows, id, "relapse");
    }

    /** Closes an open incident: trading reopened at {@code at}, recovery taken as begun then if it had not. */
    public void resume(UUID id, int version, Instant at) {
        int rows = sql.sql("""
                        update market_incident
                           set recovery_started_at = coalesce(recovery_started_at, :at), resumed_at = :at,
                               version = version + 1
                         where market_incident_id = :id and resumed_at is null and version = :version""")
                .param("id", id)
                .param("version", version)
                .param("at", utc(at))
                .update();
        changed(rows, id, "resume");
    }

    /**
     * A write that changed no row met an incident that is not there, already closed, or updated since the caller read
     * it (another version): the caller's state is stale.
     */
    private static void changed(int rows, UUID id, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("market_incident " + operation + " of " + id + " changed " + rows
                    + " rows: unknown, closed or updated since read");
        }
    }

    private static MarketIncident map(ResultSet row, int number) throws SQLException {
        return new MarketIncident(
                row.getObject("market_incident_id", UUID.class),
                MarketType.valueOf(row.getString("market_type")),
                IncidentKind.valueOf(row.getString("incident_kind")),
                IncidentReason.valueOf(row.getString("reason")),
                instant(row.getTimestamp("last_live_at")),
                instant(row.getTimestamp("paused_at")),
                instant(row.getTimestamp("recovery_started_at")),
                instant(row.getTimestamp("resumed_at")),
                row.getString("message"),
                row.getObject("created_by", UUID.class),
                instant(row.getTimestamp("expected_end_at")),
                row.getInt("version"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
