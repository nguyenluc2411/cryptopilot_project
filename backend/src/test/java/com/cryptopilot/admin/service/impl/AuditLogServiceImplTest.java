package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A cell of the audit log export is quoted as RFC 4180 asks, and never read as a formula by a spreadsheet.
 *
 * <p>Rule: SRS 3.11.9; OWASP "CSV Injection".
 */
class AuditLogServiceImplTest {

    @ParameterizedTest(name = "[{0}] → [{1}]")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "plain|plain",
                "=SUM(A1)|'=SUM(A1)",
                "+1|'+1",
                "@cmd|'@cmd",
                "-1,5|`\"'-1,5\"`",
                "a,b|`\"a,b\"`",
                "say \"hi\"|`\"say \"\"hi\"\"\"`",
            })
    void aCell_isQuoted_andDefused(String value, String expected) {
        assertThat(AuditLogServiceImpl.cell(value)).isEqualTo(expected);
    }

    @Test
    void anEmptyOrMissingValue_isAnEmptyCell() {
        assertThat(AuditLogServiceImpl.cell("")).isEmpty();
        assertThat(AuditLogServiceImpl.cell(null)).isEmpty();
    }
}
