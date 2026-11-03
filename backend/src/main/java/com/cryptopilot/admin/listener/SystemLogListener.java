package com.cryptopilot.admin.listener;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import com.cryptopilot.common.exception.UnexpectedFailure;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records in the system log every request that ended in an unexpected error, with the reference its caller received,
 * so an administrator can match a user's "error reference" to the failure. The recorder hands it to its writer thread
 * and returns at once, so the 500 is never delayed; it never throws.
 *
 * <p>Rule: UC-46; registration form ("The system logs important actions and system errors").
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemLogListener {

    private final SystemEventRecorder recorder;

    @EventListener
    void on(UnexpectedFailure failure) {
        recorder.record(
                SystemEventCategory.SYSTEM_ERROR,
                SystemEventSource.APPLICATION,
                failure.operation(),
                failure.error(),
                failure.reference());
    }
}
