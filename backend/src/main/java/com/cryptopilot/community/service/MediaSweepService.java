package com.cryptopilot.community.service;

/**
 * Deletes the files of the media storage that no post carries: uploaded with a signed form and never attached, or
 * left behind by a deletion that failed for good. Implemented by
 * {@link com.cryptopilot.community.service.impl.MediaSweepServiceImpl}.
 *
 * <p>Rule: BR-44, BR-47; D-98.
 */
public interface MediaSweepService {

    /**
     * Deletes the files older than the grace period that no post other than a deleted one carries.
     *
     * @return how many files were deleted
     * @throws com.cryptopilot.community.client.MediaStorageException when the storage cannot be listed; the files
     *     deleted before the failure stay deleted
     */
    int removeUnattached();
}
