package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.dto.response.SystemSettingResponse;
import com.cryptopilot.admin.model.SettingRule;
import com.cryptopilot.admin.model.StoredSetting;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.admin.repository.SystemSettingRepository;
import com.cryptopilot.admin.service.SystemSettingService;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SCR-43. A value is checked against its type and, when the setting has one, its {@link SettingRule}, stored in the
 * plain form of its type, audited with the old and new values (BR-57; the key goes in the values, a setting has no
 * uuid), and evicted from {@link SystemSettingReader}'s cache once the change commits.
 *
 * <p>Rule: SRS 3.11.8, UC-51; BR-57.
 */
@Service
@RequiredArgsConstructor
public class SystemSettingServiceImpl implements SystemSettingService {

    private final SystemSettingRepository settings;
    private final SystemSettingReader reader;
    private final AdminApi admin;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<SystemSettingResponse> list() {
        return settings.findAll().stream()
                .map(SystemSettingServiceImpl::response)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SystemSettingResponse get(String key) {
        return response(stored(settings.find(key), key));
    }

    @Override
    @Transactional
    public SystemSettingResponse update(UUID adminId, String key, String value) {
        StoredSetting before = stored(settings.findForUpdate(key), key);
        String plain = checked(before.valueType(), SettingRule.of(key), value.strip());
        if (!plain.equals(before.value())) {
            settings.update(key, plain, adminId, clock.instant());
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.CONFIGURATION_CHANGED,
                    AuditedEntity.SYSTEM_SETTING,
                    null,
                    Map.of("key", key, "value", before.value()),
                    Map.of("key", key, "value", plain)));
            evictAfterCommit(key);
        }
        return response(stored(settings.find(key), key));
    }

    /** The value in its type's plain form, or MSG15 on {@code value} when it is not one or is out of range. */
    static String checked(String type, Optional<SettingRule> rule, String value) {
        try {
            return switch (type) {
                case "INT" -> {
                    BigDecimal number = BigDecimal.valueOf(Integer.parseInt(value));
                    requireInRange(rule, number, value);
                    yield number.toPlainString();
                }
                case "DECIMAL" -> {
                    BigDecimal number = new BigDecimal(value);
                    if (rule.isPresent()
                            && number.stripTrailingZeros().scale() > rule.get().scale()) {
                        throw refused(value, "has more than " + rule.get().scale() + " decimal places");
                    }
                    requireInRange(rule, number, value);
                    yield number.toPlainString();
                }
                case "BOOLEAN" -> {
                    String flag = value.toLowerCase(Locale.ROOT);
                    if (!flag.equals("true") && !flag.equals("false")) {
                        throw refused(value, "is not true or false");
                    }
                    yield flag;
                }
                default -> value;
            };
        } catch (NumberFormatException notANumber) {
            throw refused(value, "is not a " + type);
        }
    }

    private static void requireInRange(Optional<SettingRule> rule, BigDecimal number, String value) {
        if (rule.isPresent()
                && (number.compareTo(rule.get().min()) < 0
                        || number.compareTo(rule.get().max()) > 0)) {
            throw refused(
                    value,
                    "is outside " + rule.get().min().toPlainString() + " to "
                            + rule.get().max().toPlainString());
        }
    }

    private static FieldValidationException refused(String value, String reason) {
        return new FieldValidationException("the value " + value + " " + reason, Map.of("value", "MSG15"));
    }

    private void evictAfterCommit(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reader.evict(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reader.evict(key);
            }
        });
    }

    private static StoredSetting stored(Optional<StoredSetting> found, String key) {
        return found.orElseThrow(() -> new ResourceNotFoundException("SystemSetting", key));
    }

    private static SystemSettingResponse response(StoredSetting setting) {
        Optional<SettingRule> rule = SettingRule.of(setting.key());
        return new SystemSettingResponse(
                setting.key(),
                setting.value(),
                setting.valueType(),
                setting.description(),
                rule.map(SettingRule::min).orElse(null),
                rule.map(SettingRule::max).orElse(null),
                setting.updatedBy(),
                setting.updatedAt());
    }
}
