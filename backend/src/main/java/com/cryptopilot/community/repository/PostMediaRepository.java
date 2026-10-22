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
     * deleted carries its files. A deleted post keeps them while it has a report not decided yet or decided against
     * it (HIDE_POST, REMOVE_POST, BAN_USER): they are the evidence of the moderation. A deleted post with no such
     * report released its files (BR-47), so they are not among them.
     *
     * <p>Rule: BR-47; D-99.
     *
     * <p>Reference: Evans, E. <i>Domain-Driven Design</i> (Addison-Wesley, 2003), ch. 6.
     */
    @Transactional(readOnly = true)
    @Query("""
            select m.storagePublicId from PostMedia m, ForumPost p
             where p.id = m.postId and m.storagePublicId in :keys
               and (p.status <> com.cryptopilot.community.model.enums.PostStatus.DELETED
                    or exists (select r.id from PostReport r
                                where r.postId = p.id
                                  and (r.status in (com.cryptopilot.community.model.enums.ReportStatus.OPEN,
                                                    com.cryptopilot.community.model.enums.ReportStatus.REVIEWING)
                                       or r.resolutionAction in (
                                              com.cryptopilot.community.model.enums.ResolutionAction.HIDE_POST,
                                              com.cryptopilot.community.model.enums.ResolutionAction.REMOVE_POST,
                                              com.cryptopilot.community.model.enums.ResolutionAction.BAN_USER))))""")
    List<String> keysStillCarried(@Param("keys") Collection<String> keys);

    /** Writes a file row. Not transactional here; the unit of work is the calling service's. */
    PostMedia save(PostMedia media);

    /** Removes a file row a post no longer carries. */
    void delete(PostMedia media);

    /** Sends pending statements, so the unique key answers where the caller can read it. */
    void flush();
}
