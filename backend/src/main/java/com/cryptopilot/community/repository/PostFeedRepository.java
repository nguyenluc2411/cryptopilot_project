package com.cryptopilot.community.repository;

import com.cryptopilot.community.model.AuthorStanding;
import com.cryptopilot.community.model.CoinDiscussion;
import com.cryptopilot.community.model.FeedPage;
import com.cryptopilot.community.model.FeedQuery;
import com.cryptopilot.community.model.MemberActivity;
import com.cryptopilot.community.model.PostEngagement;
import com.cryptopilot.community.model.enums.FeedTab;
import com.cryptopilot.community.model.enums.InfluencerRank;
import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostSentiment;
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

    // AuthorStanding.influence() and depth() in SQL, over the columns of the query in ranked().
    private static final String INFLUENCE_SCORE = AuthorStanding.INFLUENCE_FOLLOWERS + " * ln(1 + coalesce(f.n, 0))"
            + " + " + AuthorStanding.INFLUENCE_LIKES + " * ln(1 + coalesce(l.n, 0))"
            + " + " + AuthorStanding.INFLUENCE_COMMENTS + " * ln(1 + coalesce(c.n, 0))"
            + " + " + AuthorStanding.INFLUENCE_POSTS + " * ln(1 + a.posts)";
    private static final String DEPTH_SCORE = AuthorStanding.DEPTH_DEEP_POSTS + " * ln(1 + a.deep)"
            + " + " + AuthorStanding.DEPTH_LIKES + " * ln(1 + coalesce(l.n, 0))"
            + " + " + AuthorStanding.DEPTH_COMMENTS + " * ln(1 + coalesce(c.n, 0))";

    private final JdbcClient sql;

    /**
     * One page of a tab.
     *
     * @param caller the Trader reading the feed
     * @param likedSince the start of the {@code MOST_LIKED} and {@code TRENDING} windows; ignored by the other tabs
     * @param now the time the {@code TRENDING} tab measures the age of a post from
     */
    public FeedPage feed(FeedQuery query, UUID caller, Instant likedSince, Instant now, Pageable page) {
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
                    case TRENDING -> {
                        // Hacker News style: what a post drew, over its age in hours plus two, to the power 1.5.
                        where.append(" and p.post_status = 'PUBLISHED' and not p.is_official"
                                + " and p.created_at >= :likedSince");
                        params.put("likedSince", Timestamp.from(likedSince));
                        params.put("now", Timestamp.from(now));
                        yield " order by ((select count(*) from engagement e where e.post_id = p.post_id"
                                + " and e.action_type in ('LIKE', 'BULLISH', 'BEARISH')) * 3"
                                + " + (select count(*) from forum_comment c where c.post_id = p.post_id"
                                + " and c.comment_status = 'PUBLISHED') * 4"
                                + " + (select count(*) from engagement e where e.post_id = p.post_id"
                                + " and e.action_type = 'VIEW') * 0.5 + 1)"
                                + " / power(extract(epoch from (cast(:now as timestamptz) - p.created_at)) / 3600 + 2,"
                                + " 1.5) desc, p.created_at desc, p.post_id desc";
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
                        from.append(" join user_relation f on f.target_user_id = p.author_id"
                                + " and f.source_user_id = :caller and f.relation_type = 'FOLLOW'");
                        where.append(" and p.post_status = 'PUBLISHED' and not p.is_official");
                        yield " order by p.created_at desc, p.post_id desc";
                    }
                    case MINE -> {
                        where.append(" and p.author_id = :caller and p.post_status in ('PUBLISHED', 'HIDDEN')");
                        yield " order by p.created_at desc, p.post_id desc";
                    }
                };
        if (caller != null && query.tab() != FeedTab.MINE) {
            // A block works both ways: neither member sees the other's posts (D-125).
            where.append(" and not exists (select 1 from user_relation b where b.relation_type = 'BLOCK'"
                    + " and ((b.source_user_id = :caller and b.target_user_id = p.author_id)"
                    + " or (b.source_user_id = p.author_id and b.target_user_id = :caller)))");
        }
        if (query.coinId() != null) {
            where.append(" and exists (select 1 from post_coin c where c.post_id = p.post_id and c.coin_id = :coin)");
            params.put("coin", query.coinId());
        }
        if (query.text() != null) {
            where.append(" and (p.title ilike :text escape '\\' or p.content ilike :text escape '\\')");
            params.put("text", "%" + likeEscaped(query.text()) + "%");
        }
        if (query.authorId() != null) {
            where.append(" and p.author_id = :author");
            params.put("author", query.authorId());
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

    /**
     * The like, comment, view and vote counts of these posts and whether the caller liked, saved or voted each
     * (BR-45, D-99, D-125); the comments of members in a block with the caller are not counted, as they are not listed
     * (D-125).
     */
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
                                 where c.post_id = p.post_id and c.comment_status = 'PUBLISHED'
                                   and not exists (
                                       select 1 from user_relation b
                                        where b.relation_type = 'BLOCK'
                                          and ((b.source_user_id = :caller and b.target_user_id = c.author_id)
                                               or (b.source_user_id = c.author_id and b.target_user_id = :caller)))
                               ) as comments,
                               (select count(*) from engagement e
                                 where e.post_id = p.post_id and e.action_type = 'VIEW') as views,
                               (select count(*) from engagement e
                                 where e.post_id = p.post_id and e.action_type = 'BULLISH') as bullish,
                               (select count(*) from engagement e
                                 where e.post_id = p.post_id and e.action_type = 'BEARISH') as bearish,
                               (select e.action_type from engagement e where e.post_id = p.post_id
                                   and e.user_id = :caller and e.action_type in ('BULLISH', 'BEARISH')) as vote,
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
                                row.getLong("views"),
                                row.getLong("bullish"),
                                row.getLong("bearish"),
                                row.getBoolean("liked"),
                                row.getBoolean("saved"),
                                row.getString("vote") == null ? null : PostSentiment.valueOf(row.getString("vote")))))
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

    /**
     * The coins the published posts of the last seven days talk about most, the most posted about in the last day
     * first (D-125), as the Trending list of SCR-24 shows them, each with all its published posts as well.
     */
    public List<TrendingCoin> trendingCoins(Instant now, int limit) {
        return sql.sql("""
                        select c.coin_id,
                               count(*) filter (where p.created_at >= :day) as day,
                               count(*) filter (where p.created_at >= :week) as week,
                               count(*) as total,
                               max(p.created_at) as last_at
                          from forum_post p
                          join post_coin c on c.post_id = p.post_id
                         where p.post_status = 'PUBLISHED'
                         group by c.coin_id
                        having count(*) filter (where p.created_at >= :week) > 0
                         order by day desc, week desc, last_at desc, c.coin_id
                         limit :limit""")
                .param("day", Timestamp.from(now.minus(Duration.ofDays(1))))
                .param("week", Timestamp.from(now.minus(Duration.ofDays(7))))
                .param("limit", limit)
                .query((row, index) -> new TrendingCoin(
                        row.getObject("coin_id", UUID.class),
                        row.getLong("day"),
                        row.getLong("week"),
                        row.getLong("total"),
                        row.getTimestamp("last_at").toInstant()))
                .list();
    }

    /** How each of these members counts in the community (D-125); a member who published nothing is left out. */
    public Map<UUID, AuthorStanding> standings(Collection<UUID> memberIds, UUID caller, Instant now) {
        Map<UUID, AuthorStanding> result = new HashMap<>();
        if (memberIds.isEmpty()) {
            return result;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("members", memberIds);
        for (Ranked ranked : ranked(" and a.author_id in (:members)", "a.author_id", caller, now, params)) {
            result.put(ranked.memberId(), ranked.standing());
        }
        return result;
    }

    /**
     * The members who count most in the community, by influence or by analytical depth (D-125), leaving out those in a
     * block with the caller; administrators publish official posts only and so are never listed.
     */
    public List<Ranked> influencers(InfluencerRank rank, UUID caller, Instant now, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        String filter = caller == null
                ? ""
                : " and not exists (select 1 from user_relation b where b.relation_type = 'BLOCK'"
                        + " and ((b.source_user_id = :caller and b.target_user_id = a.author_id)"
                        + " or (b.source_user_id = a.author_id and b.target_user_id = :caller)))";
        String order =
                (rank == InfluencerRank.DEPTH ? DEPTH_SCORE : INFLUENCE_SCORE) + " desc, a.author_id limit :limit";
        return ranked(filter, order, caller, now, params);
    }

    // The standing of given members and the ranked lists are read by one query, and a list is ordered by the scores of
    // AuthorStanding, written in SQL from the same weights. The order uses the unrounded score, so two members whose
    // two-decimal scores are equal are listed by key.
    private List<Ranked> ranked(String filter, String order, UUID caller, Instant now, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>(extra);
        params.put("caller", caller == null ? NOBODY : caller);
        params.put("since", Timestamp.from(now.minus(Duration.ofDays(30))));
        params.put("deep", AuthorStanding.DEEP_TEXT_LENGTH);
        return sql.sql("""
                        with a as (
                            select p.author_id,
                                   count(*) as posts,
                                   count(*) filter (where p.post_type = 'VIDEO' or length(p.content) >= :deep
                                                       or exists (select 1 from post_media m
                                                                   where m.post_id = p.post_id)) as deep,
                                   count(*) filter (where p.sentiment = 'BULLISH' and p.created_at >= :since) as bull,
                                   count(*) filter (where p.sentiment = 'BEARISH' and p.created_at >= :since) as bear
                              from forum_post p
                             where p.post_status = 'PUBLISHED' and not p.is_official
                             group by p.author_id),
                        l as (
                            select p.author_id, count(*) as n
                              from engagement e join forum_post p on p.post_id = e.post_id
                             where e.action_type = 'LIKE' and p.post_status = 'PUBLISHED'
                             group by p.author_id),
                        c as (
                            select p.author_id, count(*) as n
                              from forum_comment k join forum_post p on p.post_id = k.post_id
                             where k.comment_status = 'PUBLISHED' and p.post_status = 'PUBLISHED'
                               and k.author_id <> p.author_id
                             group by p.author_id),
                        f as (
                            select target_user_id as author_id, count(*) as n
                              from user_relation where relation_type = 'FOLLOW'
                             group by target_user_id)
                        select a.author_id, a.posts, a.deep, a.bull, a.bear,
                               coalesce(l.n, 0) as likes, coalesce(c.n, 0) as comments, coalesce(f.n, 0) as followers,
                               exists (select 1 from user_relation r where r.relation_type = 'FOLLOW'
                                          and r.source_user_id = :caller and r.target_user_id = a.author_id)
                                   as followed
                          from a
                          left join l on l.author_id = a.author_id
                          left join c on c.author_id = a.author_id
                          left join f on f.author_id = a.author_id
                         where true""" + filter + " order by " + order)
                .params(params)
                .query((row, index) -> new Ranked(
                        row.getObject("author_id", UUID.class),
                        new AuthorStanding(
                                row.getLong("followers"),
                                row.getLong("likes"),
                                row.getLong("comments"),
                                row.getLong("posts"),
                                row.getLong("deep"),
                                row.getLong("bull"),
                                row.getLong("bear"),
                                row.getBoolean("followed"))))
                .list();
    }

    /** A member and how they count (D-125). */
    public record Ranked(UUID memberId, AuthorStanding standing) {}

    /**
     * A coin of the Trending list (D-125).
     *
     * @param postsLastDay the published posts of the last day tagged with it
     * @param postsLastWeek those of the last seven days
     * @param totalPosts all its published posts, however old
     */
    public record TrendingCoin(
            UUID coinId, long postsLastDay, long postsLastWeek, long totalPosts, Instant lastPostAt) {}

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

    // A LIKE pattern matches the text literally: its own wildcards and the escape are escaped.
    private static String likeEscaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
