package com.cryptopilot.community.model;

import com.cryptopilot.community.model.enums.PostSentiment;

/**
 * The counts a post is shown with, and what the caller did to it.
 *
 * @param likes the likes of the post
 * @param comments its published comments
 * @param views the signed-in members other than the author who opened it, each once (D-125)
 * @param bullish the readers who voted it Bullish (D-125)
 * @param bearish the readers who voted it Bearish (D-125)
 * @param likedByCaller whether the caller liked it
 * @param savedByCaller whether the caller saved it
 * @param callerVote how the caller voted it, or {@code null}
 */
public record PostEngagement(
        long likes,
        long comments,
        long views,
        long bullish,
        long bearish,
        boolean likedByCaller,
        boolean savedByCaller,
        PostSentiment callerVote) {

    /** A post nobody has reacted to. */
    public static final PostEngagement NONE = new PostEngagement(0, 0, 0, 0, 0, false, false, null);
}
