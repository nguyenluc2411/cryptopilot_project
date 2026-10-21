package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.admin.model.StoredSetting;
import com.cryptopilot.admin.repository.SystemSettingRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code system_setting} for other modules, typed, through a cache of the rows already read (T-082). A setting is
 * read on every fee, warning and upload check, and changes a few times a year, so each key is read from the table once
 * and then served from memory; a change made on SCR-43 evicts its key after the change commits
 * ({@link SystemSettingServiceImpl}), so the next read sees the new value without a redeployment.
 *
 * <p>The cache is per process, as {@code LiveSessions}' is (D-33): a second instance would need a shared one.
 *
 * <p>Rule: SRS 3.11.8, UC-51; TECHNICAL_DESIGN section 5.5 ({@code system_setting} is a table, not an entity).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemSettingReader implements SystemSettingApi {

    private static final String DECIMAL = "DECIMAL";
    private static final String INT = "INT";

    private final SystemSettingRepository settings;
    private final Map<String, StoredSetting> cache = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public BigDecimal decimal(String key) {
        return new BigDecimal(read(key, DECIMAL));
    }

    @Override
    @Transactional(readOnly = true)
    public int integer(String key) {
        return Integer.parseInt(read(key, INT));
    }

    /** Drops a key from the cache, so the next read goes to the table. */
    void evict(String key) {
        cache.remove(key);
    }

    private String read(String key, String type) {
        StoredSetting setting = cache.computeIfAbsent(key, missing -> settings.find(missing)
                .orElseThrow(() -> new IllegalStateException("system setting " + missing + " is not stored")));
        if (!type.equals(setting.valueType())) {
            throw new IllegalStateException("system setting " + key + " is " + setting.valueType() + ", not " + type);
        }
        return setting.value();
    }
}
