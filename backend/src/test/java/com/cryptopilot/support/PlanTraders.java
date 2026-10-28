package com.cryptopilot.support;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Traders on a given plan tier, written straight into the migrated schema, and their bearer tokens: a FREE Trader has
 * no subscription; a PRO or PREMIUM Trader has a PAID order for the seeded monthly package and an ACTIVE subscription
 * that started a day before {@code now} and ends 29 days after it. {@link #clear()} removes what was written.
 *
 * <p>Rule: BR-62; SRS UC-53; D-62.
 */
public final class PlanTraders {

    /** The seeded monthly package of each paid tier (V11). */
    private static final Map<PlanTier, UUID> MONTHLY_PACKAGES = Map.of(
            PlanTier.PRO, UUID.fromString("019b76da-a800-7002-8000-000000000002"),
            PlanTier.PREMIUM, UUID.fromString("019b76da-a800-7002-8000-000000000004"));

    private final JdbcClient sql;
    private final JwtEncoder jwtEncoder;
    private final Instant now;
    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> subscribers = new ArrayList<>();

    /**
     * @param now the application's instant, which the subscriptions and tokens are written around
     */
    public PlanTraders(JdbcClient sql, JwtEncoder jwtEncoder, Instant now) {
        this.sql = sql;
        this.jwtEncoder = jwtEncoder;
        this.now = now;
    }

    /** A new Trader on {@code tier}, as an {@code Authorization} header value. */
    public String bearer(PlanTier tier) {
        UUID trader = account();
        if (tier != PlanTier.FREE) {
            subscribe(trader, MONTHLY_PACKAGES.get(tier));
        }
        return bearer(trader);
    }

    /**
     * Puts a Trader written elsewhere on a paid tier, as {@link #bearer(PlanTier)} does; {@link #clear()} removes the
     * order and subscription and leaves the account to its writer.
     */
    public void subscribe(UUID trader, PlanTier tier) {
        subscribe(trader, MONTHLY_PACKAGES.get(tier));
        subscribers.add(trader);
    }

    /** Removes every Trader written here, and every order and subscription written here, Traders' or not. */
    public void clear() {
        for (UUID subscriber : subscribers) {
            removeSubscriptions(subscriber);
        }
        for (UUID account : accounts) {
            removeSubscriptions(account);
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        subscribers.clear();
        accounts.clear();
    }

    private void removeSubscriptions(UUID trader) {
        sql.sql("""
                        delete from user_subscription
                         where order_id in (select order_id from subscription_order where user_id = ?)""").param(trader).update();
        sql.sql("delete from subscription_order where user_id = ?")
                .param(trader)
                .update();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@plans.invalid", at, at).update();
        accounts.add(id);
        return id;
    }

    private void subscribe(UUID trader, UUID packageId) {
        Instant start = now.minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        packageId,
                        "PLAN-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }

    private String bearer(UUID trader) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(trader.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
