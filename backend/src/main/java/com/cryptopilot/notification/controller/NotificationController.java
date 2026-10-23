package com.cryptopilot.notification.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.notification.dto.response.NotificationResponse;
import com.cryptopilot.notification.dto.response.UnreadCountResponse;
import com.cryptopilot.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification centre of SCR-15 and the bell in the header (SRS 3.4.5, UC-15), Trader only. The caller only ever
 * sees their own notifications; a new one is also pushed on {@code /user/queue/notifications}.
 *
 * <p>Rule: UC-15; SRS 3.4.5; TECHNICAL_DESIGN 8.
 */
@Tag(name = "Notifications", description = "The notification centre of SCR-15 and the bell (UC-15). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NotificationController {

    private final NotificationService notifications;

    @Operation(summary = "The caller's notifications, newest first, 20 per page by default (SCR-15)")
    @ApiResponse(responseCode = "200", description = "One page of notifications")
    @ApiResponse(responseCode = "400", description = "MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "Only the unread ones when true; default false")
                    @RequestParam(defaultValue = "false")
                    boolean unreadOnly,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return notifications.list(callerOf(caller), unreadOnly, page, pageSize);
    }

    @Operation(summary = "How many of the caller's notifications are unread: the number on the bell")
    @ApiResponse(responseCode = "200", description = "The unread count")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt caller) {
        return notifications.unreadCount(callerOf(caller));
    }

    @Operation(summary = "Mark one notification read, as opening it does")
    @ApiResponse(responseCode = "200", description = "The notification, read; already read keeps its first read time")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such notification of the caller")
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return notifications.markRead(callerOf(caller), id);
    }

    @Operation(summary = "Mark all the caller's notifications read")
    @ApiResponse(responseCode = "200", description = "The unread count, now zero")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping("/read-all")
    public UnreadCountResponse markAllRead(@AuthenticationPrincipal Jwt caller) {
        return notifications.markAllRead(callerOf(caller));
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
