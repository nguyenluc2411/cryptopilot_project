package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.entity.PostMedia;
import com.cryptopilot.community.event.PostMediaReleased;
import com.cryptopilot.community.model.CheckedMedia;
import com.cryptopilot.community.model.enums.PostMediaType;
import com.cryptopilot.community.model.enums.PostType;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostMediaRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Writes a post together with its files, for a Trader's posts and an administrator's announcements alike.
 *
 * <p>The files are checked against the media storage before the transaction opens, so no database connection is held
 * while the storage answers; the transaction then writes the post and its file rows. Files a post no longer carries —
 * all of them when it is deleted, the ones an edit leaves out — are released with {@link PostMediaReleased} inside that
 * transaction and deleted from the storage after it commits (SRS 3.7.2).
 *
 * <p>Rule: UC-27, UC-28, UC-29, UC-44; BR-43, BR-44, BR-47; D-98.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PostWriter {

    private final ForumPostRepository posts;
    private final PostMediaRepository media;
    private final PostAssembler assembler;
    private final MediaRules rules;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;

    /**
     * Publishes a post of {@code author}: a Trader's, or an announcement when {@code official}.
     *
     * @throws com.cryptopilot.common.exception.BusinessException MSG01 per field, MSG32, MSG29 or
     *     {@code MEDIA_STORAGE_UNAVAILABLE}
     */
    PostDetailResponse create(UUID author, PostRequest request, boolean official) {
        Set<UUID> coins = assembler.coinTags(request.coins());
        List<CheckedMedia> files = rules.check(author, request.media(), Map.of(), !official);
        return transactions.execute(status -> {
            ForumPost post = official
                    ? ForumPost.announce(author, request.title(), request.content(), request.marketType(), coins)
                    : ForumPost.publish(author, request.title(), request.content(), request.marketType(), coins);
            post.carry(typeOf(files));
            posts.save(post);
            write(post, files, Map.of());
            flush(files);
            return assembler.detail(post, author);
        });
    }

    /**
     * Replaces the content and files of the post {@code lookup} finds and that is not deleted; MSG41 otherwise.
     *
     * @param caller who edits it, in whose folder new files must be
     * @param trader whether the caller is a Trader, whose new videos need video posting
     */
    PostDetailResponse update(
            UUID caller, UUID postId, PostRequest request, Function<UUID, Optional<ForumPost>> lookup, boolean trader) {
        ForumPost found = editable(postId, lookup);
        Set<UUID> coins = assembler.coinTags(request.coins());
        List<CheckedMedia> files = rules.check(caller, request.media(), byKey(found.getId()), trader);
        return transactions.execute(status -> {
            ForumPost post = editable(postId, lookup);
            post.revise(request.title(), request.content(), request.marketType(), coins);
            post.carry(typeOf(files));
            write(post, files, byKey(post.getId()));
            flush(files);
            return assembler.detail(post, caller);
        });
    }

    /** Deletes the post {@code lookup} finds and that is not deleted (BR-47), releasing its files; MSG41 otherwise. */
    void delete(UUID postId, Function<UUID, Optional<ForumPost>> lookup) {
        transactions.executeWithoutResult(status -> {
            ForumPost post = editable(postId, lookup);
            post.delete();
            release(post.getId(), media.findAllByPostIdOrderBySortOrderAsc(post.getId()));
        });
    }

    private void write(ForumPost post, List<CheckedMedia> files, Map<String, PostMedia> current) {
        Set<String> named = new HashSet<>();
        for (int position = 0; position < files.size(); position++) {
            CheckedMedia file = files.get(position);
            named.add(file.publicId());
            if (file.kept()) {
                PostMedia row = current.get(file.publicId());
                if (row == null) {
                    // An edit of the same post removed it between the check and this transaction.
                    throw MediaRules.invalid("file " + file.publicId() + " is no longer on the post", file.publicId());
                }
                row.moveTo(position);
            } else {
                media.save(PostMedia.attach(
                        post.getId(),
                        file.type(),
                        file.publicId(),
                        file.upload().url(),
                        file.upload().thumbnailUrl(),
                        file.upload().bytes(),
                        file.upload().durationSeconds(),
                        file.upload().width(),
                        file.upload().height(),
                        position));
            }
        }
        List<PostMedia> dropped = current.values().stream()
                .filter(Predicate.not(row -> named.contains(row.getStoragePublicId())))
                .toList();
        dropped.forEach(media::delete);
        release(post.getId(), dropped);
    }

    /**
     * Writes the post and its file rows. Two posts that name the same new file at the same moment both pass the check;
     * {@code uq_post_media_storage_public_id} lets one attach it, and the other is refused like any file already
     * attached (MSG32).
     */
    private void flush(List<CheckedMedia> files) {
        try {
            posts.flush();
        } catch (DataIntegrityViolationException raced) {
            String keys = files.stream().map(CheckedMedia::publicId).collect(Collectors.joining(", "));
            throw MediaRules.invalid("a file of " + keys + " was attached to another post meanwhile", keys);
        }
    }

    private void release(UUID postId, List<PostMedia> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<PostMediaReleased.File> files = new ArrayList<>();
        for (PostMedia row : rows) {
            files.add(new PostMediaReleased.File(row.getMediaType(), row.getStoragePublicId()));
        }
        events.publishEvent(new PostMediaReleased(postId, files));
    }

    private Map<String, PostMedia> byKey(UUID postId) {
        return media.findAllByPostIdOrderBySortOrderAsc(postId).stream()
                .collect(Collectors.toMap(PostMedia::getStoragePublicId, Function.identity()));
    }

    private static ForumPost editable(UUID postId, Function<UUID, Optional<ForumPost>> lookup) {
        return lookup.apply(postId)
                .filter(post -> !post.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    private static PostType typeOf(List<CheckedMedia> files) {
        return files.stream().anyMatch(file -> file.type() == PostMediaType.VIDEO) ? PostType.VIDEO : PostType.TEXT;
    }
}
