package com.cryptopilot.news.model.enums;

/**
 * How a news source is read, stored by name in {@code news_source.source_type} ({@code ck_news_source_type}). The SRS
 * maintains RSS sources only (UC-49); Atom feeds are read by the same parser and stay RSS here.
 *
 * <p>Rule: UC-49; NSF-10.
 */
public enum SourceType {
    RSS
}
