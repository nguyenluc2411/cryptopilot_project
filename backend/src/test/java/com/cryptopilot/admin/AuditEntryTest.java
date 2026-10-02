package com.cryptopilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void BR57_anEntry_needsAnActionAndAnEntityType() {
        assertThatThrownBy(() -> new AuditEntry(null, null, AuditedEntity.USER_ACCOUNT, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(() -> new AuditEntry(null, AuditAction.ACCOUNT_LOCKED, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    void BR57_theValues_areCopiedKeepEmptyFieldsAndCannotBeChangedAfterwards() {
        Map<String, Object> before = new HashMap<>();
        before.put("lockReason", null);

        AuditEntry entry =
                new AuditEntry(null, AuditAction.ACCOUNT_LOCKED, AuditedEntity.USER_ACCOUNT, null, before, null);
        before.put("lockReason", "changed later");

        assertThat(entry.oldValue()).containsEntry("lockReason", null);
        assertThat(entry.newValue()).isNull();
        assertThatThrownBy(() -> entry.oldValue().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
