package com.cryptopilot.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Academy in the administration console: a draft is written, published, hidden and deleted, each change audited
 * (BR-57); slugs and places on the path stay unique; the glossary is edited; and only an Admin reaches it.
 *
 * <p>Rule: BR-57; T-114.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AcademyAdminApiTest {

    private static final String ADMIN = "/api/v1/admin/academy";
    private static final String SLUG = "qa-bai-thu-nghiem";
    private static final String TERM_SLUG = "qa-thuat-ngu";
    private static final String TOPIC_SLUG = "qa-chu-de";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void removeRows() {
        sql.sql("delete from academy_term where slug like 'qa-%'").update();
        sql.sql("delete from academy_article where slug like 'qa-%'").update();
        sql.sql("delete from academy_topic where slug like 'qa-%'").update();
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void T114_BR57_aDraft_isPublishedHiddenAndDeleted_andEveryChangeIsAudited() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        String created = as(admin, post(ADMIN + "/articles"), article(SLUG, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.topic").value("nhap-mon-crypto"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(get("/api/v1/academy/articles/" + SLUG)).andExpect(status().isNotFound());
        as(admin, get(ADMIN + "/articles?status=DRAFT")).andExpect(jsonPath("$.items[*].slug", hasItem(SLUG)));

        String published = as(admin, post(ADMIN + "/articles/" + id + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(get("/api/v1/academy/articles/" + SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("## Mục\n\nNội dung"));

        as(admin, delete(ADMIN + "/articles/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMY_ARTICLE_PUBLISHED"));
        as(admin, post(ADMIN + "/articles/" + id + "/hide"))
                .andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(get("/api/v1/academy/articles/" + SLUG)).andExpect(status().isNotFound());
        String again = as(admin, post(ADMIN + "/articles/" + id + "/publish"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(again, "$.publishedAt"))
                .as("the first publication is kept")
                .isEqualTo(JsonPath.read(published, "$.publishedAt"));
        as(admin, post(ADMIN + "/articles/" + id + "/hide")).andExpect(status().isOk());
        as(admin, delete(ADMIN + "/articles/" + id)).andExpect(status().isNoContent());
        as(admin, get(ADMIN + "/articles/" + id)).andExpect(status().isNotFound());

        List<String> actions =
                sql.sql("""
                        select action_code from audit_log
                         where entity_type = 'ACADEMY_ARTICLE' and entity_id = ? order by created_at, audit_id""").param(UUID.fromString(id)).query(String.class).list();
        assertThat(actions)
                .containsExactly(
                        "CONFIGURATION_CREATED",
                        "CONFIGURATION_CHANGED",
                        "CONFIGURATION_CHANGED",
                        "CONFIGURATION_CHANGED",
                        "CONFIGURATION_CHANGED",
                        "CONFIGURATION_DELETED");
    }

    @Test
    void T114_slugsAndPlacesOnThePath_stayUnique_andTheFormIsChecked() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/articles"), article("tien-ma-hoa-la-gi", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMY_SLUG_TAKEN"));
        as(admin, post(ADMIN + "/articles"), article(SLUG, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMY_PATH_ORDER_TAKEN"));
        as(admin, post(ADMIN + "/articles"), article("Bai Sai", null)).andExpect(status().isBadRequest());
        as(admin, post(ADMIN + "/articles"), article(SLUG, null).replace("\"/markets\"", "\"//evil.test\""))
                .andExpect(status().isBadRequest());
        as(admin, post(ADMIN + "/articles"), article(SLUG, null).replace("nhap-mon-crypto", "khong-co"))
                .andExpect(status().isBadRequest());

        String id = JsonPath.read(
                as(admin, post(ADMIN + "/articles"), article(SLUG, 99))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        as(admin, put(ADMIN + "/articles/" + id), edited(article(SLUG, 99).replace("Bài thử", "Bài đã sửa"), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Bài đã sửa"))
                .andExpect(jsonPath("$.pathOrder").value(99))
                .andExpect(jsonPath("$.version").value(1));
        as(admin, put(ADMIN + "/articles/" + id), edited(article("tien-ma-hoa-la-gi", 99), 1))
                .andExpect(jsonPath("$.code").value("ACADEMY_SLUG_TAKEN"));
    }

    @Test
    void T114_BR57_theGlossary_isEdited_andAudited() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        String created = as(admin, post(ADMIN + "/terms"), term(TERM_SLUG, "bitcoin-ethereum-va-altcoin"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.articleSlug").value("bitcoin-ethereum-va-altcoin"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        as(admin, post(ADMIN + "/terms"), term(TERM_SLUG, null)).andExpect(status().isConflict());
        as(admin, post(ADMIN + "/terms"), term("qa-khac", "khong-co-bai")).andExpect(status().isBadRequest());
        as(admin, get(ADMIN + "/terms?q=QA")).andExpect(jsonPath("$[0].slug").value(TERM_SLUG));

        as(admin, put(ADMIN + "/terms/" + id), edited(term(TERM_SLUG, null), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.articleSlug").doesNotExist());
        mvc.perform(get("/api/v1/academy/terms?q=QA thuật"))
                .andExpect(jsonPath("$[0].slug").value(TERM_SLUG));
        as(admin, delete(ADMIN + "/terms/" + id)).andExpect(status().isNoContent());
        as(admin, delete(ADMIN + "/terms/" + id)).andExpect(status().isNotFound());

        assertThat(sql.sql("select count(*) from audit_log where entity_type = 'ACADEMY_TERM' and entity_id = ?")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void T114_aChangeMadeOnAnOlderVersion_isRefused_andOneWithoutAVersionToo() throws Exception {
        UUID admin = account(UserRole.ADMIN);
        String id = JsonPath.read(
                as(admin, post(ADMIN + "/articles"), article(SLUG, null))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        // Two administrators open version 0; the first saves, the second must not write over it.
        as(admin, put(ADMIN + "/articles/" + id), edited(article(SLUG, null).replace("Bài thử", "Bản A"), 0))
                .andExpect(status().isOk());
        as(admin, put(ADMIN + "/articles/" + id), edited(article(SLUG, null).replace("Bài thử", "Bản B"), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMY_EDIT_CONFLICT"));
        as(admin, put(ADMIN + "/articles/" + id), article(SLUG, null)).andExpect(status().isBadRequest());
        as(admin, get(ADMIN + "/articles/" + id)).andExpect(jsonPath("$.title").value("Bản A"));
    }

    @Test
    void T114_BR57_topics_areEdited_audited_andKeptWhileTheyHoldLessons() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, get(ADMIN + "/topics"))
                .andExpect(jsonPath("$[0].slug").value("nhap-mon-crypto"))
                .andExpect(jsonPath("$[0].articles").value(3));
        String created = as(admin, post(ADMIN + "/topics"), topic(TOPIC_SLUG, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.articles").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        as(admin, post(ADMIN + "/topics"), topic(TOPIC_SLUG, null))
                .andExpect(jsonPath("$.code").value("ACADEMY_SLUG_TAKEN"));
        as(admin, post(ADMIN + "/topics"), topic("Sai Slug", null)).andExpect(status().isBadRequest());
        as(admin, put(ADMIN + "/topics/" + id), topic(TOPIC_SLUG, 0).replace("Chủ đề thử", "Chủ đề mới"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chủ đề mới"))
                .andExpect(jsonPath("$.version").value(1));
        as(admin, put(ADMIN + "/topics/" + id), topic(TOPIC_SLUG, 0))
                .andExpect(jsonPath("$.code").value("ACADEMY_EDIT_CONFLICT"));
        mvc.perform(get("/api/v1/academy/topics")).andExpect(jsonPath("$[*].slug", hasItem(TOPIC_SLUG)));

        String lesson = JsonPath.read(
                as(admin, post(ADMIN + "/articles"), article(SLUG, null).replace("nhap-mon-crypto", TOPIC_SLUG))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        as(admin, delete(ADMIN + "/topics/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMY_TOPIC_IN_USE"));
        as(admin, delete(ADMIN + "/articles/" + lesson)).andExpect(status().isNoContent());
        as(admin, delete(ADMIN + "/topics/" + id)).andExpect(status().isNoContent());
        as(admin, delete(ADMIN + "/topics/" + id)).andExpect(status().isNotFound());

        assertThat(sql.sql("select count(*) from audit_log where entity_type = 'ACADEMY_TOPIC' and entity_id = ?")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void T114_aCoverUpload_isRefused_whileNoMediaStorageIsSet() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/covers/upload-signature"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_STORAGE_UNAVAILABLE"));
    }

    @Test
    void T114_onlyAnAdmin_writesTheAcademy() throws Exception {
        UUID trader = account(UserRole.TRADER);

        mvc.perform(get(ADMIN + "/articles")).andExpect(status().isUnauthorized());
        as(trader, get(ADMIN + "/articles")).andExpect(status().isForbidden());
        as(trader, post(ADMIN + "/articles"), article(SLUG, null)).andExpect(status().isForbidden());
        as(trader, get(ADMIN + "/topics")).andExpect(status().isForbidden());
        as(trader, post(ADMIN + "/covers/upload-signature")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------

    private static String article(String slug, Integer pathOrder) {
        return """
                {"topic": "nhap-mon-crypto", "slug": "%s", "title": "Bài thử", "summary": "Tóm tắt",
                 "body": "## Mục\\n\\nNội dung", "level": "BEGINNER", "readingMinutes": 3, "sortOrder": 9,
                 "pathOrder": %s, "appLink": "/markets"}""".formatted(slug, pathOrder == null ? "null" : pathOrder);
    }

    private static String topic(String slug, Integer version) {
        return """
                {"slug": "%s", "name": "Chủ đề thử", "description": "Mô tả", "sortOrder": 99, "version": %s}""".formatted(slug, version == null ? "null" : version);
    }

    /** The form of a change: the same fields plus the version the change was made on. */
    private static String edited(String json, long version) {
        return json.substring(0, json.lastIndexOf('}')) + ", \"version\": " + version + "}";
    }

    private static String term(String slug, String articleSlug) {
        return """
                {"slug": "%s", "term": "QA thuật ngữ", "definition": "Định nghĩa", "articleSlug": %s}""".formatted(slug, articleSlug == null ? "null" : "\"" + articleSlug + "\"");
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, null);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(user)
                .query(String.class)
                .single();
        request.header(HttpHeaders.AUTHORIZATION, bearer(user, UserRole.valueOf(role)));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@academy.invalid", role.name(), now, now).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
