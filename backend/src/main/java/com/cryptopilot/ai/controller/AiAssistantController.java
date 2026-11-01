package com.cryptopilot.ai.controller;

import com.cryptopilot.ai.dto.request.AskQuestionRequest;
import com.cryptopilot.ai.dto.request.CreateConversationRequest;
import com.cryptopilot.ai.dto.request.RenameConversationRequest;
import com.cryptopilot.ai.dto.response.AskResponse;
import com.cryptopilot.ai.dto.response.ConversationResponse;
import com.cryptopilot.ai.dto.response.MessageResponse;
import com.cryptopilot.ai.dto.response.QuotaResponse;
import com.cryptopilot.ai.service.AiAssistantService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI Assistant of SCR-29 (UC-30, T-075). Trader. The answer to a question is pushed to {@code /user/queue/ai}.
 *
 * <p>Rule: UC-30, BR-50, BR-51, BR-52; SRS 3.9.1.
 */
@Tag(name = "AI Assistant", description = "Conversations, questions and the daily quota of SCR-29 (UC-30). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AiAssistantController {

    private final AiAssistantService assistant;

    @Operation(summary = "The caller's conversations, last asked first (SCR-29)")
    @ApiResponse(responseCode = "200", description = "One page, 20 per page by default; readable on every plan (BR-52)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/conversations")
    public PageResponse<ConversationResponse> conversations(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return assistant.conversations(callerOf(caller), page, pageSize);
    }

    @Operation(summary = "Start a conversation, optionally about a pair (SRS 3.9.1)")
    @ApiResponse(responseCode = "201", description = "The conversation")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no AI Assistant (FREE)")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not listed")
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse start(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateConversationRequest request) {
        return assistant.start(callerOf(caller), request);
    }

    @Operation(summary = "Rename a conversation (SRS 3.9.1)")
    @ApiResponse(responseCode = "200", description = "The conversation")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conversation of the caller")
    @PatchMapping("/conversations/{id}")
    public ConversationResponse rename(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody RenameConversationRequest request) {
        return assistant.rename(callerOf(caller), id, request);
    }

    @Operation(summary = "Delete a conversation (SRS 3.9.1); its questions of today still count (BR-50)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conversation of the caller")
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        assistant.delete(callerOf(caller), id);
    }

    @Operation(summary = "The messages of a conversation, newest first (SCR-29)")
    @ApiResponse(responseCode = "200", description = "One page, 50 per page by default; readable on every plan")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conversation of the caller")
    @GetMapping("/conversations/{id}/messages")
    public PageResponse<MessageResponse> messages(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return assistant.messages(callerOf(caller), id, page, pageSize);
    }

    @Operation(
            summary = "Ask a question with the context selector (UC-30, NSF-16)",
            description = "Stores the question PENDING and returns at once; the answer, or FAILED (MSG31) after an"
                    + " error or 60 seconds, is pushed to /user/queue/ai. A failed question uses no quota.")
    @ApiResponse(responseCode = "202", description = "The pending question and today's quota with it counted")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(
            responseCode = "403",
            description = "MSG29: the plan has no AI Assistant (FREE, BR-52), the performance summary needs PREMIUM,"
                    + " or Futures analysis is not in the plan")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conversation, plan or pair of the caller")
    @ApiResponse(responseCode = "429", description = "MSG30: today's AI quota is used up")
    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AskResponse ask(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody AskQuestionRequest request) {
        return assistant.ask(callerOf(caller), id, request);
    }

    @Operation(summary = "Today's AI quota of the caller (BR-50)")
    @ApiResponse(responseCode = "200", description = "Limit, used, remaining and when it resets (00:00 UTC+7)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/quota")
    public QuotaResponse quota(@AuthenticationPrincipal Jwt caller) {
        return assistant.quota(callerOf(caller));
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
