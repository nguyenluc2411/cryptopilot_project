package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.PostMediaType;
import jakarta.validation.constraints.NotNull;

/**
 * Asks for a signed form to upload one file of a post (SCR-26).
 *
 * <p>Rule: BR-43, BR-44; D-98.
 *
 * @param mediaType IMAGE, or VIDEO on a plan with video posting
 */
public record UploadSignatureRequest(
        @NotNull(message = "MSG01") PostMediaType mediaType) {}
