package com.cryptopilot.community.client;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One page of the files the media storage holds under a folder, oldest first.
 *
 * @param files the files of this page
 * @param nextCursor what to pass for the next page, or {@code null} on the last one
 */
public record StoredFilePage(List<File> files, String nextCursor) {

    public StoredFilePage {
        files = List.copyOf(files);
    }

    /** A page with no files and no next page. */
    public static StoredFilePage empty() {
        return new StoredFilePage(List.of(), null);
    }

    /**
     * A stored file as a listing names it.
     *
     * @param publicId the key it is stored under
     * @param createdAt when it was uploaded
     */
    public record File(String publicId, Instant createdAt) {

        public File {
            Objects.requireNonNull(publicId, "publicId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
