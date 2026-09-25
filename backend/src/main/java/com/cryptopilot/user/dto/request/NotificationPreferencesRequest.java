package com.cryptopilot.user.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * What the Notifications tab of SCR-07 saves: one switch per channel (SRS UC-08, section 3.2.5).
 *
 * <p>There is no switch for in-app notifications, because SRS 3.2.5 says they are always on. Both
 * switches are required and boxed, so that a body that forgot one is refused with MSG01 instead of
 * quietly turning that channel off.
 *
 * <p>Rule: SRS UC-08, section 3.2.5; message MSG01.
 *
 * @param email whether notifications also go out by mail
 * @param push whether notifications also go out as push messages to the registered devices
 */
public record NotificationPreferencesRequest(
        @NotNull(message = "MSG01") Boolean email,
        @NotNull(message = "MSG01") Boolean push) {}
