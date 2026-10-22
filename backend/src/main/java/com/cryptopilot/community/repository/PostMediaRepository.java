package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.PostMedia;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
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

    /**
     * Which of these keys a post still carries: the files the sweep of unattached files keeps. A post that is not
     * deleted carries its files, and so does a post moderation removed (REMOVE_POST), whose files are the evidence of
     * the decision. A post its author deleted released its files (BR-47), so they are not among them.
     */
    @Transactional(readOnly = true)
    @Query("""
            select m.storagePublicId from PostMedia m, ForumPost p
             where p.id = m.postId and m.storagePublicId in :keys
               and (p.status <> com.cryptopilot.community.model.enums.PostStatus.DELETED
                    or exists (select r.id from PostReport r
                                where r.postId = p.id
                                  and r.resolutionAction
                                      = com.cryptopilot.community.model.enums.ResolutionAction.REMOVE_POST))""")
    List<String> keysStillCarried(@Param("keys") Collection<String> keys);

    /** Writes a file row. Not transactional here; the unit of work is the calling service's. */
    PostMedia save(PostMedia media);

    /** Removes a file row a post no longer carries. */
    void delete(PostMedia media);

    /** Sends pending statements, so the unique key answers where the caller can read it. */
    void flush();
}
