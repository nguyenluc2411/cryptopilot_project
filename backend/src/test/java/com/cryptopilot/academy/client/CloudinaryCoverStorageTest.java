package com.cryptopilot.academy.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signing a cover upload and accepting a cover address (T-114): the form is signed the way Cloudinary checks it, and
 * only a photo of the account's cover folder is accepted, stored with the cover crop.
 */
class CloudinaryCoverStorageTest {

    private static final String CROP = "c_fill,g_auto,w_1200,h_675,q_auto,f_auto";
    private static final String PREFIX = "https://res.cloudinary.com/demo/image/upload/";

    private final CloudinaryCoverStorage storage = new CloudinaryCoverStorage(
            new CoverStorageProperties(
                    "demo",
                    "key-123",
                    "secret-456",
                    URI.create("https://api.cloudinary.com"),
                    URI.create("https://res.cloudinary.com"),
                    "cryptopilot/academy",
                    CROP),
            Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void T114_anUpload_isSignedForANewKeyInTheCoverFolder_imagesOnly() {
        CoverUploadTicket ticket = storage.sign();

        assertThat(ticket.uploadUrl()).isEqualTo("https://api.cloudinary.com/v1_1/demo/image/upload");
        assertThat(ticket.apiKey()).isEqualTo("key-123");
        assertThat(ticket.timestamp())
                .isEqualTo(Instant.parse("2026-10-26T00:00:00Z").getEpochSecond());
        assertThat(ticket.publicId()).startsWith("cryptopilot/academy/");
        assertThat(ticket.allowedFormats()).containsExactly("jpg", "jpeg", "png", "webp");
        assertThat(ticket.signature())
                .isEqualTo(CloudinaryCoverStorage.signature(
                        Map.of(
                                "public_id",
                                ticket.publicId(),
                                "timestamp",
                                Long.toString(ticket.timestamp()),
                                "allowed_formats",
                                "jpg,jpeg,png,webp",
                                "overwrite",
                                "false"),
                        "secret-456"));
        assertThat(storage.sign().publicId()).isNotEqualTo(ticket.publicId());
    }

    @Test
    void T114_theSignature_isSha1OfTheSortedFieldsAndTheSecret() {
        // sha1("a=1&b=2secret"), computed outside Java.
        assertThat(CloudinaryCoverStorage.signature(Map.of("b", "2", "a", "1"), "secret"))
                .isEqualTo("69021e767b8b2f38af0bcc5fcefee075eb2ec60d");
    }

    @Test
    void T114_aCoverOfTheFolder_isAccepted_withTheCoverCrop() {
        String uploaded = PREFIX + "v1792945947/cryptopilot/academy/3f1c2b9a-0d1e.jpg";

        assertThat(storage.accept(uploaded))
                .contains(PREFIX + CROP + "/v1792945947/cryptopilot/academy/3f1c2b9a-0d1e.jpg");
        assertThat(storage.accept(PREFIX + "w_300,h_200/c_crop/v1/cryptopilot/academy/a.png"))
                .contains(PREFIX + CROP + "/v1/cryptopilot/academy/a.png");
        assertThat(storage.accept(PREFIX + "cryptopilot/academy/a.webp"))
                .contains(PREFIX + CROP + "/cryptopilot/academy/a.webp");
    }

    @Test
    void T114_anyOtherAddress_isRefused() {
        assertThat(storage.accept("https://images.unsplash.com/photo-1.jpg")).isEmpty();
        assertThat(storage.accept("https://res.cloudinary.com/other/image/upload/v1/cryptopilot/academy/a.jpg"))
                .isEmpty();
        assertThat(storage.accept(PREFIX + "v1/cryptopilot/posts/a.jpg")).isEmpty();
        assertThat(storage.accept(PREFIX + "v1/cryptopilot/academy/a.gif")).isEmpty();
        assertThat(storage.accept(PREFIX + "v1/cryptopilot/academy/../posts/a.jpg"))
                .isEmpty();
        assertThat(storage.accept(null)).isEmpty();
    }
}
