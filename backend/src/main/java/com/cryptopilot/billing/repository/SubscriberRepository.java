package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who holds which paid tier at an instant, for many users at once. The same rule as
 * {@link SubscriptionPackageRepository#findHeldAt}: an ACTIVE subscription running at the instant, the highest tier
 * when a user holds more than one.
 *
 * <p>Rule: SRS 3.10.4; BR-62; UC-42.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SubscriberRepository {

    private static final String HELD = """
            from user_subscription s
            join subscription_order o on o.order_id = s.order_id
            join subscription_package p on p.package_id = o.package_id
           where s.subscription_status = 'ACTIVE' and s.start_at <= :now and s.end_at > :now""";

    private final JdbcClient sql;

    /** The highest tier each of these users holds at {@code now}; a user holding none is left out. */
    public Map<UUID, PlanTier> tiersOf(Collection<UUID> userIds, Instant now) {
        Map<UUID, PlanTier> tiers = new HashMap<>();
        if (userIds.isEmpty()) {
            return tiers;
        }
        sql.sql("select distinct on (o.user_id) o.user_id, p.tier " + HELD
                        + " and o.user_id in (:users) order by o.user_id, p.tier_rank desc")
                .param("now", Timestamp.from(now))
                .param("users", userIds)
                .query((row, index) ->
                        tiers.put(row.getObject("user_id", UUID.class), PlanTier.valueOf(row.getString("tier"))))
                .list();
        return tiers;
    }

    /** The users whose highest tier at {@code now} is this one. */
    public Set<UUID> holdersOf(PlanTier tier, Instant now) {
        Set<UUID> holders = new HashSet<>();
        sql.sql("select user_id from (select distinct on (o.user_id) o.user_id, p.tier " + HELD
                        + " order by o.user_id, p.tier_rank desc) held where tier = :tier")
                .param("now", Timestamp.from(now))
                .param("tier", tier.name())
                .query((row, index) -> holders.add(row.getObject("user_id", UUID.class)))
                .list();
        return holders;
    }
}
