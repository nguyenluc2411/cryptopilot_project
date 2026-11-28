package com.cryptopilot.admin.service.impl;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sets a system setting for a test as SCR-43 would: the row is written and the reader's cache of it dropped, so the
 * next read sees the value. A test that changes a setting sets it back afterwards: the database is shared by the
 * suite.
 */
public final class StoredSettings {

    private final JdbcClient sql;
    private final SystemSettingReader reader;

    public StoredSettings(JdbcClient sql, SystemSettingReader reader) {
        this.sql = sql;
        this.reader = reader;
    }

    /** The stored value of {@code key}. */
    public String get(String key) {
        return sql.sql("select setting_value from system_setting where setting_key = ?")
                .param(key)
                .query(String.class)
                .single();
    }

    /** Stores {@code value} for {@code key} and drops the cached one. */
    public void set(String key, String value) {
        int updated = sql.sql("update system_setting set setting_value = ? where setting_key = ?")
                .params(value, key)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("system setting " + key + " is not stored");
        }
        reader.evict(key);
    }
}
