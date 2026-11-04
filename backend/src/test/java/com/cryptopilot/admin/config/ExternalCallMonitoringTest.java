package com.cryptopilot.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.impl.ExternalCallTracker;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.common.exception.ExternalAnswer;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.model.enums.BinanceVenue;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A watched client's answers and failures reach the tracker and the system log; the caller's own mistakes, local
 * methods and other beans are left alone; and every client the module watches still exists under its name.
 *
 * <p>Rule: UC-52.
 */
class ExternalCallMonitoringTest {

    /** A stand-in for a client of an external service. */
    public static class FakeClient {

        public String fetch() {
            return "ok";
        }

        public String fail() {
            throw new RuntimeException("service down");
        }

        public String misuse() {
            throw new IllegalArgumentException("symbol must not be blank");
        }

        public String sign() {
            return "signed";
        }

        /** Throws what the test hands it. */
        public String refuse(RuntimeException refusal) {
            throw refusal;
        }
    }

    /** A refusal that says whether the service answered, as {@code BinanceClientException} does. */
    static final class Refusal extends RuntimeException implements ExternalAnswer {

        private static final long serialVersionUID = 1L;

        private final boolean answered;

        Refusal(String message, boolean answered) {
            super(message);
            this.answered = answered;
        }

        @Override
        public boolean serviceAnswered() {
            return answered;
        }
    }

    private final SystemEventRecorder recorder = mock(SystemEventRecorder.class);
    private final ExternalCallTracker tracker = mock(ExternalCallTracker.class);
    private FakeClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void watch() {
        ObjectProvider<SystemEventRecorder> recorders = mock(ObjectProvider.class);
        ObjectProvider<ExternalCallTracker> trackers = mock(ObjectProvider.class);
        when(recorders.getIfAvailable()).thenReturn(recorder);
        when(trackers.getIfAvailable()).thenReturn(tracker);
        ExternalCallMonitoring monitoring = new ExternalCallMonitoring(
                Map.of(FakeClient.class.getName(), SystemEventSource.AI_SERVICE), recorders, trackers);
        client = (FakeClient) monitoring.postProcessAfterInitialization(new FakeClient(), "fake");
    }

    @Test
    void anAnswer_isNoted_andAFailure_isRecordedAndRethrown() {
        assertThat(AopUtils.isAopProxy(client)).isTrue();
        assertThat(client.fetch()).isEqualTo("ok");
        verify(tracker).succeeded(SystemEventSource.AI_SERVICE);

        assertThatThrownBy(client::fail).hasMessage("service down");
        verify(recorder)
                .record(
                        eq(SystemEventCategory.EXTERNAL_API_FAILURE),
                        eq(SystemEventSource.AI_SERVICE),
                        eq("FakeClient.fail"),
                        any(RuntimeException.class),
                        isNull());
    }

    /** UC-52, RFC 9110 15.5: a 4xx refusal is the service answering; a 429 or no answer is a failure. */
    @Test
    void aFourHundredRefusal_isAnAnswer_whileARateLimitIsAFailure() {
        Refusal badRequest = new Refusal("BINANCE_SPOT /api/v3/klines answered 400", true);
        assertThatThrownBy(() -> client.refuse(badRequest)).isSameAs(badRequest);
        verify(tracker).succeeded(SystemEventSource.AI_SERVICE);
        verifyNoInteractions(recorder);

        Refusal rateLimited = new Refusal("BINANCE_SPOT /api/v3/klines answered 429", false);
        assertThatThrownBy(() -> client.refuse(rateLimited)).isSameAs(rateLimited);
        verify(recorder)
                .record(
                        eq(SystemEventCategory.EXTERNAL_API_FAILURE),
                        eq(SystemEventSource.AI_SERVICE),
                        eq("FakeClient.refuse"),
                        eq(rateLimited),
                        isNull());
    }

