package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.dto.request.LatencySamplesRequest;
import com.cryptopilot.market.dto.response.LatencyReportResponse;
import com.cryptopilot.market.service.LatencyService;
import com.cryptopilot.market.service.TopicSubscriptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The realtime latency of TR-36. The clients' samples are timers of their own ({@code cryptopilot.client.latency}),
 * beside the server's ({@code cryptopilot.market.push.latency}, {@code cryptopilot.paper.fill.latency}), so the
 * report shows where the time goes: up to the push, or between the push and the screen. The figures are this
 * instance's since it started; the percentiles are Micrometer's, over its recent window.
 *
 * <p>Rule: TR-36; TR-34; SRS 4.2.3.
 */
@Service
@RequiredArgsConstructor
public class LatencyServiceImpl implements LatencyService {

    /** The client samples' timer (TR-36). */
    public static final String CLIENT_LATENCY = "cryptopilot.client.latency";

    /** The timers the report reads. */
    static final Set<String> TIMERS =
            Set.of(MarketBroadcastServiceImpl.PUSH_LATENCY, "cryptopilot.paper.fill.latency", CLIENT_LATENCY);

    private static final int TOP_TOPICS = 20;

    private final MeterRegistry meters;
    private final TopicSubscriptionService subscriptions;
    private final Clock clock;

    @Override
    public void record(LatencySamplesRequest samples) {
        for (LatencySamplesRequest.Sample sample : samples.samples()) {
            Timer.builder(CLIENT_LATENCY)
                    .description("From the exchange producing the data to the client's screen, as it measured (TR-36)")
                    .tag("kind", sample.kind().name().toLowerCase(Locale.ROOT))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meters)
                    .record(Duration.ofMillis(sample.millis()));
        }
    }

    @Override
    public LatencyReportResponse report() {
        List<LatencyReportResponse.Series> series = meters.getMeters().stream()
                .filter(meter ->
                        meter instanceof Timer && TIMERS.contains(meter.getId().getName()))
                .map(meter -> series((Timer) meter))
                .sorted(Comparator.comparing(LatencyReportResponse.Series::name)
                        .thenComparing(s -> s.tags().toString()))
                .toList();
        Map<String, Integer> counts = subscriptions.counts();
        Map<String, Integer> top = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_TOPICS)
                .forEach(entry -> top.put(entry.getKey(), entry.getValue()));
        return new LatencyReportResponse(clock.instant(), series, counts.size(), top);
    }

    private static LatencyReportResponse.Series series(Timer timer) {
        HistogramSnapshot snapshot = timer.takeSnapshot();
        Map<String, String> tags = new TreeMap<>();
        for (Tag tag : timer.getId().getTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        return new LatencyReportResponse.Series(
                timer.getId().getName(),
                tags,
                snapshot.count(),
                percentile(snapshot, 0.5),
                percentile(snapshot, 0.95),
                percentile(snapshot, 0.99),
                snapshot.max(TimeUnit.MILLISECONDS));
    }

    private static double percentile(HistogramSnapshot snapshot, double wanted) {
        for (ValueAtPercentile value : snapshot.percentileValues()) {
            if (Math.abs(value.percentile() - wanted) < 1e-9) {
                return value.value(TimeUnit.MILLISECONDS);
            }
        }
        return 0;
    }
}
