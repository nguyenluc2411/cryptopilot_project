package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.PostMediaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A file the browser uploaded with a form from {@code /media/upload-signature}, named for a post in the order it is
 * shown. Only the key travels: the address, size and format are read back from the media storage.
 *
 * <p>Rule: BR-44; D-98.
 *
 * @param publicId the {@code publicId} of the signed form
 * @param mediaType IMAGE or VIDEO, as signed
 */
public record MediaRequest(
        @NotBlank(message = "MSG01") @Size(max = 255, message = "MSG01")
        String publicId,

        @NotNull(message = "MSG01") PostMediaType mediaType) {}
