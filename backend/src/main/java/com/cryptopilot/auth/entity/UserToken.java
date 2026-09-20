package com.cryptopilot.auth.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One issued token: an email verification link, a password reset link, or a refresh token. Each is
 * valid until an instant and may be used once.
 *
 * <h2>Aggregate</h2>
 *
 * <p>An aggregate root of its own, in the module that owns the table. The database ties its
 * lifetime to the account — {@code fk_user_token_user} cascades, so deleting an account would take
 * its tokens — but {@code auth} and {@code user} are separate modules, and a module reaches another
 * only through its public API. The account is therefore named by identifier and never mapped as an
 * association; that is also what Vernon recommends for its own reasons, so the module boundary and
 * the modelling rule point the same way.
 *
 * <h2>What is stored</h2>
 *
 * <p>Never the token. The value sent to the holder exists in the mail that carries it and in the
 * client that keeps it; what is stored here is its SHA-256 digest, so a database that leaks tells
 * an attacker nothing they can present. Lookup works because the digest of the presented value is
 * what is searched for — which is also why the column is unique. The constructor refuses anything
 * that is not a 64-character hexadecimal digest, so a raw secret cannot be stored here by mistake.
 *
 * <h2>Validity</h2>
 *
 * <p>The windows belong to the rules, not to this class: 24 hours for a verification link (BR-01),
 * 30 minutes for a password reset (BR-04). The caller computes the expiry from the injected clock
 * and passes it in, so this class carries the instant rather than a duration and stays correct when
 * a window changes. Single use is enforced here, because it is a property of the row: {@link
 * #markUsed(Instant)} refuses a token that has been used or has expired, and a refresh token
 * presented after use is the reuse that revokes the family (TECHNICAL_DESIGN 7.15) — detecting it
 * is this method's rejection, acting on it is the service's.
 *
 * <p>Rule: BR-01, BR-04; TECHNICAL_DESIGN sections 5.3 and 7.15.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 and 6.
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10
 * (reference other aggregates by identity).
 */
@Entity
@Table(name = "user_token")
@AttributeOverride(name = "id", column = @Column(name = "token_id", nullable = false, updatable = false))
public class UserToken extends BaseEntity {

    /** A SHA-256 digest in lower-case hexadecimal, which is exactly what the column holds. */
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, updatable = false, length = 32)
    private TokenType tokenType;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** For JPA only. */
    protected UserToken() {}

    private UserToken(UUID userId, TokenType tokenType, String tokenHash, Instant expiresAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tokenType = Objects.requireNonNull(tokenType, "tokenType must not be null");
        this.tokenHash = requireDigest(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * Records a token that has just been issued.
     *
     * @param tokenHash the SHA-256 digest of the value sent to the holder, in lower-case
     *     hexadecimal. Passing the value itself is refused, because the column would accept it and
     *     nothing afterwards would notice.
     * @param expiresAt when the token stops being usable, computed by the caller from the injected
     *     clock and the window the rule gives this kind of token (BR-01, BR-04).
     */
    public static UserToken issue(UUID userId, TokenType tokenType, String tokenHash, Instant expiresAt) {
        return new UserToken(userId, tokenType, tokenHash, expiresAt);
    }

    /**
     * Consumes the token, refusing one that has already been used or has expired.
     *
     * <p>Both refusals carry the same error, and deliberately: distinguishing "used" from "expired"
     * from "never existed" tells whoever is presenting the value which of the three it is.
     *
     * <p>Rule: BR-01, BR-04 (a link is valid for its window and can be used once).
     */
    public void markUsed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!isUsableAt(now)) {
            throw new BusinessException(
                    ErrorCode.TOKEN_INVALID_OR_EXPIRED, "token " + getId() + " is used already or has expired");
        }
        this.usedAt = now;
    }

    /**
     * Whether the token would be accepted at this instant: not yet used, and not yet expired. The
     * expiry instant itself is past the window — a link valid for 24 hours is not valid at 24 hours.
     */
    public boolean isUsableAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** The account this token was issued to. */
    public UUID getUserId() {
        return userId;
    }

    /** What presenting this token achieves. */
    public TokenType getTokenType() {
        return tokenType;
    }

    /** The SHA-256 digest of the issued value. Never the value. */
    public String getTokenHash() {
        return tokenHash;
    }

    /** When the token stops being usable (BR-01, BR-04). */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** When the token was used, or {@code null} while it has not been. */
    public Instant getUsedAt() {
        return usedAt;
    }

    private static String requireDigest(String tokenHash) {
        if (tokenHash == null || !SHA_256_HEX.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException(
                    "tokenHash must be a SHA-256 digest in lower-case hexadecimal, never the token itself");
        }
        return tokenHash;
    }
}
