package com.cryptopilot.news.client;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Reads one RSS or Atom feed over HTTP and returns its items as the outlet wrote them (NSF-10). Used by the crawl and by
 * the test read of a feed an administrator saves (UC-49).
 *
 * <p>The address comes from an administrator, so the server must not be made to read its own network: before every
 * request, and again before every redirect is followed, the host is resolved and refused when any of its addresses is
 * loopback, private, link-local, carrier-grade NAT or multicast. A host that resolves differently between that check
 * and the connection is not caught; the check stops a mistyped or careless address, which is the threat an
 * administrator-only setting carries.
 *
 * <p>One request takes at most the read timeout from the first byte sent to the last byte received, and reads at most
 * the configured size, compressed and decompressed. The XML parser refuses a document type declaration, so an entity
 * can neither read a local file nor expand without bound.
 *
 * <p>Rule: NSF-10 (30-second timeout); UC-49 (the feed is read before it is saved).
 *
 * <p>Reference: OWASP (2021). <i>Server-Side Request Forgery Prevention Cheat Sheet</i> and <i>XML External Entity
 * Prevention Cheat Sheet</i>.
 */
public class RssClient implements AutoCloseable {

    private static final String FEED_ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1";

    private static final String PAGE_ACCEPT = "text/html, application/xhtml+xml;q=0.9, */*;q=0.1";

    private final RssClientProperties properties;
    private final HttpClient http;

