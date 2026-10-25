package com.cryptopilot.academy.client;

import java.util.Optional;

/**
 * Where the cover photos of Academy lessons are kept. The administrator's browser uploads a photo straight to the
 * storage with a form this port signs, so the secret never leaves the backend; the lesson then names the photo by its
 * address.
 *
 * <p>Rule: T-114.
 */
public interface CoverStorage {

    /** Signs an upload of one new cover photo; refused with MEDIA_STORAGE_UNAVAILABLE when no storage is set. */
    CoverUploadTicket sign();

    /**
     * The address a lesson stores for a cover: a photo of this storage's cover folder, rewritten to be delivered cropped
     * to the cover's size; empty when the address is not such a photo. Without a storage every https address is kept
     * as it is.
     */
    Optional<String> accept(String coverImageUrl);
}
