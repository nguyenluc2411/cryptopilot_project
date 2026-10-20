package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.PostMedia;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the files attached to posts.
 *
 * <p>Rule: BR-44.
 */
public interface PostMediaRepository extends Repository<PostMedia, UUID> {

    /** The files of one post in their order. */
    @Transactional(readOnly = true)
    List<PostMedia> findAllByPostIdOrderBySortOrderAsc(UUID postId);

    /** The files of these posts, each post's in order; read once for a page of the feed. */
    @Transactional(readOnly = true)
    List<PostMedia> findAllByPostIdInOrderBySortOrderAsc(Collection<UUID> postIds);

    /** Whether a file under this key is attached to any post. */
    @Transactional(readOnly = true)
    boolean existsByStoragePublicId(String storagePublicId);

    /** Writes a file row. Not transactional here; the unit of work is the calling service's. */
    PostMedia save(PostMedia media);

    /** Removes a file row a post no longer carries. */
    void delete(PostMedia media);

    /** Sends pending statements, so the unique key answers where the caller can read it. */
    void flush();
}
