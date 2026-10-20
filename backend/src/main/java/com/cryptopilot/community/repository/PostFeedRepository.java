package com.cryptopilot.community.repository;

import com.cryptopilot.community.model.CoinDiscussion;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.FeedQuery;
import com.cryptopilot.community.model.MemberActivity;
import com.cryptopilot.community.model.PostEngagement;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.Sentiment;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the community feeds (SCR-24) and what each post is shown with. A feed is answered as the keys of one page in
 * feed order and the total, so the posts are loaded once by key; the counts, the caller's likes and saves and the coin
 * tags of a page are read with one query each rather than one per post.
 *
 * <p>Who sees what is part of every query (BR-47): published posts in the shared tabs, the caller's hidden posts in
 * the caller's own tabs, deleted posts nowhere. Official posts are left out of {@code LATEST} and {@code MOST_LIKED}
 * because they are pinned above the feed (BR-61) and would otherwise be listed twice.
 *
 * <p>A Guest reads the shared tabs too (D-99): the caller is then {@code null} and has liked and saved nothing.
 *
 * <p>Rule: UC-26, UC-32; BR-45, BR-47, BR-61; SRS 3.7.1; CR-04; D-99.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PostFeedRepository {

    /** Stands for a Guest in the queries that ask what the caller liked or saved: no account has this key. */
    private static final UUID NOBODY = new UUID(0L, 0L);

    private final JdbcClient sql;

    /**
     * One page of a tab.
     *
     * @param caller the Trader reading the feed
     * @param likedSince the start of the {@code MOST_LIKED} window; ignored by the other tabs
     */
    public FeedPage feed(FeedQuery query, UUID caller, Instant likedSince, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        params.put("caller", caller == null ? NOBODY : caller);
        StringBuilder from = new StringBuilder(" from forum_post p");
        StringBuilder where = new StringBuilder(" where true");
        String order =
                switch (query.tab()) {
                    case LATEST -> {
                        where.append(" and p.post_status = 'PUBLISHED' and not p.is_official");
                        yield " order by p.created_at desc, p.post_id desc";
                    }
                    case MOST_LIKED -> {
                        where.append(" and p.post_status = 'PUBLISHED' and not p.is_official"
                                + " and p.created_at >= :likedSince");
                        params.put("likedSince", Timestamp.from(likedSince));
                        yield " order by (select count(*) from engagement l where l.post_id = p.post_id"
                                + " and l.action_type = 'LIKE') desc, p.created_at desc, p.post_id desc";
                    }
                    case SAVED -> {
                        from.append(" join engagement s on s.post_id = p.post_id and s.user_id = :caller"
                                + " and s.action_type = 'SAVE'");
                        where.append(" and (p.post_status = 'PUBLISHED'"
                                + " or (p.post_status = 'HIDDEN' and p.author_id = :caller))");
                        yield " order by s.created_at desc, p.post_id desc";
                    }
                    case FOLLOWING -> {
                        from.append(" join user_follow f on f.followee_id = p.author_id and f.follower_id = :caller");
                        where.append(" and p.post_status = 'PUBLISHED' and not p.is_official");
                        yield " order by p.created_at desc, p.post_id desc";
                    }
                    case MINE -> {
                        where.append(" and p.author_id = :caller and p.post_status in ('PUBLISHED', 'HIDDEN')");
                        yield " order by p.created_at desc, p.post_id desc";
                    }
                };
        if (query.coinId() != null) {
            where.append(" and exists (select 1 from post_coin c where c.post_id = p.post_id and c.coin_id = :coin)");
            params.put("coin", query.coinId());
        }
        if (query.authorId() != null) {
            where.append(" and p.author_id = :author");
            params.put("author", query.authorId());
        }
        if (query.sentiment() != null) {
            where.append(" and p.sentiment = :sentiment");
            params.put("sentiment", query.sentiment().name());
        }
        if (query.market() != null) {
            where.append(" and p.market_type = :market");
            params.put("market", query.market().name());
        }
        return page("select p.post_id" + from + where + order, "select count(*)" + from + where, params, page);
    }

    /**
     * One page of official posts, newest first (BR-61).
     *
     * @param includeHidden whether hidden announcements are listed too, as the administration console lists them
     * @param category only official posts of this category, or {@code null} for all (D-99)
     */
    public FeedPage official(boolean includeHidden, OfficialCategory category, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        String from = " from forum_post p where p.is_official and p.post_status "
                + (includeHidden ? "in ('PUBLISHED', 'HIDDEN')" : "= 'PUBLISHED'");
        if (category != null) {
            from += " and p.official_category = :category";
            params.put("category", category.name());
        }
        return page(
                "select p.post_id" + from + " order by p.created_at desc, p.post_id desc",
                "select count(*)" + from,
                params,
                page);
    }

    /** The like and comment counts of these posts and whether the caller liked or saved each (BR-45). */
    public Map<UUID, PostEngagement> engagement(Collection<UUID> postIds, UUID caller) {
        Map<UUID, PostEngagement> result = new HashMap<>();
        if (postIds.isEmpty()) {
            return result;
        }
        sql.sql("""
                        select p.post_id,
                               (select count(*) from engagement e
                                 where e.post_id = p.post_id and e.action_type = 'LIKE') as likes,
                               (select count(*) from forum_comment c
                                 where c.post_id = p.post_id and c.comment_status = 'PUBLISHED') as comments,
                               exists (select 1 from engagement e where e.post_id = p.post_id
                                          and e.user_id = :caller and e.action_type = 'LIKE') as liked,
                               exists (select 1 from engagement e where e.post_id = p.post_id
                                          and e.user_id = :caller and e.action_type = 'SAVE') as saved
                          from forum_post p
                         where p.post_id in (:posts)""")
                .param("caller", caller == null ? NOBODY : caller)
                .param("posts", postIds)
                .query((row, index) -> result.put(
                        row.getObject("post_id", UUID.class),
                        new PostEngagement(
                                row.getLong("likes"),
                                row.getLong("comments"),
                                row.getBoolean("liked"),
                                row.getBoolean("saved"))))
                .list();
        return result;
    }

    /** How much the published posts talk about one coin, counted from {@code now} back (D-99). */
    public CoinDiscussion coinDiscussion(UUID coinId, Instant now) {
        return sql.sql("""
                        select count(*) filter (where p.created_at >= :day) as day,
                               count(*) filter (where p.created_at >= :week) as week,
                               count(*) as total,
                               max(p.created_at) as last_at
                          from forum_post p
                          join post_coin c on c.post_id = p.post_id
                         where c.coin_id = :coin and p.post_status = 'PUBLISHED'""")
                .param("coin", coinId)
                .param("day", Timestamp.from(now.minus(Duration.ofDays(1))))
                .param("week", Timestamp.from(now.minus(Duration.ofDays(7))))
                .query((row, index) -> new CoinDiscussion(
                        row.getLong("day"),
                        row.getLong("week"),
                        row.getLong("total"),
                        row.getTimestamp("last_at") == null
                                ? null
                                : row.getTimestamp("last_at").toInstant()))
                .single();
    }

    /** The published posts tagged with a coin since an instant, counted by label; unlabelled ones left out (D-99). */
    public Map<Sentiment, Long> sentimentOfPosts(UUID coinId, Instant since) {
        Map<Sentiment, Long> result = new java.util.EnumMap<>(Sentiment.class);
        sql.sql("""
                        select p.sentiment, count(*) as posts
                          from forum_post p
                          join post_coin c on c.post_id = p.post_id
                         where c.coin_id = :coin and p.post_status = 'PUBLISHED' and p.sentiment is not null
                           and p.created_at >= :since
                         group by p.sentiment""")
                .param("coin", coinId)
                .param("since", Timestamp.from(since))
                .query((row, index) -> result.put(Sentiment.valueOf(row.getString("sentiment")), row.getLong("posts")))
                .list();
        return result;
    }

    /** A member's published posts and the likes they received (D-99). */
    public MemberActivity memberActivity(UUID memberId) {
        return sql.sql("""
                        select count(*) as posts,
                               coalesce(sum((select count(*) from engagement e
                                              where e.post_id = p.post_id and e.action_type = 'LIKE')), 0) as likes
                          from forum_post p
                         where p.author_id = :member and p.post_status = 'PUBLISHED'""")
                .param("member", memberId)
                .query((row, index) -> new MemberActivity(row.getLong("posts"), row.getLong("likes")))
                .single();
    }

    /** The coin keys each of these posts is tagged with; a post without tags is left out. */
    public Map<UUID, Set<UUID>> coinTags(Collection<UUID> postIds) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        if (postIds.isEmpty()) {
            return result;
        }
        sql.sql("select post_id, coin_id from post_coin where post_id in (:posts)")
                .param("posts", postIds)
                .query((row, index) -> result.computeIfAbsent(
                                row.getObject("post_id", UUID.class), key -> new HashSet<>())
                        .add(row.getObject("coin_id", UUID.class)))
                .list();
        return result;
    }

    private FeedPage page(String select, String count, Map<String, Object> params, Pageable page) {
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<UUID> ids = sql.sql(select + " limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> row.getObject("post_id", UUID.class))
                .list();
        long total = sql.sql(count).params(params).query(Long.class).single();
        return new FeedPage(ids, total);
    }
}
