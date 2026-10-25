package com.cryptopilot.news.client;

/**
 * A web page as the server sent it, read for the text of a summary and then dropped (BR-48).
 *
 * @param url the address the page was read from, after redirects
 * @param body the bytes of the page, at most the configured size
 * @param contentType the {@code Content-Type} the server declared, or {@code null}
 */
public record WebPage(String url, byte[] body, String contentType) {}
