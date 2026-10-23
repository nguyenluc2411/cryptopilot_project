package com.cryptopilot.community.model;

/**
 * The counts a post is shown with, and what the caller did to it.
 *
 * @param likes the likes of the post
 * @param comments its published comments
 * @param likedByCaller whether the caller liked it
 * @param savedByCaller whether the caller saved it
 */
public record PostEngagement(long likes, long comments, boolean likedByCaller, boolean savedByCaller) {

    /** A post nobody has reacted to. */
    public static final PostEngagement NONE = new PostEngagement(0, 0, false, false);
}
