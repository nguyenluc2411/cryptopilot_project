package com.cryptopilot.academy.model;

import com.cryptopilot.academy.model.enums.ArticleLevel;

/**
 * The filters of the lesson list as the client names them; every one is optional and they combine.
 *
 * <p>Rule: T-114.
 *
 * @param topic only lessons of the topic with this slug
 * @param level only lessons of this level
 * @param text only lessons whose title or summary contains this, 2 to 100 characters, case ignored
 */
public record ArticleFilter(String topic, ArticleLevel level, String text) {

    /** No filter. */
    public static final ArticleFilter NONE = new ArticleFilter(null, null, null);
}