    public RssClient(RssClientProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Reads the feed at {@code address}.
     *
     * @param userAgent the agent the source asks for, or {@code null} for the configured one
     * @throws RssClientException naming why the feed could not be read
     */
    public RssFeed read(String address, String userAgent) {
        Fetched fetched = fetch(address, userAgent, FEED_ACCEPT);
        return parse(fetched.uri(), fetched.response());
    }

    /**
     * Reads the article page at {@code address}, for the text an AI summary is written from (NSF-11), with the same
     * guard, limits and agent as a feed. The page is returned as the server sent it; nothing of it is stored (BR-48).
     *
     * @param userAgent the agent the source asks for, or {@code null} for the configured one
     * @throws RssClientException naming why the page could not be read
     */
    public WebPage readPage(String address, String userAgent) {
        Fetched fetched = fetch(address, userAgent, PAGE_ACCEPT);
        return new WebPage(
                fetched.uri().toString(),
                fetched.response().body(),
                fetched.response().headers().firstValue("Content-Type").orElse(null));
    }

    /** One GET, its redirects followed by hand and each target checked like the first, ending with a 2xx. */
    private Fetched fetch(String address, String userAgent, String accept) {
        URI uri = absolute(address);
        String agent = userAgent == null || userAgent.isBlank() ? properties.userAgent() : userAgent;
        for (int redirects = 0; ; redirects++) {
            requireAllowed(uri);
            Response response = get(uri, agent, accept);
            if (isRedirect(response.status())) {
                if (redirects >= properties.maxRedirects()) {
                    throw new RssClientException(
                            RssClientException.Reason.HTTP_STATUS,
                            address + " redirected more than " + properties.maxRedirects() + " times");
                }
                uri = redirectTarget(uri, response.headers());
                continue;
            }
            if (response.status() / 100 != 2) {
                throw new RssClientException(
                        RssClientException.Reason.HTTP_STATUS, uri + " answered HTTP " + response.status());
            }
            return new Fetched(uri, response);
        }
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * Whether a connection to {@code address} would reach the server's own network: loopback, the wildcard,
     * link-local (cloud metadata lives there), the private ranges of RFC 1918 and RFC 4193, carrier-grade NAT (RFC
     * 6598) or multicast.
     */
    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private static URI absolute(String address) {
        try {
            URI uri = new URI(
                    Objects.requireNonNull(address, "address must not be null").strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // reported below, like any other address that is not a web address
        }
        throw new RssClientException(
                RssClientException.Reason.INVALID_ADDRESS, address + " is not an http or https address");
    }

    private void requireAllowed(URI uri) {
        if (properties.allowPrivateAddresses()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new RssClientException(
                    RssClientException.Reason.UNREACHABLE, uri.getHost() + " cannot be resolved", e);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new RssClientException(
                        RssClientException.Reason.BLOCKED_ADDRESS,
                        uri.getHost() + " resolves to an address of the server's own network");
            }
        }
    }

    private Response get(URI uri, String agent, String accept) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("User-Agent", agent)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        long limit = properties.maxFeedSize().toBytes();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new LimitedBody(limit));
        try {
            HttpResponse<byte[]> response = pending.get(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.headers(), decoded(uri, response, limit));
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw timedOut(uri, e);
        } catch (ExecutionException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseTooLarge) {
                    throw tooLarge(uri, limit);
                }
                if (cause instanceof HttpTimeoutException) {
                    throw timedOut(uri, cause);
                }
            }
            throw new RssClientException(
                    RssClientException.Reason.UNREACHABLE, uri + " could not be read: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new RssClientException(RssClientException.Reason.UNREACHABLE, "interrupted reading " + uri, e);
        }
    }

    /** The body, gunzipped when the server compressed it, never more than {@code limit} bytes either way. */
    private static byte[] decoded(URI uri, HttpResponse<byte[]> response, long limit) {
        boolean gzip = response.headers()
                .firstValue("Content-Encoding")
                .map(encoding -> encoding.toLowerCase(Locale.ROOT).contains("gzip"))
                .orElse(false);
        if (!gzip) {
            return response.body();
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            byte[] body = in.readNBytes(Math.toIntExact(Math.min(limit + 1, Integer.MAX_VALUE - 8)));
            if (body.length > limit) {
                throw tooLarge(uri, limit);
            }
            return body;
        } catch (IOException e) {
            throw new RssClientException(
                    RssClientException.Reason.NOT_A_FEED, uri + " sent a body that is not valid gzip", e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI redirectTarget(URI from, HttpHeaders headers) {
        String location = headers.firstValue("Location")
                .orElseThrow(() -> new RssClientException(
                        RssClientException.Reason.HTTP_STATUS, from + " redirected without a Location"));
        try {
            return absolute(from.resolve(new URI(location.strip())).toString());
        } catch (URISyntaxException e) {
            throw new RssClientException(
                    RssClientException.Reason.HTTP_STATUS, from + " redirected to an invalid address", e);
        }
    }

    private static RssFeed parse(URI uri, Response response) {
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(response.body()), contentType, true)) {
            SyndFeed feed = input.build(reader);
            List<RssItem> items = new ArrayList<>();
            for (SyndEntry entry : feed.getEntries()) {
                items.add(item(entry));
            }
            return new RssFeed(feed.getTitle(), items);
        } catch (FeedException | IOException | IllegalArgumentException e) {
            throw new RssClientException(
                    RssClientException.Reason.NOT_A_FEED, uri + " did not return an RSS or Atom feed", e);
        }
    }

    private static RssItem item(SyndEntry entry) {
        List<String> categories = new ArrayList<>();
        for (SyndCategory category : entry.getCategories()) {
            addIfPresent(categories, category.getName());
            addIfPresent(categories, category.getTaxonomyUri());
        }
        String link = entry.getLink() != null ? entry.getLink() : webLink(entry.getUri());
        return new RssItem(entry.getTitle(), link, instant(entry), description(entry), categories);
    }

    private static Instant instant(SyndEntry entry) {
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return date == null ? null : date.toInstant();
    }

    private static String description(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description != null && description.getValue() != null) {
            return description.getValue();
        }
        return entry.getContents().stream()
                .map(SyndContent::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** An Atom id is often the article's address; a tag URI or a bare id is not one. */
    private static String webLink(String uri) {
        return uri != null && (uri.startsWith("http://") || uri.startsWith("https://")) ? uri : null;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.strip());
        }
    }

    /** Either clock ran out: the request's own timeout, or the bound on the whole exchange. */
    private RssClientException timedOut(URI uri, Throwable cause) {
        return new RssClientException(
                RssClientException.Reason.UNREACHABLE,
                uri + " did not answer within " + properties.readTimeout().toMillis() + " ms",
                cause);
    }

    private static RssClientException tooLarge(URI uri, long limit) {
        return new RssClientException(RssClientException.Reason.TOO_LARGE, uri + " sent more than " + limit + " bytes");
    }

    private record Response(int status, HttpHeaders headers, byte[] body) {}

    private record Fetched(URI uri, Response response) {}

    /** Thrown inside the body subscriber when the response passes the limit; reported as TOO_LARGE. */
    private static final class ResponseTooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** Collects the body and cancels the download as soon as it passes the limit, rather than after reading it all. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(long limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (body.size() + (long) buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                body.writeBytes(bytes);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(body.toByteArray());
        }

        @Override
        public CompletableFuture<byte[]> getBody() {
            return result;
        }
    }
}
