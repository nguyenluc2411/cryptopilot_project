package com.cryptopilot.admin.model;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Instant;

/**
 * The filters of the system log on SCR-44; {@code null} is no restriction. The period applies to when the failure was
 * last seen.
 *
 * <p>Rule: UC-46.
 */
public record SystemEventFilter(SystemEventCategory category, SystemEventSource source, Instant from, Instant until) {}