    /** Binance answers 403 when its WAF blocks this server: every call fails, so it is a failure, not an answer. */
    @Test
    void aBinanceForbidden_isAFailure_notAnAnswer() throws Exception {
        java.lang.reflect.Constructor<BinanceClientException> create =
                BinanceClientException.class.getDeclaredConstructor(
                        BinanceClientException.Kind.class,
                        BinanceVenue.class,
                        String.class,
                        java.time.Instant.class,
                        Throwable.class,
                        int.class);
        create.setAccessible(true);
        BinanceClientException forbidden = create.newInstance(
                BinanceClientException.Kind.REJECTED,
                BinanceVenue.SPOT,
                "SPOT /api/v3/klines answered 403",
                null,
                null,
                403);

        assertThatThrownBy(() -> client.refuse(forbidden)).isSameAs(forbidden);
        verify(recorder)
                .record(
                        eq(SystemEventCategory.EXTERNAL_API_FAILURE),
                        eq(SystemEventSource.AI_SERVICE),
                        eq("FakeClient.refuse"),
                        eq(forbidden),
                        isNull());
        verify(tracker, never()).succeeded(any());
    }

    @Test
    void theAiServiceRefusingNotToRetry_isAnAnswer_whileUnreachableIsAFailure() {
        AiServiceException refused = new AiServiceException("BAD_REQUEST", false, "no text", null, 400);
        assertThatThrownBy(() -> client.refuse(refused)).isSameAs(refused);
        verify(tracker).succeeded(SystemEventSource.AI_SERVICE);
        verifyNoInteractions(recorder);

        AiServiceException down = new AiServiceException(AiServiceException.UNREACHABLE, true, "connection refused");
        assertThatThrownBy(() -> client.refuse(down)).isSameAs(down);
        verify(recorder)
                .record(
                        eq(SystemEventCategory.EXTERNAL_API_FAILURE),
                        eq(SystemEventSource.AI_SERVICE),
                        eq("FakeClient.refuse"),
                        eq(down),
                        isNull());
    }

    @Test
    void theClientsNoBeanMatched_areReportedAfterStartup() {
        ExternalCallMonitoring monitoring = new ExternalCallMonitoring(
                Map.of(
                        FakeClient.class.getName(),
                        SystemEventSource.AI_SERVICE,
                        "com.cryptopilot.gone.RenamedClient",
                        SystemEventSource.MAIL,
                        ExternalCallMonitoringTest.class.getName(),
                        SystemEventSource.NEWS_SOURCE),
                mockProvider(),
                mockProvider());
        monitoring.postProcessAfterInitialization(new FakeClient(), "fake");

        assertThatCode(monitoring::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(monitoring.unwatched())
                .containsExactly(ExternalCallMonitoringTest.class.getName(), "com.cryptopilot.gone.RenamedClient");
    }

    @Test
    void theCallersMistake_andALocalMethod_areNotCalls() {
        assertThatThrownBy(client::misuse).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.sign()).isEqualTo("signed");
        verifyNoInteractions(recorder);
        verify(tracker, never()).succeeded(any());
    }

    @Test
    void anotherBean_isNotWrapped() {
        Object other = new Object();
        ExternalCallMonitoring monitoring = new ExternalCallMonitoring(mockProvider(), mockProvider());
        assertThat(monitoring.postProcessAfterInitialization(other, "other")).isSameAs(other);
    }

    @Test
    void everyWatchedClient_stillExistsUnderItsName() {
        ExternalCallMonitoring.CLIENTS.keySet().forEach(name -> assertThatCode(() -> Class.forName(name))
                .as(name + " was renamed: the health panel would read UNKNOWN for it")
                .doesNotThrowAnyException());
        assertThat(ExternalCallMonitoring.CLIENTS.values())
                .containsExactlyInAnyOrder(
                        SystemEventSource.BINANCE_REST,
                        SystemEventSource.AI_SERVICE,
                        SystemEventSource.MEDIA_STORAGE,
                        SystemEventSource.NEWS_SOURCE,
                        SystemEventSource.MAIL);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
        return mock(ObjectProvider.class);
    }
}
