package com.cryptopilot.journal.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.journal.client.ChartImageStorage;
import com.cryptopilot.journal.client.ChartImageStorageException;
import com.cryptopilot.journal.client.ChartImageUploadTicket;
import com.cryptopilot.journal.client.CloudinaryChartImageStorage;
import com.cryptopilot.journal.client.StoredChartImage;
import com.cryptopilot.journal.dto.request.JournalReviewRequest;
import com.cryptopilot.journal.dto.response.ChartImageUploadResponse;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.journal.service.JournalReviewService;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.trading.StrategyCatalog;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Saves the review of a trade. The record is looked up with its owner, so another Trader's id is "not found". Only the
 * review columns are written, by one update that leaves the execution facts and the row's version alone (BR-40). A new
 * chart image must be a key signed for the caller, stored, in JPG, PNG or WEBP and within {@code MAX_IMAGE_SIZE_MB}
 * (5 MB, SRS 3.6.1); the image it replaces, or that the review drops, is deleted from the storage once the review has
 * committed, and a failed delete is only logged.
 *
 * <p>Rule: BR-40; UC-23; SRS 3.6.1; MSG14, MSG15, MSG32, MSG41; D-98.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalReviewServiceImpl implements JournalReviewService {

    static final String MAX_IMAGE_SIZE_MB = "MAX_IMAGE_SIZE_MB";

    private static final long MEGABYTE = 1024L * 1024L;
    private static final String CHART_IMAGE = "chartImagePublicId";

    private static final Logger log = LoggerFactory.getLogger(JournalReviewServiceImpl.class);

    private final TradingJournalRepository journal;
    private final JournalService entries;
    private final StrategyCatalog strategies;
    private final ChartImageStorage images;
    private final SystemSettingApi settings;
    private final Clock clock;

    @Override
    @Transactional
    public JournalEntryDetailResponse review(UUID userId, UUID journalId, JournalReviewRequest request) {
        TradingJournal record = owned(userId, journalId);
        refuseOtherFields(request);
        requireStrategy(request.strategyId());
        String current = record.getChartImagePublicId();
        String requested = blankToNull(request.chartImagePublicId());
        String url = Objects.equals(requested, current)
                ? record.getChartImageUrl()
                : requested == null ? null : newImage(userId, requested).url();
        journal.review(
                journalId,
                userId,
                request.strategyId(),
                blankToNull(request.setupDescription()),
                blankToNull(request.lessonsLearned()),
                blankToNull(request.mistakes()),
                url,
                requested,
                clock.instant());
        if (current != null && !current.equals(requested)) {
            destroyAfterCommit(current);
        }
        log.info("UC-23 journal {} reviewed by its Trader", journalId);
        return entries.detail(userId, journalId);
    }

    @Override
    @Transactional(readOnly = true)
    public ChartImageUploadResponse signChartImage(UUID userId, UUID journalId) {
        owned(userId, journalId);
        ChartImageUploadTicket ticket = images.sign(userId);
        return new ChartImageUploadResponse(
                ticket.uploadUrl(),
                ticket.apiKey(),
                ticket.timestamp(),
                ticket.signature(),
                ticket.publicId(),
                ticket.allowedFormats(),
                maxBytes());
    }

    private TradingJournal owned(UUID userId, UUID journalId) {
        return journal.findByIdAndUserId(journalId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingJournal", journalId));
    }

    /** BR-40: the review carries the review fields only; an execution fact, or anything else, is refused. */
    private static void refuseOtherFields(JournalReviewRequest request) {
        if (request.otherFields().isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        request.otherFields().keySet().forEach(field -> errors.put(field, "MSG01"));
        throw new FieldValidationException(
                "BR-40 a review changes only its own fields, not " + errors.keySet(), errors);
    }

    private void requireStrategy(UUID strategyId) {
        if (strategyId != null && !strategies.names(Set.of(strategyId)).containsKey(strategyId)) {
            throw new FieldValidationException("no strategy " + strategyId, Map.of("strategyId", "MSG15"));
        }
    }

    /** The stored image a new key names, checked against what the storage itself read back. */
    private StoredChartImage newImage(UUID userId, String publicId) {
        if (!images.issuedTo(userId, publicId)) {
            throw invalidImage("key " + publicId + " was not signed for the caller");
        }
        StoredChartImage stored;
        try {
            stored = images.describe(publicId).orElseThrow(() -> invalidImage("nothing is stored under " + publicId));
        } catch (ChartImageStorageException unavailable) {
            throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, unavailable.getMessage());
        }
        if (!CloudinaryChartImageStorage.FORMATS.contains(stored.format()) || stored.bytes() > maxBytes()) {
            throw invalidImage("image " + publicId + " is " + stored.format() + " of " + stored.bytes() + " bytes");
        }
        return stored;
    }

    private long maxBytes() {
        return MEGABYTE * settings.integer(MAX_IMAGE_SIZE_MB);
    }

    private static FieldValidationException invalidImage(String detail) {
        return new FieldValidationException(ErrorCode.MEDIA_INVALID, detail, Map.of(CHART_IMAGE, "MSG32"));
    }

    private void destroyAfterCommit(String publicId) {
        Runnable destroy = () -> {
            try {
                images.destroy(publicId);
            } catch (RuntimeException failed) {
                log.warn("BR-40 chart image {} no longer used could not be deleted: {}", publicId, failed.toString());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            destroy.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                destroy.run();
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
