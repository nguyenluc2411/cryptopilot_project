package com.cryptopilot.community.service.impl;

import com.cryptopilot.community.client.CloudinaryProperties;
import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.MediaStorageException;
import com.cryptopilot.community.client.StoredFilePage;
import com.cryptopilot.community.config.MediaSweepProperties;
import com.cryptopilot.community.model.enums.PostMediaType;
import com.cryptopilot.community.repository.PostMediaRepository;
import com.cryptopilot.community.service.MediaSweepService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The sweep of unattached files. Every file of a post is stored under the configured folder, so the folder is listed
 * page by page for each media type, oldest first, until the files reach the grace period or the page limit; the files
 * older than the grace period are checked against the posts in one query per page, and those no live post carries
 * are deleted one by one. Each run starts again from the oldest file: deleted files no longer appear, so a backlog
 * shrinks run by run, but once the posts carry more old files than the page limit lists (20 pages of 500 by default),
 * the files after them are not reached until {@code max-pages} is raised.
 *
 * <p>No transaction is held while the storage answers: each page's query is a short read of its own. A file attached
 * in the instant between that read and its deletion would be lost, which the grace period makes practically
 * impossible: a form expires an hour after it is signed, and a post is published minutes after its upload, not a day.
 * A file that cannot be deleted is logged and left for the next run.
 *
 * <p>Rule: BR-44, BR-47; D-98.
 */
@Service
@RequiredArgsConstructor
public class MediaSweepServiceImpl implements MediaSweepService {

    private static final Logger log = LoggerFactory.getLogger(MediaSweepServiceImpl.class);

    private final MediaStorage storage;
    private final PostMediaRepository media;
    private final CloudinaryProperties cloudinary;
    private final MediaSweepProperties properties;
    private final Clock clock;

    @Override
    public int removeUnattached() {
        Instant cutoff = clock.instant().minus(properties.grace());
        int deleted = 0;
        for (PostMediaType type : PostMediaType.values()) {
            deleted += sweep(type, cutoff);
        }
        return deleted;
    }

    private int sweep(PostMediaType type, Instant cutoff) {
        int deleted = 0;
        String cursor = null;
        int pages = 0;
        boolean reachedRecent;
        do {
            StoredFilePage page = storage.list(type, cloudinary.folder(), cursor);
            deleted += removeUnattached(type, page.files(), cutoff);
            // Oldest first: once a page ends inside the grace period, every later file is younger still.
            reachedRecent = !page.files().isEmpty()
                    && !page.files().getLast().createdAt().isBefore(cutoff);
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && !reachedRecent && pages < properties.maxPages());
        if (cursor != null && !reachedRecent) {
            log.info("Media sweep of {} files stopped after {} pages; the rest waits for the next runs", type, pages);
        }
        return deleted;
    }

    private int removeUnattached(PostMediaType type, List<StoredFilePage.File> files, Instant cutoff) {
        List<String> old = files.stream()
                .filter(file -> file.createdAt().isBefore(cutoff))
                .map(StoredFilePage.File::publicId)
                .toList();
        if (old.isEmpty()) {
            return 0;
        }
        Set<String> carried = new HashSet<>(media.keysStillCarried(old));
        int deleted = 0;
        for (String publicId : old) {
            if (carried.contains(publicId)) {
                continue;
            }
            try {
                storage.destroy(type, publicId);
                deleted++;
            } catch (MediaStorageException failed) {
                log.warn("Media sweep could not delete {} {}: {}", type, publicId, failed.getMessage());
            }
        }
        return deleted;
    }
}
