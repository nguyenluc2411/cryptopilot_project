package com.cryptopilot.community.repository;

import com.cryptopilot.user.AccountActivityCounter;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A user's posts that are not deleted, published or hidden, for SCR-35.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CommunityActivityCounter implements AccountActivityCounter {

    private final JdbcClient sql;

    @Override
    public Map<String, Long> count(UUID userId) {
        long posts = sql.sql("select count(*) from forum_post where author_id = ? and post_status <> 'DELETED'")
                .param(userId)
                .query(Long.class)
                .single();
        return Map.of("posts", posts);
    }
}
