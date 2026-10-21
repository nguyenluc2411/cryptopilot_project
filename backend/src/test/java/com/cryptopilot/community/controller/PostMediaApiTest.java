package com.cryptopilot.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.InMemoryMediaStorage;
import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.MediaStorageException;
import com.cryptopilot.community.service.MediaSweepService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * The files of posts end to end, against an in-memory media storage: the signed form, BR-44 checked on what the
 * storage holds rather than what the client says, video posting on PREMIUM only (BR-43), and the deletion of files a
 * post no longer carries after the change commits.
 *
 * <p>Rule: UC-27, UC-28, UC-29, UC-44; BR-43, BR-44, BR-47, BR-62; MSG29, MSG32; D-98.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PostMediaApiTest.Storage.class})
class PostMediaApiTest {

    private static final String POSTS = "/api/v1/posts";
    private static final String SIGN = "/api/v1/media/upload-signature";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long MB = 1024L * 1024L;

    /** The PREMIUM package of 30 days that V11 seeded. */
    private static final UUID PREMIUM_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000004");

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000002");

    @TestConfiguration(proxyBeanMethods = false)
    static class Storage {

        @Bean
        @Primary
        InMemoryMediaStorage inMemoryMediaStorage() {
            return new InMemoryMediaStorage();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MediaStorage storageBean;

    @Autowired
    private MediaSweepService sweep;

    private final List<UUID> accounts = new ArrayList<>();

    private InMemoryMediaStorage storage() {
        return (InMemoryMediaStorage) storageBean;
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from forum_post where author_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        storage().clear();
    }

    // ------------------------------------------------------------------------------------------
    // The signed form (D-98)
    // ------------------------------------------------------------------------------------------

    @Test
    void D98_anImageForm_isSignedUnderTheCallersFolder_withTheLimitsOfBr44() throws Exception {
        UUID trader = trader();

        as(trader, post(SIGN), "{\"mediaType\": \"IMAGE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId")
                        .value(org.hamcrest.Matchers.startsWith("cryptopilot/posts/" + trader + "/")))
                .andExpect(jsonPath("$.signature").isNotEmpty())
                .andExpect(jsonPath("$.allowedFormats[0]").value("jpg"))
                .andExpect(jsonPath("$.allowedFormats.length()").value(3))
                .andExpect(jsonPath("$.maxBytes").value(5 * MB))
                .andExpect(jsonPath("$.maxDurationSeconds").doesNotExist())
                .andExpect(jsonPath("$.apiSecret").doesNotExist());
        as(trader, post(SIGN), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    @Test
    void BR43_aVideoForm_needsAPaidPlan() throws Exception {
        UUID free = trader();
        UUID pro = trader();
        UUID premium = trader();
        subscribe(pro, PRO_PACKAGE);
        subscribeToPremium(premium);

        as(free, post(SIGN), "{\"mediaType\": \"VIDEO\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"))
                .andExpect(jsonPath("$.messageArgs[0]").value("PRO"));
        // D-99: the registered topic gives video posting to every active paid package.
        as(pro, post(SIGN), "{\"mediaType\": \"VIDEO\"}").andExpect(status().isOk());
        as(premium, post(SIGN), "{\"mediaType\": \"VIDEO\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedFormats[0]").value("mp4"))
                .andExpect(jsonPath("$.maxBytes").value(100 * MB))
                .andExpect(jsonPath("$.maxDurationSeconds").value(300));
    }

    @Test
    void D98_anUnavailableStorage_answers503() throws Exception {
        UUID trader = trader();
        storage().failing(true);

        as(trader, post(SIGN), "{\"mediaType\": \"IMAGE\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messageCode").value("MSG43"));
    }

    // ------------------------------------------------------------------------------------------
    // Attaching files (BR-44)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR44_aPostWithImages_showsThemInOrder_andItsCoverInTheFeed() throws Exception {
        UUID trader = trader();
        String first = signed(trader, "IMAGE");
        String second = signed(trader, "IMAGE");
        storage().uploadImage(first, "jpg", MB);
        storage().uploadImage(second, "webp", 2 * MB);

