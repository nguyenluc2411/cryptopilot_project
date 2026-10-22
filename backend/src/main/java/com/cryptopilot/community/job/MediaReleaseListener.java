package com.cryptopilot.community.job;

import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.event.PostMediaReleased;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Deletes released post files from the media storage (SRS 3.7.2, "Media Deletion Request"). The event reaches this
 * class through the event publication registry, after the post change committed; a failure of the storage propagates,
 * so the outbox processor delivers the event again and a file already deleted is not an error the second time.
 *
 * <p>Rule: BR-47; NSF-15; D-98.
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (Transactional Outbox).
 */
@Component
public class MediaReleaseListener {

    private final MediaStorage storage;

    public MediaReleaseListener(MediaStorage storage) {
        this.storage = storage;
    }

    // No database work, so no transaction: no pooled connection is held while the storage answers.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(PostMediaReleased released) {
        for (PostMediaReleased.File file : released.files()) {
            storage.destroy(file.type(), file.publicId());
        }
    }
}
