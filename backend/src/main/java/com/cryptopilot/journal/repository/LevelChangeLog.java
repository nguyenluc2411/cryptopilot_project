package com.cryptopilot.journal.repository;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.trading.PositionLevelChange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The history of the level changes of the journal's positions, {@code trading_journal_level_change}: one row per
 * change, never updated. Only the {@code journal} module reaches it, so only it writes the table.
 *
 * <p>Rule: BR-35; UC-21; D-119 (5), D-124.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LevelChangeLog {

    private static final String COLUMNS =
            "journal_id, changed_at, old_stop_loss_price, old_take_profit_price, new_stop_loss_price,"
                    + " new_take_profit_price";

    private final JdbcClient sql;

    /** Records one change, in the caller's transaction. */
    public void append(PositionLevelChange change, Instant now) {
        sql.sql("insert into trading_journal_level_change (level_change_id, " + COLUMNS
                        + ", created_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        UuidV7.next(),
                        change.positionId(),
                        Timestamp.from(change.changedAt()),
                        change.oldStopLoss(),
                        change.oldTakeProfit(),
                        change.newStopLoss(),
                        change.newTakeProfit(),
                        Timestamp.from(now))
                .update();
    }

    /** The changes of one position, oldest first. */
    public List<PositionLevelChange> of(UUID journalId) {
        return sql.sql("select " + COLUMNS + " from trading_journal_level_change where journal_id = ?"
                        + " order by changed_at, level_change_id")
                .param(journalId)
                .query(LevelChangeLog::change)
                .list();
    }

    /** The changes of every OPEN simulated position, oldest first per position. */
    public Map<UUID, List<PositionLevelChange>> ofOpenSimulated() {
        return sql
                .sql("select " + COLUMNS.replace("journal_id", "c.journal_id")
                        + " from trading_journal_level_change c join trading_journal j on j.journal_id = c.journal_id"
                        + " where j.trade_status = 'OPEN' and j.source = 'SIMULATED'"
                        + " order by c.journal_id, c.changed_at, c.level_change_id")
                .query(LevelChangeLog::change)
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        PositionLevelChange::positionId, LinkedHashMap::new, Collectors.toList()));
    }

    private static PositionLevelChange change(ResultSet row, int ignored) throws SQLException {
        return new PositionLevelChange(
                row.getObject("journal_id", UUID.class),
                row.getTimestamp("changed_at").toInstant(),
                row.getBigDecimal("old_stop_loss_price"),
                row.getBigDecimal("old_take_profit_price"),
                row.getBigDecimal("new_stop_loss_price"),
                row.getBigDecimal("new_take_profit_price"));
    }
}
