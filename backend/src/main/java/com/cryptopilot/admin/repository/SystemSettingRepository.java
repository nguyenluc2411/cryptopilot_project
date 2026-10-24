package com.cryptopilot.admin.repository;

import com.cryptopilot.admin.model.StoredSetting;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code system_setting}, a table keyed by name and therefore not an entity (TECHNICAL_DESIGN 5.5).
 * Rows are seeded by migrations; an administrator changes values, never adds or removes keys.
 *
 * <p>Rule: SRS 3.11.8; UC-51.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemSettingRepository {

    private static final String COLUMNS =
            "select setting_key, setting_value, value_type, description, updated_by, updated_at from system_setting";

    private static final RowMapper<StoredSetting> ROW = (row, index) -> new StoredSetting(
            row.getString("setting_key"),
            row.getString("setting_value"),
            row.getString("value_type"),
            row.getString("description"),
            row.getObject("updated_by", UUID.class),
            row.getTimestamp("updated_at").toInstant());

    private final JdbcClient sql;

    /** Every setting, by key. A deliberate unbounded read: the table holds a few dozen seeded rows. */
    public List<StoredSetting> findAll() {
        return sql.sql(COLUMNS + " order by setting_key").query(ROW).list();
    }

    public Optional<StoredSetting> find(String key) {
        return sql.sql(COLUMNS + " where setting_key = ?").param(key).query(ROW).optional();
    }

    /** The setting, row-locked until the transaction ends, so two administrators' changes are applied in turn. */
    public Optional<StoredSetting> findForUpdate(String key) {
        return sql.sql(COLUMNS + " where setting_key = ? for update")
                .param(key)
                .query(ROW)
                .optional();
    }

    /** Stores a new value, in the caller's transaction. */
    public void update(String key, String value, UUID updatedBy, Instant now) {
        sql.sql("update system_setting set setting_value = ?, updated_by = ?, updated_at = ? where setting_key = ?")
                .params(value, updatedBy, Timestamp.from(now), key)
                .update();
    }
}
