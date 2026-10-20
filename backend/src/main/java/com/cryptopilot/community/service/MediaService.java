package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.response.UploadSignatureResponse;
import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.UUID;

/**
 * Signs the forms with which a browser uploads the files of a post straight to the media storage (D-98). Implemented
 * by {@link com.cryptopilot.community.service.impl.MediaServiceImpl}.
 *
 * <p>Rule: BR-43, BR-44, BR-62 ({@code VIDEO_POST}); D-48.
 */
public interface MediaService {

    /**
     * A form for one file of a Trader's post; a video needs a plan with video posting (MSG29).
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED} or
     *     {@code MEDIA_STORAGE_UNAVAILABLE}
     */
    UploadSignatureResponse signForTrader(UUID trader, PostMediaType type);

    /** A form for one file of an announcement (UC-44); an administrator has no plan to check. */
    UploadSignatureResponse signForAdmin(UUID admin, PostMediaType type);
}
