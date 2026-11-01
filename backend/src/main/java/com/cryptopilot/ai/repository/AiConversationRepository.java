package com.cryptopilot.ai.repository;

import com.cryptopilot.ai.entity.AiConversation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The Trader's conversations; a deleted one is never read back (SRS 3.9.1). */
public interface AiConversationRepository extends Repository<AiConversation, UUID> {

    @Transactional(readOnly = true)
    Optional<AiConversation> findById(UUID conversationId);

    @Transactional(readOnly = true)
    Optional<AiConversation> findByIdAndUserIdAndDeletedAtIsNull(UUID conversationId, UUID userId);

    @Transactional(readOnly = true)
    Page<AiConversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID userId, Pageable page);

    AiConversation save(AiConversation conversation);
}
