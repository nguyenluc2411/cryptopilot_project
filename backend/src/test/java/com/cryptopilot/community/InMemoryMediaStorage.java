package com.cryptopilot.community;

import com.cryptopilot.community.client.CloudinarySignature;
import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.MediaStorageException;
import com.cryptopilot.community.client.StoredFilePage;
import com.cryptopilot.community.client.StoredMedia;
import com.cryptopilot.community.client.UploadTicket;
import com.cryptopilot.community.model.enums.PostMediaType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A media storage held in memory for the community API tests: a test "uploads" a file by storing what the storage
 * would report about it, and reads back which keys were destroyed. A listing names every file in one page, oldest
 * first, uploaded now unless {@link #uploadedAt(String, Instant)} says otherwise. Like a form signed with
 * {@code overwrite=false}, a second upload under a key keeps the first file. {@link #failing(boolean)} makes every call fail
 * as an unreachable storage does.
 */
public class InMemoryMediaStorage implements MediaStorage {

    private final Map<String, StoredMedia> files = new ConcurrentHashMap<>();
    private final Map<String, Instant> uploadedAt = new ConcurrentHashMap<>();
    private final List<String> destroyed = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public UploadTicket sign(PostMediaType type, String publicId, List<String> allowedFormats) {
        failIfAsked();
        return new UploadTicket(
                "https://upload.invalid/" + type.name().toLowerCase(),
                "test-key",
                1L,
                CloudinarySignature.sign(Map.of("public_id", publicId, "timestamp", "1"), "test-secret"),
                publicId,
                allowedFormats,
                false);
    }

    @Override
    public Optional<StoredMedia> describe(PostMediaType type, String publicId) {
        failIfAsked();
        return Optional.ofNullable(files.get(publicId)).filter(file -> file.type() == type);
    }

    @Override
    public void destroy(PostMediaType type, String publicId) {
        failIfAsked();
        files.remove(publicId);
        uploadedAt.remove(publicId);
        destroyed.add(publicId);
    }

    @Override
    public StoredFilePage list(PostMediaType type, String prefix, String cursor) {
        failIfAsked();
        List<StoredFilePage.File> listed = files.values().stream()
                .filter(file -> file.type() == type && file.publicId().startsWith(prefix + "/"))
                .map(file -> new StoredFilePage.File(file.publicId(), uploadedAt(file.publicId())))
                .sorted(Comparator.comparing(StoredFilePage.File::createdAt))
                .toList();
        return new StoredFilePage(listed, null);
    }

    /** Makes a stored file look uploaded at this instant; a file not given one was uploaded now. */
    public void uploadedAt(String publicId, Instant when) {
        uploadedAt.put(publicId, when);
    }

    private Instant uploadedAt(String publicId) {
        return uploadedAt.computeIfAbsent(publicId, key -> Instant.now());
    }

    /** Stores an image as the browser's upload would. */
    public void uploadImage(String publicId, String format, long bytes) {
        files.putIfAbsent(
                publicId,
                new StoredMedia(
                        publicId,
                        PostMediaType.IMAGE,
                        format,
                        bytes,
                        640,
                        480,
                        null,
                        "https://res.invalid/" + publicId + "." + format,
                        null));
    }

    /** Stores a video as the browser's upload would. */
    public void uploadVideo(String publicId, String format, long bytes, int seconds) {
        files.putIfAbsent(
                publicId,
                new StoredMedia(
                        publicId,
                        PostMediaType.VIDEO,
                        format,
                        bytes,
                        1280,
                        720,
                        seconds,
                        "https://res.invalid/" + publicId + "." + format,
                        "https://res.invalid/" + publicId + ".jpg"));
    }

    public List<String> destroyed() {
        return List.copyOf(destroyed);
    }

    public void failing(boolean fail) {
        failing.set(fail);
    }

    public void clear() {
        files.clear();
        uploadedAt.clear();
        destroyed.clear();
        failing.set(false);
    }

    private void failIfAsked() {
        if (failing.get()) {
            throw new MediaStorageException("the test storage is down");
        }
    }
}
