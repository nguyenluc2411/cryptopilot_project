package com.cryptopilot.community.repository;

import com.cryptopilot.community.entity.PostReport;
import com.cryptopilot.community.model.enums.ReportStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the violation reports of posts.
 *
 * <p>Rule: UC-33; BR-46.
 */
public interface PostReportRepository extends Repository<PostReport, UUID> {

    /** Whether the Trader already reported the post (BR-46). */
    @Transactional(readOnly = true)
    boolean existsByPostIdAndReporterId(UUID postId, UUID reporterId);

    /** The reports of a post with one of these statuses, oldest first. */
    @Transactional(readOnly = true)
    List<PostReport> findAllByPostIdAndStatusInOrderByCreatedAtAscIdAsc(UUID postId, Collection<ReportStatus> statuses);

    /**
     * Whether the post has a report not decided yet or decided against it (HIDE_POST, REMOVE_POST, BAN_USER): its
     * files are then the evidence of the moderation and stay stored (BR-47, D-99).
     */
    @Transactional(readOnly = true)
    @Query("""
            select count(r) > 0 from PostReport r
             where r.postId = :postId
               and (r.status in (com.cryptopilot.community.model.enums.ReportStatus.OPEN,
                                 com.cryptopilot.community.model.enums.ReportStatus.REVIEWING)
                    or r.resolutionAction in (com.cryptopilot.community.model.enums.ResolutionAction.HIDE_POST,
                                              com.cryptopilot.community.model.enums.ResolutionAction.REMOVE_POST,
                                              com.cryptopilot.community.model.enums.ResolutionAction.BAN_USER))""")
    boolean holdsEvidence(@Param("postId") UUID postId);

    /** Writes a report. Not transactional here; the unit of work is the calling service's. */
    PostReport save(PostReport report);

    /** Sends pending statements, so the unique key answers where the caller can read it. */
    void flush();
}
