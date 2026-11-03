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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A watched client's answers and failures reach the tracker and the system log; the caller's own mistakes, local
 * methods and other beans are left alone; and every client the module watches still exists under its name.
 *
 * <p>Rule: UC-46.
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
