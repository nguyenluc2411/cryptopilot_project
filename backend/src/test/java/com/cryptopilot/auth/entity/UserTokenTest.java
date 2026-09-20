package com.cryptopilot.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two properties every issued token has: it stops working at an instant, and it works once.
 *
 * <p>What is proved here is the part that is a property of the row. The windows themselves — 24
 * hours for a verification link (BR-01), 30 minutes for a password reset (BR-04) — are computed by
 * the caller from the injected clock and arrive as an instant, so they are proved where they are
 * computed: the registration and password reset tasks. What is proved here is that whatever instant
 * arrives is honoured, that the boundary is exclusive, and that a used token is refused. The reuse
 * of a refresh token also revokes the rest of its family (TECHNICAL_DESIGN 7.15); detecting the
 * reuse is the rejection below, acting on it belongs to T-012, and the family column does not exist
 * yet (A-10).
 */
class UserTokenTest {

    private static final UUID ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");
    private static final Instant ISSUED = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(24 * 60 * 60);

    /** A SHA-256 digest in lower-case hexadecimal, which is what the column is sized for. */
    private static final String DIGEST = "a".repeat(64);

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void BR01_aFreshToken_isUsableUntilItsExpiryInstant(TokenType type) {
        UserToken token = UserToken.issue(ACCOUNT, type, DIGEST, EXPIRES);

        assertThat(token.isUsableAt(ISSUED)).isTrue();
        assertThat(token.isUsableAt(EXPIRES.minusMillis(1))).isTrue();
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getUserId()).isEqualTo(ACCOUNT);
        assertThat(token.getTokenType()).isEqualTo(type);
    }

    /** A link valid for a window is not valid at the end of it: the boundary instant is already past. */
    @Test
    void BR04_aTokenAtItsExpiryInstant_isNoLongerUsable() {
        UserToken token = UserToken.issue(ACCOUNT, TokenType.PASSWORD_RESET, DIGEST, EXPIRES);

        assertThat(token.isUsableAt(EXPIRES)).isFalse();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> token.markUsed(EXPIRES))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void BR01_usingATokenOnce_recordsWhenItWasUsed() {
        UserToken token = UserToken.issue(ACCOUNT, TokenType.EMAIL_VERIFICATION, DIGEST, EXPIRES);

        token.markUsed(ISSUED.plusSeconds(60));

        assertThat(token.getUsedAt()).isEqualTo(ISSUED.plusSeconds(60));
        assertThat(token.isUsableAt(ISSUED.plusSeconds(61))).isFalse();
    }

    /**
     * BR-01 and BR-04 both make the link single-use. For a refresh token the same rejection is what
     * reveals that a stolen value was replayed (TECHNICAL_DESIGN 7.15).
     */
    @ParameterizedTest
    @EnumSource(TokenType.class)
    void BR01_usingATokenASecondTime_isRefused(TokenType type) {
        UserToken token = UserToken.issue(ACCOUNT, type, DIGEST, EXPIRES);
        token.markUsed(ISSUED.plusSeconds(60));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> token.markUsed(ISSUED.plusSeconds(120)))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
        assertThat(token.getUsedAt()).as("the first use stands").isEqualTo(ISSUED.plusSeconds(60));
    }

    /**
     * The row stores a digest, never the value that was mailed out. The guard is what stops a
     * caller passing the token itself: the column would accept the string and nothing afterwards
     * would notice that the secret is now in the database in the clear.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "a-perfectly-ordinary-looking-raw-token-value-nobody-hashed",
                // upper-case hexadecimal: the right length, the wrong alphabet
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "abc",
                ""
            })
    void aTokenHashThatIsNotASha256Digest_isRefused(String notADigest) {
        assertThatThrownBy(() -> UserToken.issue(ACCOUNT, TokenType.REFRESH, notADigest, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never the token itself");
    }

    @Test
    void aTokenWithoutAnAccount_aTypeOrAnExpiry_isRefused() {
        assertThatThrownBy(() -> UserToken.issue(null, TokenType.REFRESH, DIGEST, EXPIRES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UserToken.issue(ACCOUNT, null, DIGEST, EXPIRES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UserToken.issue(ACCOUNT, TokenType.REFRESH, DIGEST, null))
                .isInstanceOf(NullPointerException.class);
    }
}
