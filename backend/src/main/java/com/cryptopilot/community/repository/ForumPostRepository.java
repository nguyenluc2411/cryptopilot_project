package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.ForumPost;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the post rows. The feeds are read by {@link PostFeedRepository}, which answers keys in feed order;
 * this one loads and writes the posts themselves.
 *
 * <p>Rule: UC-26, UC-27, UC-29; BR-47.
 */
public interface ForumPostRepository extends Repository<ForumPost, UUID> {

    @Transactional(readOnly = true)
    Optional<ForumPost> findById(UUID postId);

    /** The author's post with this key, or empty when there is none or another account wrote it. */
    @Transactional(readOnly = true)
    Optional<ForumPost> findByIdAndAuthorId(UUID postId, UUID authorId);

    /** The official post with this key, or empty (UC-44). */
    @Transactional(readOnly = true)
    Optional<ForumPost> findByIdAndOfficialTrue(UUID postId);

    /** The stored posts among these keys, in no particular order. */
    @Transactional(readOnly = true)
    List<ForumPost> findAllByIdIn(Collection<UUID> postIds);

    /** Writes a post and its coin tags. Not transactional here; the unit of work is the calling service's. */
    ForumPost save(ForumPost post);

    /** Sends pending statements, so a constraint answers where the caller can read it. */
    void flush();
}