        String id = id(as(trader, post(POSTS), postBody("Two charts", image(second), image(first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postType").value("TEXT"))
                .andExpect(jsonPath("$.media.length()").value(2))
                .andExpect(jsonPath("$.media[0].publicId").value(second))
                .andExpect(jsonPath("$.media[0].url").value("https://res.invalid/" + second + ".webp"))
                .andExpect(jsonPath("$.media[1].publicId").value(first)));

        as(trader, get(POSTS + "?tab=MINE"), null)
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].cover.publicId").value(second));
    }

    @Test
    void BR44_aVideoPost_isAVideoPost_onPremium() throws Exception {
        UUID premium = trader();
        subscribeToPremium(premium);
        String video = signed(premium, "VIDEO");
        storage().uploadVideo(video, "mp4", 50 * MB, 300);

        as(premium, post(POSTS), postBody("A walkthrough", video(video)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postType").value("VIDEO"))
                .andExpect(jsonPath("$.media[0].mediaType").value("VIDEO"))
                .andExpect(jsonPath("$.media[0].durationSeconds").value(300))
                .andExpect(jsonPath("$.media[0].thumbnailUrl").value("https://res.invalid/" + video + ".jpg"));
    }

    @Test
    void BR43_aVideo_cannotBePostedWithoutThePremiumPlan() throws Exception {
        UUID free = trader();
        String key = "cryptopilot/posts/" + free + "/" + UUID.randomUUID();
        storage().uploadVideo(key, "mp4", MB, 10);

        as(free, post(POSTS), postBody("Sneaked video", video(key)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        assertThat(postsOf(free)).isZero();
    }

    @Test
    void BR44_filesBeyondTheLimits_areRefusedWithMsg32_andDeleted() throws Exception {
        UUID premium = trader();
        subscribeToPremium(premium);
        String big = signed(premium, "IMAGE");
        String gif = signed(premium, "IMAGE");
        String longVideo = signed(premium, "VIDEO");
        storage().uploadImage(big, "jpg", 5 * MB + 1);
        storage().uploadImage(gif, "gif", MB);
        storage().uploadVideo(longVideo, "mp4", MB, 301);

        as(premium, post(POSTS), postBody("Too big", image(big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"))
                .andExpect(jsonPath("$.messageArgs[0]").value("5 MB"));
        as(premium, post(POSTS), postBody("Animated", image(gif)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        as(premium, post(POSTS), postBody("Too long", video(longVideo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"))
                .andExpect(jsonPath("$.messageArgs[0]").value("300 seconds"));

        assertThat(storage().destroyed()).containsExactlyInAnyOrder(big, gif, longVideo);
        assertThat(postsOf(premium)).isZero();
    }

    @Test
    void BR44_aPostCarriesFourImagesOrOneVideo_neverBoth() throws Exception {
        UUID premium = trader();
        subscribeToPremium(premium);
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            images.add(signed(premium, "IMAGE"));
            storage().uploadImage(images.get(i), "png", MB);
        }
        String video = signed(premium, "VIDEO");
        storage().uploadVideo(video, "webm", MB, 30);

        as(
                        premium,
                        post(POSTS),
                        postBody(
                                "Five images",
                                images.stream().map(PostMediaApiTest::image).toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        as(premium, post(POSTS), postBody("Image and video", image(images.get(0)), video(video)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        as(premium, post(POSTS), postBody("Same twice", image(images.get(0)), image(images.get(0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        as(
                        premium,
                        post(POSTS),
                        postBody(
                                "Four images",
                                images.subList(0, 4).stream()
                                        .map(PostMediaApiTest::image)
                                        .toArray(String[]::new)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.length()").value(4));
    }

    @Test
    void D98_aFileThatIsNotTheCallersNewUpload_isRefused() throws Exception {
        UUID owner = trader();
        UUID other = trader();
        String mine = signed(owner, "IMAGE");
        storage().uploadImage(mine, "jpg", MB);
        String missing = signed(other, "IMAGE");

        as(other, post(POSTS), postBody("Someone else's file", image(mine)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        as(other, post(POSTS), postBody("Never uploaded", image(missing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));

        as(owner, post(POSTS), postBody("First post", image(mine))).andExpect(status().isCreated());
        as(owner, post(POSTS), postBody("Second post", image(mine)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG32"));
        // Refused for not being the caller's file: it is not deleted.
        assertThat(storage().destroyed()).isEmpty();
    }

    @Test
    void D98_aKeyOfAnotherShape_isRefusedWithMsg32_notAServerError() throws Exception {
        UUID trader = trader();
        String folder = "cryptopilot/posts/" + trader + "/";

        for (String key : List.of(folder + "../other", folder + "a b", folder + "x/" + UUID.randomUUID(), folder)) {
            as(trader, post(POSTS), postBody("Odd key", image(key)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("MSG32"));
        }
        assertThat(postsOf(trader)).isZero();
    }

    @Test
    void BR43_aVideoAlreadyPosted_staysWhenThePlanEnds_butNoNewOneCanBeAdded() throws Exception {
        UUID trader = trader();
        subscribeToPremium(trader);
        String video = signed(trader, "VIDEO");
        storage().uploadVideo(video, "mp4", MB, 60);
        String id = id(as(trader, post(POSTS), postBody("Before expiry", video(video))));
        sql.sql("""
                        update user_subscription set end_at = ?
                         where order_id in (select order_id from subscription_order where user_id = ?)""")
                .params(Timestamp.from(Instant.now().minusSeconds(60)), trader)
                .update();

        as(trader, put(POSTS + "/" + id), postBody("After expiry", video(video)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postType").value("VIDEO"))
                .andExpect(jsonPath("$.media[0].publicId").value(video));
        String another = "cryptopilot/posts/" + trader + "/" + UUID.randomUUID();
        storage().uploadVideo(another, "mp4", MB, 60);
        as(trader, put(POSTS + "/" + id), postBody("Swapped video", video(another)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        as(trader, post(SIGN), "{\"mediaType\": \"VIDEO\"}").andExpect(status().isForbidden());
    }

    @Test
    void D98_theAdminForm_isForAdminsOnly() throws Exception {
        UUID trader = trader();
        UUID admin = account(UserRole.ADMIN);

        as(trader, post("/api/v1/admin/media/upload-signature"), "{\"mediaType\": \"IMAGE\"}")
                .andExpect(status().isForbidden());
        as(admin, UserRole.ADMIN, post(SIGN), "{\"mediaType\": \"IMAGE\"}").andExpect(status().isForbidden());
        mvc.perform(post(SIGN)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------
    // Releasing files (SRS 3.7.2, BR-47)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC29_anEditKeepsTheFilesItNamesAgain_andDeletesTheOthers() throws Exception {
        UUID trader = trader();
        String kept = signed(trader, "IMAGE");
        String dropped = signed(trader, "IMAGE");
        String added = signed(trader, "IMAGE");
        storage().uploadImage(kept, "jpg", MB);
        storage().uploadImage(dropped, "jpg", MB);
        String id = id(as(trader, post(POSTS), postBody("Charts", image(kept), image(dropped))));
        storage().uploadImage(added, "png", MB);

        as(trader, put(POSTS + "/" + id), postBody("Charts again", image(added), image(kept)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.length()").value(2))
                .andExpect(jsonPath("$.media[0].publicId").value(added))
                .andExpect(jsonPath("$.media[1].publicId").value(kept));

        await().atMost(WAIT).until(() -> storage().destroyed().contains(dropped));
        assertThat(storage().destroyed()).containsExactly(dropped);
    }

    @Test
    void BR47_deletingAPost_deletesItsFilesAfterTheCommit() throws Exception {
        UUID trader = trader();
        String file = signed(trader, "IMAGE");
        storage().uploadImage(file, "jpg", MB);
        String id = id(as(trader, post(POSTS), postBody("Short-lived", image(file))));

        as(trader, delete(POSTS + "/" + id), null).andExpect(status().isNoContent());

        await().atMost(WAIT).until(() -> storage().destroyed().contains(file));
    }

    @Test
    void UC44_anAnnouncement_carriesFilesFromTheAdminForm() throws Exception {
        UUID admin = account(UserRole.ADMIN);
        String file = JsonPath.read(
                as(admin, UserRole.ADMIN, post("/api/v1/admin/media/upload-signature"), "{\"mediaType\": \"IMAGE\"}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.publicId");
        storage().uploadImage(file, "png", MB);

        as(admin, UserRole.ADMIN, post("/api/v1/admin/announcements"), postBody("Release notes", image(file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.official").value(true))
                .andExpect(jsonPath("$.media[0].publicId").value(file));
    }

    // ------------------------------------------------------------------------------------------
    // The sweep of files no post carries (plan 9.3)
    // ------------------------------------------------------------------------------------------

    @Test
    void D98_theSweep_deletesOldFilesNoLivePostCarries_andKeepsTheRest() throws Exception {
        UUID trader = trader();
        String carried = signed(trader, "IMAGE");
        storage().uploadImage(carried, "jpg", 1_000);
        as(trader, post(POSTS), postBody("A post with a picture", image(carried)))
                .andExpect(status().isCreated());
        String leftByADeletedPost = signed(trader, "IMAGE");
        storage().uploadImage(leftByADeletedPost, "png", 1_000);
        String deletedPost = id(as(trader, post(POSTS), postBody("A post deleted later", image(leftByADeletedPost)))
                .andExpect(status().isCreated()));
        // As if the release of its files had failed for good: the post is deleted, its file still stored.
        sql.sql("update forum_post set post_status = 'DELETED' where post_id = ?")
                .param(UUID.fromString(deletedPost))
                .update();
        String neverAttached = signed(trader, "IMAGE");
        storage().uploadImage(neverAttached, "webp", 1_000);
        String justUploaded = signed(trader, "IMAGE");
        storage().uploadImage(justUploaded, "jpg", 1_000);
        String oldVideo = "cryptopilot/posts/" + trader + "/" + UUID.randomUUID();
        storage().uploadVideo(oldVideo, "mp4", 1_000, 10);
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        for (String file : List.of(carried, leftByADeletedPost, neverAttached, oldVideo)) {
            storage().uploadedAt(file, twoDaysAgo);
        }

        assertThat(sweep.removeUnattached()).isEqualTo(3);

        assertThat(storage().destroyed()).containsExactlyInAnyOrder(leftByADeletedPost, neverAttached, oldVideo);
    }

    @Test
    void D98_aSweepWhileTheStorageIsDown_fails_andTheNextRunDeletesTheFile() throws Exception {
        UUID trader = trader();
        String file = signed(trader, "IMAGE");
        storage().uploadImage(file, "jpg", 1_000);
        storage().uploadedAt(file, Instant.now().minus(Duration.ofDays(2)));
        storage().failing(true);

        assertThatThrownBy(sweep::removeUnattached).isInstanceOf(MediaStorageException.class);

        storage().failing(false);
        assertThat(sweep.removeUnattached()).isEqualTo(1);
        assertThat(storage().destroyed()).containsExactly(file);
    }

    // ------------------------------------------------------------------------------------------

    private String signed(UUID trader, String type) throws Exception {
        UserRole role = UserRole.TRADER;
        if (type.equals("VIDEO") && !premium(trader)) {
            // A FREE Trader gets no video form; tests that need a key anyway build one in the caller's folder.
            return "cryptopilot/posts/" + trader + "/" + UUID.randomUUID();
        }
        return JsonPath.read(
                as(trader, role, post(SIGN), "{\"mediaType\": \"" + type + "\"}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.publicId");
    }

    private boolean premium(UUID trader) {
        return sql.sql("select count(*) from subscription_order where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static String image(String publicId) {
        return "{\"publicId\": \"" + publicId + "\", \"mediaType\": \"IMAGE\"}";
    }

    private static String video(String publicId) {
        return "{\"publicId\": \"" + publicId + "\", \"mediaType\": \"VIDEO\"}";
    }

    private static String postBody(String title, String... media) {
        return "{\"title\": \"" + title + "\", \"content\": \"text\", \"media\": [" + String.join(", ", media) + "]}";
    }

    private long postsOf(UUID author) {
        return sql.sql("select count(*) from forum_post where author_id = ?")
                .param(author)
                .query(Long.class)
                .single();
    }

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        return as(trader, UserRole.TRADER, request, body);
    }

    private ResultActions as(UUID user, UserRole role, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user, role));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private UUID trader() {
        return account(UserRole.TRADER);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t061.invalid", role.name(), now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, 'Media', ?, ?)")
                .params(id, now, now)
                .update();
        accounts.add(id);
        return id;
    }

    /** A PAID order for the PREMIUM package and an ACTIVE subscription that started a day ago. */
    private void subscribeToPremium(UUID trader) {
        subscribe(trader, PREMIUM_PACKAGE);
    }

    /** A PAID order for a package and an ACTIVE subscription that started a day ago. */
    private void subscribe(UUID trader, UUID packageId) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        packageId,
                        "T061-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
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
