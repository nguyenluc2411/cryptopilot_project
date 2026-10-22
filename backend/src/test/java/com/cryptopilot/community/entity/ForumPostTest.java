package com.cryptopilot.community.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.community.model.enums.OfficialCategory;
import com.cryptopilot.community.model.enums.PostStatus;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rules a post enforces by itself: the lengths of SRS 3.7.2, the coin tag limit and who sees it at each status.
 *
 * <p>Rule: UC-27, UC-29, UC-44; BR-47, BR-61; SRS 3.7.2.
 */
class ForumPostTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void UC27_aPublishedPost_isTextOnly_published_andNotOfficial() {
        ForumPost post = ForumPost.publish(AUTHOR, "  Title  ", "  Text  ", MarketType.SPOT, List.of());

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.isOfficial()).isFalse();
        assertThat(post.getTitle()).isEqualTo("Title");
        assertThat(post.getContent()).isEqualTo("Text");
    }

    @Test
    void BR61_anAnnouncement_isOfficial() {
        assertThat(ForumPost.announce(AUTHOR, OfficialCategory.EDUCATION, "Title", "Text", null, List.of())
                        .isOfficial())
                .isTrue();
    }

    @Test
    void D99_anOfficialPost_hasACategory_andATradersPostHasNone() {
        ForumPost official = ForumPost.announce(AUTHOR, OfficialCategory.EDUCATION, "Title", "Text", null, List.of());
        ForumPost trader = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());

        assertThat(official.getOfficialCategory()).isEqualTo(OfficialCategory.EDUCATION);
        official.categorize(OfficialCategory.PLATFORM_UPDATE);
        assertThat(official.getOfficialCategory()).isEqualTo(OfficialCategory.PLATFORM_UPDATE);
        assertThat(trader.getOfficialCategory()).isNull();
        assertThatThrownBy(() -> trader.categorize(OfficialCategory.EDUCATION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void D99_aRemovedPost_isDeleted_andAHiddenOneRestores() {
        ForumPost removed = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());
        removed.remove();
        assertThat(removed.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThatThrownBy(removed::restore).isInstanceOf(IllegalStateException.class);

        ForumPost hidden = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());
        hidden.hide();
        assertThat(hidden.isVisibleTo(OTHER)).isFalse();
        hidden.restore();
        assertThat(hidden.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void UC27_theLengthsOfTheForm_areBounds() {
        assertThat(ForumPost.publish(AUTHOR, "x".repeat(5), "y", null, List.of())
                        .getTitle())
                .hasSize(5);
        assertThat(ForumPost.publish(AUTHOR, "x".repeat(150), "y".repeat(10_000), null, List.of())
                        .getContent())
                .hasSize(10_000);
        assertThatThrownBy(() -> ForumPost.publish(AUTHOR, "x".repeat(4), "y", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForumPost.publish(AUTHOR, "x".repeat(151), "y", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForumPost.publish(AUTHOR, "Title", "   ", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForumPost.publish(AUTHOR, "Title", "y".repeat(10_001), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void UC27_aPost_isTaggedWithAtMostFiveCoins() {
        List<UUID> five =
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(ForumPost.publish(AUTHOR, "Title", "Text", null, five).getCoinIds())
                .hasSize(5);

        List<UUID> six = new java.util.ArrayList<>(five);
        six.add(UUID.randomUUID());
        assertThatThrownBy(() -> ForumPost.publish(AUTHOR, "Title", "Text", null, six))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void UC29_revising_replacesTheTags() {
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        ForumPost post = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of(kept, dropped));

        post.revise("New title", "New text", MarketType.FUTURES, List.of(kept, added));

        assertThat(post.getCoinIds()).isEqualTo(Set.of(kept, added));
        assertThat(post.getMarketType()).isEqualTo(MarketType.FUTURES);
    }

    @Test
    void BR47_aPublishedPost_isVisibleToEveryone() {
        ForumPost post = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());

        assertThat(post.isVisibleTo(AUTHOR)).isTrue();
        assertThat(post.isVisibleTo(OTHER)).isTrue();
    }

    @Test
    void BR47_aHiddenPost_isVisibleToItsAuthorOnly() {
        ForumPost post = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());
        ReflectionTestUtils.setField(post, "status", PostStatus.HIDDEN);

        assertThat(post.isVisibleTo(AUTHOR)).isTrue();
        assertThat(post.isVisibleTo(OTHER)).isFalse();
        assertThatThrownBy(() -> post.revise("Title", "Text", null, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void BR47_aDeletedPost_isVisibleToNoTrader_andCannotBeRevised() {
        ForumPost post = ForumPost.publish(AUTHOR, "Title", "Text", null, List.of());

        post.delete();

        assertThat(post.isDeleted()).isTrue();
        assertThat(post.isVisibleTo(AUTHOR)).isFalse();
        assertThat(post.isVisibleTo(OTHER)).isFalse();
        assertThatThrownBy(() -> post.revise("Title", "Text", null, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
