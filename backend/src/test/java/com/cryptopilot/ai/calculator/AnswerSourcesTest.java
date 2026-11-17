package com.cryptopilot.ai.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.ai.model.AnswerSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which articles an answer cites (T-074): the numbers in brackets that the question's context holds, once each, in the
 * order the answer first cites them.
 *
 * <p>Rule: BR-51; T-074.
 */
class AnswerSourcesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonNode CONTEXT = JSON.readTree("""
            {"news":{"coin":"BTC","articles":[
              {"ref":1,"about":"COIN","headline":"ETF Bitcoin bị rút ròng","outlet":"CoinDesk",
               "publishedAt":"2026-11-12T09:00:00Z","url":"https://coindesk.example/etf"},
              {"ref":2,"about":"MARKET","headline":"Sàn bị hack","outlet":"Cointelegraph",
               "publishedAt":"2026-11-12T08:00:00Z","url":"https://ct.example/hack"},
              {"ref":3,"about":"MARKET","headline":"Không có link","outlet":"X"}
            ]}}""");

    @Test
    void T074_theCitedArticles_comeOnceEach_inTheOrderTheAnswerFirstCitesThem() {
        String answer = "Thị trường chịu áp lực sau vụ hack [2]. Dòng tiền ETF rút ra [1][2], và [1, 2] cùng chiều.";

        assertThat(AnswerSources.cited(answer, CONTEXT))
                .containsExactly(
                        new AnswerSource(
                                2,
                                AnswerSource.SYSTEM,
                                "Sàn bị hack",
                                "Cointelegraph",
                                "2026-11-12T08:00:00Z",
                                "https://ct.example/hack"),
                        new AnswerSource(
                                1,
                                AnswerSource.SYSTEM,
                                "ETF Bitcoin bị rút ròng",
                                "CoinDesk",
                                "2026-11-12T09:00:00Z",
                                "https://coindesk.example/etf"));
    }

    @Test
    void T074_aNumberTheContextDoesNotHold_orAnArticleWithoutALink_citesNothing() {
        assertThat(AnswerSources.cited("Theo [7] và [3], giá giảm.", CONTEXT)).isEmpty();
        assertThat(AnswerSources.cited("Mức [abc] và [ ] không phải trích dẫn.", CONTEXT))
                .isEmpty();
    }

    @Test
    void T074_noNewsInTheContext_noContext_orNoAnswer_citesNothing() {
        assertThat(AnswerSources.cited("Theo [1].", JSON.readTree("{\"market\":{}}")))
                .isEmpty();
        assertThat(AnswerSources.cited("Theo [1].", null)).isEmpty();
        assertThat(AnswerSources.cited(null, CONTEXT)).isEmpty();
    }

    @Test
    void T074_anArticleNamingItsOrigin_keepsIt() {
        JsonNode web = JSON.readTree("""
                {"news":{"articles":[{"ref":1,"origin":"WEB","headline":"H","outlet":"O","url":"https://w.example"}]}}""");

        assertThat(AnswerSources.cited("Xem [1].", web))
                .containsExactly(new AnswerSource(1, "WEB", "H", "O", null, "https://w.example"));
    }
}
