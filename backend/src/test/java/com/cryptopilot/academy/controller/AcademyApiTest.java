package com.cryptopilot.academy.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Academy a Guest reads, against the lessons and the glossary the migration seeds: topics, the lesson list and
 * its filters, a lesson page with its neighbours, the beginner path and the glossary. A lesson that is not published
 * is nowhere.
 *
 * <p>Rule: T-114.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AcademyApiTest {

    private static final String ACADEMY = "/api/v1/academy";
    private static final String DRAFT_SLUG = "bai-nhap-thu-nghiem";
    // Compared without the line ending, which follows how git checked the migration out (LF or CRLF).
    private static final String DISCLAIMER = "không phải lời khuyên đầu tư.*";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @AfterEach
    void removeRows() {
        sql.sql("delete from academy_term where slug = ?").param(DRAFT_SLUG).update();
        sql.sql("delete from academy_article where slug = ?").param(DRAFT_SLUG).update();
    }

    @Test
    void T114_theTopics_areListedInOrder_withTheirLessonCounts() throws Exception {
        mvc.perform(get(ACADEMY + "/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].slug").value("nhap-mon-crypto"))
                .andExpect(jsonPath("$[0].name").value("Nhập môn crypto"))
                .andExpect(jsonPath("$[0].articles").value(3))
                .andExpect(jsonPath("$[7].slug").value("defi-nft-stablecoin"));
    }

    @Test
    void T114_theLessons_arePaged_andFilteredByTopicLevelAndText() throws Exception {
        mvc.perform(get(ACADEMY + "/articles?pageSize=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].slug").value("tien-ma-hoa-la-gi"))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].coverImageUrl", startsWith("https://")));
        mvc.perform(get(ACADEMY + "/articles?topic=futures-don-bay"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].topicSlug", everyItem(is("futures-don-bay"))));
        mvc.perform(get(ACADEMY + "/articles?level=ADVANCED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("funding-rate-open-interest"));
        mvc.perform(get(ACADEMY + "/articles?q=Stablecoin"))
                .andExpect(jsonPath("$.items[*].slug", hasItem("stablecoin-la-gi")));
        mvc.perform(get(ACADEMY + "/articles?topic=khong-co"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void T114_aBadFilter_isRefused() throws Exception {
        mvc.perform(get(ACADEMY + "/articles?q=a")).andExpect(status().isBadRequest());
        mvc.perform(get(ACADEMY + "/articles?level=EXPERT")).andExpect(status().isBadRequest());
        mvc.perform(get(ACADEMY + "/articles?pageSize=101")).andExpect(status().isBadRequest());
        mvc.perform(get(ACADEMY + "/terms?letter=ab")).andExpect(status().isBadRequest());
    }

    @Test
    void T114_aLessonOnThePath_linksItsNeighboursOnThePath_andItsTopic() throws Exception {
        mvc.perform(get(ACADEMY + "/articles/blockchain-hoat-dong-the-nao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Blockchain hoạt động như thế nào?"))
                .andExpect(jsonPath("$.body", startsWith("## ")))
                .andExpect(jsonPath("$.body", containsString(DISCLAIMER)))
                .andExpect(jsonPath("$.level").value("BEGINNER"))
                .andExpect(jsonPath("$.pathOrder").value(3))
                .andExpect(jsonPath("$.coverImageUrl", startsWith("https://res.cloudinary.com/")))
                .andExpect(jsonPath("$.coverCredit", endsWith(" / Unsplash")))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andExpect(jsonPath("$.previous.slug").value("bitcoin-ethereum-va-altcoin"))
                .andExpect(jsonPath("$.next.slug").value("vi-khoa-rieng-seed-phrase"))
                .andExpect(jsonPath("$.related.length()").value(2))
                .andExpect(jsonPath("$.related[0].slug").value("co-che-dong-thuan-pow-pos"));
    }

    @Test
    void T114_aLessonOffThePath_linksItsNeighboursInItsTopic() throws Exception {
        mvc.perform(get(ACADEMY + "/articles/co-che-dong-thuan-pow-pos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathOrder").value(nullValue()))
                .andExpect(jsonPath("$.previous.slug").value("blockchain-hoat-dong-the-nao"))
                .andExpect(jsonPath("$.next.slug").value("phi-gas-va-layer-2"))
                .andExpect(jsonPath("$.related.length()").value(0));
        mvc.perform(get(ACADEMY + "/articles/tien-ma-hoa-la-gi"))
                .andExpect(jsonPath("$.previous").value(nullValue()))
                .andExpect(jsonPath("$.appLink").value("/markets"));
    }

    @Test
    void T114_theBeginnerPath_isInOrder() throws Exception {
        mvc.perform(get(ACADEMY + "/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].pathOrder").value(1))
                .andExpect(jsonPath("$[0].slug").value("tien-ma-hoa-la-gi"))
                .andExpect(jsonPath("$[10].slug").value("luyen-tap-giao-dich-mo-phong"));
    }

    @Test
    void T114_theGlossary_isAlphabetical_andFilteredByLetterAndText() throws Exception {
        mvc.perform(get(ACADEMY + "/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(64))
                .andExpect(jsonPath("$[0].term").value("Altcoin"))
                .andExpect(jsonPath("$[0].articleSlug").value("bitcoin-ethereum-va-altcoin"));
        mvc.perform(get(ACADEMY + "/terms?letter=s"))
                .andExpect(jsonPath("$[*].term", everyItem(startsWith("S"))))
                .andExpect(jsonPath("$[*].slug", hasItem("seed-phrase")));
        mvc.perform(get(ACADEMY + "/terms?q=seed"))
                .andExpect(jsonPath("$[*].slug", hasItem("seed-phrase")))
                .andExpect(jsonPath("$[*].slug", not(hasItem("bitcoin"))));
    }

    @Test
    void T114_aLessonThatIsNotPublished_isNowhere() throws Exception {
        UUID draft = UUID.randomUUID();
        sql.sql("""
                        insert into academy_article (article_id, topic_id, slug, title, summary, body, article_level,
                                                     reading_minutes, article_status, sort_order, path_order,
                                                     created_at, updated_at)
                        select ?, topic_id, ?, 'Bản nháp', 'Tóm tắt', 'Nội dung', 'BEGINNER', 3, 'DRAFT', 0, 99,
                               now(), now()
                          from academy_topic where slug = 'nhap-mon-crypto'""").params(draft, DRAFT_SLUG).update();
        sql.sql("""
                        insert into academy_term (term_id, slug, term, definition, article_id, created_at, updated_at)
                        values (?, ?, 'Zeta nháp', 'Định nghĩa', ?, now(), now())""").params(UUID.randomUUID(), DRAFT_SLUG, draft).update();

        mvc.perform(get(ACADEMY + "/articles/" + DRAFT_SLUG)).andExpect(status().isNotFound());
        mvc.perform(get(ACADEMY + "/articles?topic=nhap-mon-crypto"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].slug", not(hasItem(DRAFT_SLUG))));
        mvc.perform(get(ACADEMY + "/path")).andExpect(jsonPath("$.length()").value(11));
        mvc.perform(get(ACADEMY + "/topics"))
                .andExpect(jsonPath("$[0].articles").value(3));
        mvc.perform(get(ACADEMY + "/terms?letter=z"))
                .andExpect(jsonPath("$[0].slug").value(DRAFT_SLUG))
                .andExpect(jsonPath("$[0].articleSlug").value(nullValue()));
    }
}
