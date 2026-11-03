package com.cryptopilot.admin.config;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.impl.ExternalCallTracker;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import com.cryptopilot.common.exception.ExternalAnswer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Watches the clients of the external services without touching them: each client bean is wrapped in a proxy that
 * notes every answer in {@link ExternalCallTracker} and writes every failure to the system log. The clients are found
 * by class name, so this module depends on none of the modules that own them, and a client that is renamed is merely
 * no longer watched (the health panel then reads UNKNOWN), which {@code ExternalCallMonitoringTest} catches.
 *
 * <p>A failure is what the client throws to its caller, after its own retries: a Binance call that succeeded on the
 * second try is a success. An {@link IllegalArgumentException}, {@link IllegalStateException} or
 * {@link NullPointerException} is the caller's mistake, not the service's, and is not recorded. Only public methods
 * declared by the client are watched, and of those not the ones in {@link #LOCAL_METHODS}, so {@code close},
 * {@code toString} and the like do not count as calls to the service.
 *
 * <p>A failure that is an {@link ExternalAnswer} the service answered (a 4xx refusal of this request) counts as an
 * answer and is not recorded: the service is up. RSS, Cloudinary and SMTP do not say so, so their refusals stay
 * failures.
 *
 * <p>Rule: UC-46; registration form, Admin System ("external API failures"). Reference: Fielding, R. &amp; Reschke, J.,
 * RFC 9110, §15.5 (4xx: client error, the server answered).
 */
public class ExternalCallMonitoring implements BeanPostProcessor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallMonitoring.class);

    /** The client classes, by name, and the service each one calls. */
    static final Map<String, SystemEventSource> CLIENTS = Map.of(
            "com.cryptopilot.market.client.BinanceRestClient", SystemEventSource.BINANCE_REST,
            "com.cryptopilot.ai.client.AiServiceClient", SystemEventSource.AI_SERVICE,
            "com.cryptopilot.community.client.CloudinaryClient", SystemEventSource.MEDIA_STORAGE,
            "com.cryptopilot.news.client.RssClient", SystemEventSource.NEWS_SOURCE,
            "com.cryptopilot.notification.client.SmtpEmailSender", SystemEventSource.MAIL);

    /**
     * Public methods of the clients that answer without calling the service: closing, the request weight Binance
     * allows, and signing an upload, which Cloudinary checks only when the browser uploads.
     */
    static final Set<String> LOCAL_METHODS = Set.of("close", "usedWeightThisMinute", "weightPerMinute", "sign");

    private final Map<String, SystemEventSource> clients;
    private final ObjectProvider<SystemEventRecorder> recorder;
    private final ObjectProvider<ExternalCallTracker> tracker;
    private final Set<String> wrapped = ConcurrentHashMap.newKeySet();

    ExternalCallMonitoring(ObjectProvider<SystemEventRecorder> recorder, ObjectProvider<ExternalCallTracker> tracker) {
        this(CLIENTS, recorder, tracker);
    }

    /** With other clients, for a test. */
    ExternalCallMonitoring(
            Map<String, SystemEventSource> clients,
            ObjectProvider<SystemEventRecorder> recorder,
            ObjectProvider<ExternalCallTracker> tracker) {
        this.clients = Map.copyOf(clients);
        this.recorder = recorder;
        this.tracker = tracker;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        SystemEventSource source = clients.get(type.getName());
        if (source == null || Modifier.isFinal(type.getModifiers())) {
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new Watch(source, type, recorder, tracker));
        wrapped.add(type.getName());
        return proxy.getProxy(type.getClassLoader());
    }

    /** Says which clients are not watched: renamed (WARN, a defect) or simply not created (DEBUG, disabled). */
    @Override
    public void afterSingletonsInstantiated() {
        clients.keySet().stream()
                .filter(name -> !wrapped.contains(name))
                .sorted()
                .forEach(name -> {
                    if (loadable(name)) {
                        log.debug("UC-46 {} is not created, so {} is not watched", name, clients.get(name));
                    } else {
                        log.warn(
                                "UC-46 {} no longer exists, so {} is not watched; update CLIENTS",
                                name,
                                clients.get(name));
                    }
                });
    }

    /** The client names no bean matched so far. */
    Set<String> unwatched() {
        Set<String> missing = new TreeSet<>(clients.keySet());
        missing.removeAll(wrapped);
        return missing;
    }

    private static boolean loadable(String name) {
        try {
            Class.forName(name, false, ExternalCallMonitoring.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError renamed) {
            return false;
        }
    }

    /** Notes the outcome of each call to one client. */
    static final class Watch implements MethodInterceptor {

        private final SystemEventSource source;
        private final Class<?> client;
        private final ObjectProvider<SystemEventRecorder> recorder;
        private final ObjectProvider<ExternalCallTracker> tracker;

        Watch(
                SystemEventSource source,
                Class<?> client,
                ObjectProvider<SystemEventRecorder> recorder,
                ObjectProvider<ExternalCallTracker> tracker) {
            this.source = source;
            this.client = client;
            this.recorder = recorder;
            this.tracker = tracker;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            if (!watched(method)) {
                return invocation.proceed();
            }
            Object result;
            try {
                result = invocation.proceed();
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException misuse) {
                throw misuse;
            } catch (RuntimeException failure) {
                if (failure instanceof ExternalAnswer answer && answer.serviceAnswered()) {
                    answered();
                    throw failure;
                }
                SystemEventRecorder log = recorder.getIfAvailable();
                if (log != null) {
                    log.record(
                            SystemEventCategory.EXTERNAL_API_FAILURE,
                            source,
                            client.getSimpleName() + "." + method.getName(),
                            failure,
                            null);
                }
                throw failure;
            }
            answered();
            return result;
        }

        private void answered() {
            ExternalCallTracker calls = tracker.getIfAvailable();
            if (calls != null) {
                calls.succeeded(source);
            }
        }

        private boolean watched(Method method) {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            if (method.getDeclaringClass() == Object.class) {
                return false;
            }
            if (LOCAL_METHODS.contains(method.getName())) {
                return false;
            }
            // Declared by the client itself or by the port it implements (MediaStorage, EmailSender).
            return method.getDeclaringClass().isAssignableFrom(client);
        }
    }
}
