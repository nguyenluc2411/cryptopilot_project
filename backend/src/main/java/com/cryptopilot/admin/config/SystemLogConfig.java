package com.cryptopilot.admin.config;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.impl.ExternalCallTracker;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;

/**
 * Where the system log of UC-46 gets its failures from, besides the unexpected errors of requests
 * ({@code SystemLogListener}): the clients of the external services ({@link ExternalCallMonitoring}) and the
 * {@code @Scheduled} jobs, through the error handler of the scheduler Spring Boot builds. With virtual threads on, that
 * scheduler is a {@code SimpleAsyncTaskScheduler}, otherwise a {@code ThreadPoolTaskScheduler}; both customizers are
 * declared and Boot applies the one it needs.
 *
 * <p>Rule: UC-46; registration form, Admin System ("system errors").
 */
@Configuration(proxyBeanMethods = false)
public class SystemLogConfig {

    private static final Logger log = LoggerFactory.getLogger(SystemLogConfig.class);

    /** Static, as a post-processor must be: it is built before the other beans and reaches them only lazily. */
    @Bean
    static ExternalCallMonitoring externalCallMonitoring(
            ObjectProvider<SystemEventRecorder> recorder, ObjectProvider<ExternalCallTracker> tracker) {
        return new ExternalCallMonitoring(recorder, tracker);
    }

    @Bean
    SimpleAsyncTaskSchedulerCustomizer jobFailuresOnTheVirtualScheduler(ObjectProvider<SystemEventRecorder> recorder) {
        return scheduler -> scheduler.setErrorHandler(jobFailures(recorder));
    }

    @Bean
    ThreadPoolTaskSchedulerCustomizer jobFailuresOnThePooledScheduler(ObjectProvider<SystemEventRecorder> recorder) {
        return scheduler -> scheduler.setErrorHandler(jobFailures(recorder));
    }

    /**
     * Logs a job's failure as the scheduler's default handler does, keeps the job scheduled, and records the failure
     * with the job's class and method, the first frame of the application in the stack trace.
     */
    static ErrorHandler jobFailures(ObjectProvider<SystemEventRecorder> recorder) {
        return failure -> {
            log.error("Unexpected error occurred in scheduled task", failure);
            SystemEventRecorder systemLog = recorder.getIfAvailable();
            if (systemLog != null) {
                systemLog.record(
                        SystemEventCategory.SYSTEM_ERROR,
                        SystemEventSource.SCHEDULED_JOB,
                        jobOf(failure),
                        failure,
                        null);
            }
        };
    }

    /**
     * {@code Class.method} of the outermost application frame: the {@code @Scheduled} method the scheduler called, so
     * every failure of one job shares its name whatever it failed on.
     */
    static String jobOf(Throwable failure) {
        String job = "unknown";
        for (StackTraceElement frame : failure.getStackTrace()) {
            String type = frame.getClassName();
            if (type.startsWith("com.cryptopilot.") && !type.contains("$$")) {
                job = type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
        }
        return job;
    }
}
