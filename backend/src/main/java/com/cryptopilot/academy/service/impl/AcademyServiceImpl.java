package com.cryptopilot.academy.service.impl;

import com.cryptopilot.academy.dto.response.ArticleDetailResponse;
import com.cryptopilot.academy.dto.response.ArticleLinkResponse;
import com.cryptopilot.academy.dto.response.ArticleSummaryResponse;
import com.cryptopilot.academy.dto.response.TermResponse;
import com.cryptopilot.academy.dto.response.TopicResponse;
import com.cryptopilot.academy.model.ArticleCard;
import com.cryptopilot.academy.model.ArticleFilter;
import com.cryptopilot.academy.model.ArticleText;
import com.cryptopilot.academy.repository.AcademyCatalogRepository;
import com.cryptopilot.academy.service.AcademyService;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public side of the Academy.
 *
 * <p>A lesson page names the lesson before and after it: on the beginner path when the lesson is on it, otherwise in
 * its topic. The related lessons are up to three others of the same topic, the neighbours left out because the page
 * already links them.
 *
 * <p>Rule: T-114.
 */
@Service
@RequiredArgsConstructor
public class AcademyServiceImpl implements AcademyService {

    /** Most related lessons a lesson page lists. */
    static final int RELATED_LIMIT = 3;

    private final AcademyCatalogRepository catalog;

    @Override
    @Transactional(readOnly = true)
    public List<TopicResponse> topics() {
        return catalog.topics().stream()
                .map(topic -> new TopicResponse(topic.slug(), topic.name(), topic.description(), topic.articles()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ArticleSummaryResponse> articles(ArticleFilter filter, Integer page, Integer pageSize) {
        ArticleFilter checked =
                new ArticleFilter(SearchText.blankToNull(filter.topic()), filter.level(), SearchText.of(filter.text()));
        return PageResponse.of(catalog.articles(checked, Paging.of(page, pageSize)), AcademyServiceImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public ArticleDetailResponse article(String slug) {
        ArticleText text = catalog.article(slug).orElseThrow(() -> new ResourceNotFoundException("Article", slug));
        ArticleCard card = text.card();
        List<ArticleCard> topic = catalog.topicArticles(card.topicSlug());
        List<ArticleCard> sequence = card.pathOrder() == null ? topic : catalog.beginnerPath();
        int at = indexOf(sequence, card.slug());
        ArticleCard previous = at > 0 ? sequence.get(at - 1) : null;
        ArticleCard next = at >= 0 && at < sequence.size() - 1 ? sequence.get(at + 1) : null;
        List<ArticleSummaryResponse> related = topic.stream()
                .filter(other -> !other.slug().equals(card.slug()))
                .filter(other -> !isSame(other, previous) && !isSame(other, next))
                .limit(RELATED_LIMIT)
                .map(AcademyServiceImpl::summary)
                .toList();
        return new ArticleDetailResponse(
                card.slug(),
                card.title(),
                card.summary(),
                text.body(),
                card.level(),
                card.readingMinutes(),
                card.topicSlug(),
                card.topicName(),
                card.pathOrder(),
                text.appLink(),
                text.publishedAt(),
                text.updatedAt(),
                link(previous),
                link(next),
                related);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleSummaryResponse> beginnerPath() {
        return catalog.beginnerPath().stream().map(AcademyServiceImpl::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TermResponse> terms(String text, String letter) {
        String first = SearchText.blankToNull(letter);
        if (first != null && first.codePointCount(0, first.length()) != 1) {
            throw new FieldValidationException("letter must be one letter, was " + first, Map.of("letter", "MSG15"));
        }
        return catalog.terms(SearchText.of(text), first).stream()
                .map(term -> new TermResponse(
                        term.slug(), term.term(), term.definition(), term.articleSlug(), term.articleTitle()))
                .toList();
    }

    private static ArticleSummaryResponse summary(ArticleCard card) {
        return new ArticleSummaryResponse(
                card.slug(),
                card.title(),
                card.summary(),
                card.level(),
                card.readingMinutes(),
                card.topicSlug(),
                card.topicName(),
                card.pathOrder());
    }

    private static ArticleLinkResponse link(ArticleCard card) {
        return card == null ? null : new ArticleLinkResponse(card.slug(), card.title());
    }

    private static boolean isSame(ArticleCard card, ArticleCard other) {
        return other != null && card.slug().equals(other.slug());
    }

    private static int indexOf(List<ArticleCard> cards, String slug) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).slug().equals(slug)) {
                return i;
            }
        }
        return -1;
    }
}
