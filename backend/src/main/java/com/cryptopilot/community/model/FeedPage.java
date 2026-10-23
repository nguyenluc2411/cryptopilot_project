package com.cryptopilot.community.model;

import java.util.List;
import java.util.UUID;

/**
 * One page of a feed query: the keys of the posts in feed order, and how many match across all pages.
 *
 * @param postIds the posts of the page, in the order the feed shows them
 * @param total the number of posts the query matches
 */
public record FeedPage(List<UUID> postIds, long total) {

    public FeedPage {
        postIds = List.copyOf(postIds);
    }
}
