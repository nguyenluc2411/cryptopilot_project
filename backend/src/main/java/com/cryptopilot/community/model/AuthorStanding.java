package com.cryptopilot.community.model;

/**
 * How much a member counts in the community, as CoinMarketCap Community ranks its influencers (D-125). Both scores are
 * computed from what the community already records; nothing is stored for them.
 *
 * <ul>
 *   <li>Influence weighs the followers, the likes and the comments of others that the member's published posts
 *       received, and those posts, each on a log scale so that one large number does not drown the rest.
 *   <li>Analytical depth weighs the published posts that carry media or a long text ({@value #DEEP_TEXT_LENGTH}
 *       characters or more), then the likes and comments all of them received.
 * </ul>
 *
 * @param followers the members who follow them
 * @param likes the likes their published posts received
 * @param comments the published comments of others on their published posts
 * @param posts their published posts
 * @param deepPosts those of their published posts that carry media or a long text
 * @param bullishPosts their published posts of the last 30 days marked Bullish
 * @param bearishPosts their published posts of the last 30 days marked Bearish
 * @param followedByCaller whether the caller follows them
 */
public record AuthorStanding(
        long followers,
        long likes,
        long comments,
        long posts,
        long deepPosts,
        long bullishPosts,
        long bearishPosts,
        boolean followedByCaller) {

    /** The shortest text that makes a post without media count for analytical depth. */
    public static final int DEEP_TEXT_LENGTH = 600;

    /** A member who has published nothing and whom nobody follows. */
    public static final AuthorStanding NONE = new AuthorStanding(0, 0, 0, 0, 0, 0, 0, false);

    /** The weight of the followers in the influence score. */
    public static final int INFLUENCE_FOLLOWERS = 40;

    /** The weight of the likes received in the influence score. */
    public static final int INFLUENCE_LIKES = 25;

    /** The weight of the comments of others received in the influence score. */
    public static final int INFLUENCE_COMMENTS = 15;

    /** The weight of the published posts in the influence score. */
    public static final int INFLUENCE_POSTS = 10;

    /** The weight of the posts with media or a long text in the analytical depth score. */
    public static final int DEPTH_DEEP_POSTS = 50;

    /** The weight of the likes received in the analytical depth score. */
    public static final int DEPTH_LIKES = 20;

    /** The weight of the comments of others received in the analytical depth score. */
    public static final int DEPTH_COMMENTS = 10;

    /** The influence score, two decimals. */
    public double influence() {
        return round(INFLUENCE_FOLLOWERS * Math.log1p(followers)
                + INFLUENCE_LIKES * Math.log1p(likes)
                + INFLUENCE_COMMENTS * Math.log1p(comments)
                + INFLUENCE_POSTS * Math.log1p(posts));
    }

    /** The analytical depth score, two decimals. */
    public double depth() {
        return round(DEPTH_DEEP_POSTS * Math.log1p(deepPosts)
                + DEPTH_LIKES * Math.log1p(likes)
                + DEPTH_COMMENTS * Math.log1p(comments));
    }

    private static double round(double score) {
        return Math.round(score * 100) / 100.0;
    }
}
