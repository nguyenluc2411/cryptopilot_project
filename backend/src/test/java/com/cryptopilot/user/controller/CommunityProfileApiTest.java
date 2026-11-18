package com.cryptopilot.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.client.ProfileImageStorage;
import com.cryptopilot.user.client.ProfileImageStorageException;
import com.cryptopilot.user.client.ProfileImageUploadTicket;
import com.cryptopilot.user.client.StoredProfileImage;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Edit dialog of a member page (D-99): introduction, link, avatar and banner, saved as a whole, and the signed form
 * that uploads an image. The storage is a stand-in, so what it "holds" is set per test.
 *
 * <p>The invariants: a saved image is one the storage read back as the caller's JPG, PNG or WEBP within
 * {@code MAX_IMAGE_SIZE_MB}; the key the profile already has keeps its image without asking the storage; a replaced or
 * cleared image is deleted from the storage, and only once the save stands; a refused save changes nothing.
 *
 * <p>Rule: D-98, D-99; MSG01, MSG14, MSG32.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CommunityProfileApiTest {

    private static final String SAVE = "/api/v1/me/community-profile";
    private static final String SIGN = "/api/v1/me/profile-images/upload-signature";
    private static final long FIVE_MB = 5L * 1024 * 1024;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private ProfileImageStorage images;

    private UUID trader;

    @BeforeEach
    void insertTrader() {
        trader = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(trader, trader + "@cp99.invalid", now, now).update();
        sql.sql("insert into user_profile (user_id, display_name, created_at, updated_at) values (?, ?, ?, ?)")
                .params(trader, "Member", now, now)
                .update();
    }

    @AfterEach
    void removeTrader() {
        sql.sql("delete from user_account where user_id = ?").param(trader).update();
    }

    @Test
    void D99_aSave_answersMsg14_andShowsOnTheProfileAndTheMemberPage() throws Exception {
        String avatar = stored("png", 1000);
        String banner = stored("webp", FIVE_MB);

        save(body("  Swing trader  ", "https://x.com/member", avatar, banner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));

        mvc.perform(get("/api/v1/me/profile").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.bio").value("Swing trader"))
                .andExpect(jsonPath("$.websiteUrl").value("https://x.com/member"))
                .andExpect(jsonPath("$.avatarUrl").value(urlOf(avatar)))
                .andExpect(jsonPath("$.avatarPublicId").value(avatar))
                .andExpect(jsonPath("$.bannerUrl").value(urlOf(banner)))
                .andExpect(jsonPath("$.bannerPublicId").value(banner));
        mvc.perform(get("/api/v1/community/members/" + trader))
                .andExpect(jsonPath("$.avatarUrl").value(urlOf(avatar)))
                .andExpect(jsonPath("$.bannerUrl").value(urlOf(banner)))
                .andExpect(jsonPath("$.bio").value("Swing trader"))
                .andExpect(jsonPath("$.websiteUrl").value("https://x.com/member"));
        verify(images, never()).destroy(any());
    }

    /** The keys the profile has keep their images as they are: the storage is not asked again. */
    @Test
    void D99_theKeysTheProfileHas_keepTheirImages_withoutAskingTheStorage() throws Exception {
        String avatar = stored("png", 1000);
        String banner = stored("jpg", 1000);
        save(body("Before", null, avatar, banner)).andExpect(status().isOk());
        when(images.describe(avatar)).thenThrow(new ProfileImageStorageException("down"));
        when(images.describe(banner)).thenThrow(new ProfileImageStorageException("down"));

        save(body("After", null, avatar, banner)).andExpect(status().isOk());

        Map<String, Object> row = row();
        assertThat(row.get("bio")).isEqualTo("After");
        assertThat(row.get("avatar_url")).isEqualTo(urlOf(avatar));
        assertThat(row.get("banner_url")).isEqualTo(urlOf(banner));
        verify(images, never()).destroy(any());
    }

    /** A replaced image and a cleared one are deleted from the storage; empty fields clear what was stored. */
    @Test
    void D99_aReplacedOrClearedImage_isDeletedFromTheStorage() throws Exception {
        String firstAvatar = stored("png", 1000);
        String banner = stored("png", 1000);
        save(body("Bio", "https://x.com/member", firstAvatar, banner)).andExpect(status().isOk());
        String secondAvatar = stored("png", 1000);

        save(body("", "", secondAvatar, null)).andExpect(status().isOk());

        verify(images).destroy(firstAvatar);
        verify(images).destroy(banner);
        Map<String, Object> row = row();
        assertThat(row.get("avatar_url")).isEqualTo(urlOf(secondAvatar));
        assertThat(row.get("banner_url")).isNull();
        assertThat(row.get("banner_public_id")).isNull();
        assertThat(row.get("bio")).isNull();
        assertThat(row.get("website_url")).isNull();
    }

    /** A delete the storage fails is only logged: the save stands. */
    @Test
    void D99_aFailedDeleteOfAReplacedImage_doesNotUndoTheSave() throws Exception {
        String avatar = stored("png", 1000);
        save(body(null, null, avatar, null)).andExpect(status().isOk());
        doThrow(new ProfileImageStorageException("down")).when(images).destroy(avatar);

        save(body(null, null, null, null)).andExpect(status().isOk());

        assertThat(row().get("avatar_url")).isNull();
    }

    /**
     * MSG32: a GIF, a file over the limit, a key with nothing stored and a key not signed for the caller are refused, on
     * the field that named them, and a refused save changes nothing.
     */
    @Test
    void D99_anImageOfAnotherFormatOrSize_orNotTheCallers_isRefused() throws Exception {
        String gif = stored("gif", 1000);
        String large = stored("png", FIVE_MB + 1);
        String missing = "cryptopilot/profiles/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, missing)).thenReturn(true);
        when(images.describe(missing)).thenReturn(Optional.empty());
        String good = stored("png", 1000);

        for (String key : List.of(gif, large, missing, "cryptopilot/profiles/someone/else")) {
            save(body("Not saved", null, key, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageCode").value("MSG32"))
                    .andExpect(jsonPath("$.errors.avatarPublicId").value("MSG32"));
            save(body("Not saved", null, good, key))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.bannerPublicId").value("MSG32"));
        }
        Map<String, Object> row = row();
        assertThat(row.get("bio")).isNull();
        assertThat(row.get("avatar_url")).isNull();
    }

    @Test
    void D99_aStorageThatCannotCheckTheImage_answers503() throws Exception {
        String key = "cryptopilot/profiles/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, key)).thenReturn(true);
        when(images.describe(key)).thenThrow(new ProfileImageStorageException("down"));

        save(body(null, null, null, key)).andExpect(status().isServiceUnavailable());
    }

    /** MSG01: an introduction over 300 characters, a link over 200 or not https. */
    @ParameterizedTest(name = "the link {0} is refused")
    @ValueSource(strings = {"http://x.com/member", "javascript:alert(1)", "https://x.com/a b", "x.com/member"})
    void D99_aLinkThatIsNotHttps_answersMsg01(String link) throws Exception {
        save(body(null, link, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.websiteUrl").value("MSG01"));
    }

    @Test
    void D99_anIntroductionOrALinkTooLong_answersMsg01() throws Exception {
        save(body("x".repeat(301), null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bio").value("MSG01"));
        save(body(null, "https://x.com/" + "a".repeat(200), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.websiteUrl").value("MSG01"));
        save(body("x".repeat(300), "https://x.com/" + "a".repeat(186), null, null))
                .andExpect(status().isOk());
    }

    /** The upload form is the storage's, with the size limit of MAX_IMAGE_SIZE_MB. */
    @Test
    void D99_theUploadForm_isSignedForTheCaller_withTheSizeLimit() throws Exception {
        when(images.sign(trader))
                .thenReturn(new ProfileImageUploadTicket(
                        "https://api.example/upload", "key", 42, "sig", "cryptopilot/profiles/x", List.of("png")));

        sign().andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://api.example/upload"))
                .andExpect(jsonPath("$.apiKey").value("key"))
                .andExpect(jsonPath("$.timestamp").value(42))
                .andExpect(jsonPath("$.signature").value("sig"))
                .andExpect(jsonPath("$.publicId").value("cryptopilot/profiles/x"))
                .andExpect(jsonPath("$.allowedFormats[0]").value("png"))
                .andExpect(jsonPath("$.maxBytes").value(FIVE_MB));
    }

    @Test
    void D99_withoutAStorage_theUploadForm_answers503() throws Exception {
        when(images.sign(trader)).thenThrow(new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, "none"));

        sign().andExpect(status().isServiceUnavailable());
    }

    @Test
    void D99_aGuest_isChallenged() throws Exception {
        mvc.perform(put(SAVE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(SIGN)).andExpect(status().isUnauthorized());
    }

    /** A key signed for the trader and stored with the given format and size. */
    private String stored(String format, long bytes) {
        String key = "cryptopilot/profiles/" + trader + "/" + UUID.randomUUID();
        when(images.issuedTo(trader, key)).thenReturn(true);
        when(images.describe(key)).thenReturn(Optional.of(new StoredProfileImage(key, format, bytes, urlOf(key))));
        return key;
    }

    private static String urlOf(String key) {
        return "https://img.example/" + key + ".png";
    }

    private static String body(String bio, String websiteUrl, String avatar, String banner) {
        return "{\"bio\": %s, \"websiteUrl\": %s, \"avatarPublicId\": %s, \"bannerPublicId\": %s}"
                .formatted(quoted(bio), quoted(websiteUrl), quoted(avatar), quoted(banner));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private ResultActions save(String body) throws Exception {
        return mvc.perform(put(SAVE)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions sign() throws Exception {
        return mvc.perform(post(SIGN).header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private Map<String, Object> row() {
        return sql.sql("select bio, website_url, avatar_url, banner_url, banner_public_id from user_profile"
                        + " where user_id = ?")
                .param(trader)
                .query()
                .singleRow();
    }

    private String bearer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(trader.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
