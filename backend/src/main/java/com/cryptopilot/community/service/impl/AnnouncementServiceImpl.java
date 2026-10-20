package com.cryptopilot.community.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.community.dto.request.PostRequest;
import com.cryptopilot.community.dto.response.PostDetailResponse;
import com.cryptopilot.community.dto.response.PostSummaryResponse;
import com.cryptopilot.community.entity.ForumPost;
import com.cryptopilot.community.repository.ForumPostRepository;
import com.cryptopilot.community.repository.PostFeedRepository;
import com.cryptopilot.community.service.AnnouncementService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The announcements of the administration console (UC-44). They are posts with the official flag set at creation, so
 * they are published, read and deleted like any post (BR-47) and pinned above the feed (BR-61). Any administrator may
 * change any announcement; the author shown is the one who published it.
 *
 * <p>Rule: UC-44; BR-47, BR-61.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final ForumPostRepository posts;
    private final PostFeedRepository feeds;
    private final PostAssembler assembler;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostSummaryResponse> list(UUID admin, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        return assembler.page(feeds.official(true, request), request, admin);
    }

    @Override
    @Transactional
    public PostDetailResponse create(UUID admin, PostRequest request) {
        ForumPost post = ForumPost.announce(
                admin, request.title(), request.content(), request.marketType(), assembler.coinTags(request.coins()));
        posts.save(post);
        posts.flush();
        return assembler.detail(post, admin);
    }

    @Override
    @Transactional
    public PostDetailResponse update(UUID admin, UUID postId, PostRequest request) {
        ForumPost post = announcement(postId);
        post.revise(request.title(), request.content(), request.marketType(), assembler.coinTags(request.coins()));
        posts.flush();
        return assembler.detail(post, admin);
    }

    @Override
    @Transactional
    public void delete(UUID admin, UUID postId) {
        announcement(postId).delete();
    }

    private ForumPost announcement(UUID postId) {
        return posts.findByIdAndOfficialTrue(postId)
                .filter(post -> !post.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Announcement", postId));
    }
}
