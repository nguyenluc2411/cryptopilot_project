package com.cryptopilot.community.repository;

import com.cryptopilot.community.model.FeedPage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads what a member page lists besides the member's posts (D-99, as on CoinMarketCap Community): the coins their
 * posts talk about, their comments and the posts they reacted to. Each is one query over what the community already
 * records; nothing is stored for the page.
 *
 * <p>Who sees what follows the feeds (BR-47, D-125): only published posts and published comments, and nothing of a
 * post whose author is in a block with the reader, since the reader does not see that post anywhere else either.
 *
 * <p>Rule: BR-47; D-99, D-125.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MemberPageRepository {

    /** Stands for a Guest in the block filters: no account has this key. */
    public static final UUID NOBODY = new UUID(0L, 0L);

    /** The reactions a member page lists: a like, or a Bullish or Bearish vote on a post (D-125). */
    private static final String REACTIONS = "('LIKE', 'BULLISH', 'BEARISH')";

    // Neither member sees the other's posts when a block stands between them (D-125); a Guest blocks nobody.
    private static final String NOT_BLOCKED = " and not exists (select 1 from user_relation b"
            + " where b.relation_type = 'BLOCK'"
            + " and ((b.source_user_id = :caller and b.target_user_id = p.author_id)"
            + " or (b.source_user_id = p.author_id and b.target_user_id = :caller)))";

    private final JdbcClient sql;

    /**
     * A coin a member's posts talk about.
     *
     * @param coinId the coin
     * @param posts the member's published posts tagged with it
     * @param allMentions the coin tags of all the member's published posts, for the coin's share of them
     * @param bullishPosts those of the posts marked Bullish by the member
     * @param bearishPosts those marked Bearish
     * @param lastPostAt when the newest of the posts was published
     */
    public record TokenMention(
            UUID coinId, long posts, long allMentions, long bullishPosts, long bearishPosts, Instant lastPostAt) {}

    /**
     * The coins a member's published posts are tagged with, the most posted about first, then the most recently, at
     * most {@code limit} of them. Official posts are the team's, not the member's, and are left out.
     */
    public List<TokenMention> tokenMentions(UUID memberId, int limit) {
        return sql.sql("""
                        select c.coin_id,
                               count(*) as posts,
                               sum(count(*)) over () as all_mentions,
                               count(*) filter (where p.sentiment = 'BULLISH') as bullish,
                               count(*) filter (where p.sentiment = 'BEARISH') as bearish,
                               max(p.created_at) as last_at
                          from forum_post p
                          join post_coin c on c.post_id = p.post_id
                         where p.author_id = :member and p.post_status = 'PUBLISHED' and not p.is_official
                         group by c.coin_id
                         order by posts desc, last_at desc, c.coin_id
                         limit :limit""")
                .param("member", memberId)
                .param("limit", limit)
                .query((row, index) -> new TokenMention(
                        row.getObject("coin_id", UUID.class),
                        row.getLong("posts"),
                        row.getLong("all_mentions"),
                        row.getLong("bullish"),
                        row.getLong("bearish"),
                        row.getTimestamp("last_at").toInstant()))
                .list();
    }

    /**
     * A comment of a member and the post it is on.
     *
     * @param commentId the comment
     * @param postId the post it is on
     * @param reply whether it answers another comment
     * @param content its text
     * @param likes the likes it received
     * @param createdAt when it was written
     * @param postTitle the title of the post
     * @param postContent the text of the post
     */
    public record MemberComment(
            UUID commentId,
            UUID postId,
            boolean reply,
            String content,
            long likes,
            Instant createdAt,
            String postTitle,
            String postContent) {}

    /** A page of rows and the number of rows of all pages. */
    public record Rows<T>(List<T> items, long total) {}

    /**
     * One page of a member's published comments on published posts, the newest first, without those on the posts of a
     * member in a block with {@code caller} ({@code null} for a Guest).
     */
    public Rows<MemberComment> comments(UUID memberId, UUID caller, Pageable page) {
        Map<String, Object> params = params(memberId, caller);
        String from = " from forum_comment k join forum_post p on p.post_id = k.post_id"
                + " where k.author_id = :member and k.comment_status = 'PUBLISHED' and p.post_status = 'PUBLISHED'"
                + NOT_BLOCKED;
        Map<String, Object> paged = paged(params, page);
        List<MemberComment> items = sql.sql("select k.comment_id, k.post_id, k.parent_comment_id, k.content,"
                        + " k.created_at, p.title, p.content as post_content,"
                        + " (select count(*) from engagement e where e.comment_id = k.comment_id"
                        + " and e.action_type = 'LIKE') as likes"
                        + from
                        + " order by k.created_at desc, k.comment_id desc limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> new MemberComment(
                        row.getObject("comment_id", UUID.class),
                        row.getObject("post_id", UUID.class),
                        row.getObject("parent_comment_id", UUID.class) != null,
                        row.getString("content"),
                        row.getLong("likes"),
                        row.getTimestamp("created_at").toInstant(),
                        row.getString("title"),
                        row.getString("post_content")))
                .list();
        long total = sql.sql("select count(*)" + from)
                .params(params)
                .query(Long.class)
                .single();
        return new Rows<>(items, total);
    }

    /**
     * One page of the published posts a member reacted to (liked or voted on), the most recently reacted to first,
     * without the posts of a member in a block with {@code caller} ({@code null} for a Guest). A post reacted to twice
     * is listed once, at its newest reaction.
     */
    public FeedPage reactions(UUID memberId, UUID caller, Pageable page) {
        Map<String, Object> params = params(memberId, caller);
        String from = " from (select e.post_id, max(e.created_at) as reacted_at from engagement e"
                + " where e.user_id = :member and e.post_id is not null and e.action_type in " + REACTIONS
                + " group by e.post_id) r"
                + " join forum_post p on p.post_id = r.post_id"
                + " where p.post_status = 'PUBLISHED'"
                + NOT_BLOCKED;
        List<UUID> ids = sql.sql("select r.post_id" + from
                        + " order by r.reacted_at desc, r.post_id desc limit :limit offset :offset")
                .params(paged(params, page))
                .query((row, index) -> row.getObject("post_id", UUID.class))
                .list();
        long total = sql.sql("select count(*)" + from)
                .params(params)
                .query(Long.class)
                .single();
        return new FeedPage(ids, total);
    }

    private static Map<String, Object> params(UUID memberId, UUID caller) {
        Map<String, Object> params = new HashMap<>();
        params.put("member", memberId);
        params.put("caller", caller == null ? NOBODY : caller);
        return params;
    }

    private static Map<String, Object> paged(Map<String, Object> params, Pageable page) {
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        return paged;
    }
}
