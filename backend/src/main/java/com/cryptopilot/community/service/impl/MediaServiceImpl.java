package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.MediaStorageException;
import com.cryptopilot.community.client.UploadTicket;
import com.cryptopilot.community.dto.response.UploadSignatureResponse;
import com.cryptopilot.community.model.enums.PostMediaType;
import com.cryptopilot.community.service.MediaService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Signs upload forms under a fresh key in the caller's folder, so a form uploads one file the caller alone can attach,
 * and returns the limits of BR-44 with it for the form's own check before the upload.
 *
 * <p>Rule: BR-43, BR-44, BR-62; D-98.
 */
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final MediaStorage storage;
    private final MediaRules rules;

    @Override
    public UploadSignatureResponse signForTrader(UUID trader, PostMediaType type) {
        if (type == PostMediaType.VIDEO) {
            rules.requireVideoPosting(trader);
        }
        return sign(trader, type);
    }

    @Override
    public UploadSignatureResponse signForAdmin(UUID admin, PostMediaType type) {
        return sign(admin, type);
    }

    private UploadSignatureResponse sign(UUID owner, PostMediaType type) {
        UploadTicket ticket;
        try {
            ticket = storage.sign(type, rules.newPublicId(owner), MediaRules.formatsOf(type));
        } catch (MediaStorageException failed) {
            throw MediaRules.unavailable(failed);
        }
        return new UploadSignatureResponse(
                ticket.uploadUrl(),
                ticket.apiKey(),
                ticket.timestamp(),
                ticket.signature(),
                ticket.publicId(),
                ticket.allowedFormats(),
                ticket.overwrite(),
                type,
                rules.maxBytes(type),
                rules.maxDurationSeconds(type));
    }
}
