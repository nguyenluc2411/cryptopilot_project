package com.cryptopilot.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The two halves of the access token: the encoder that signs one and the decoder that verifies one,
 * both over the same symmetric key.
 *
 * <h2>One algorithm, named at both ends</h2>
 *
 * <p>HS256, stated to the encoder in its header and to the decoder as the only algorithm it will
 * accept. Naming it on the decoder is the part that matters and the part that is easy to leave out:
 * a decoder that takes the algorithm from the token's own header lets the token choose how it is
 * verified, which is the algorithm-confusion attack — present {@code alg: none}, or an HMAC signed
 * with a public key the server published, and the server checks a signature the attacker controls.
 * {@link MacAlgorithm#HS256} on {@code macAlgorithm} is what makes any other header a rejection
 * instead of an instruction.
 *
 * <h2>The expiry is checked exactly, and against the injected clock</h2>
 *
 * <p>Spring's own timestamp validator is replaced by {@link AccessTokenExpiryValidator}, which says
 * why in full: the default allows sixty seconds of clock skew that SRS 3.2.3 never granted, and it
 * accepts a token at the instant it expires where every other expiry in this application is
 * exclusive.
 *
 * <p>The replacement reads the same {@link Clock} as everything else, so a test moves the expiry
 * rather than waiting for it — without this the decoder would be the one component in the application
 * still reading the system clock, and the one expiry that could not be tested at its boundary.
 *
 * <p>The issuer is checked too. It proves nothing on its own — anyone who has the key can write any
 * issuer they like — but it is what stops a token minted for some future sibling service, signed with
 * a key that was copied between environments, from being accepted here by accident.
 *
 * <h2>Symmetric, and why that is the right choice here</h2>
 *
 * <p>The same process signs and verifies. A public-key algorithm buys the ability to let something
 * else verify without being able to sign, and nothing else verifies: this is one modular monolith
 * reading its own tokens, so the extra key management would pay for a property nobody uses.
 * TECHNICAL_DESIGN 5.3 fixes HS256 for that reason, and this is where the choice is stated.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.3; SRS 3.2.3.
 *
 * <p>Reference: Jones, M., Bradley, J. &amp; Sakimura, N. (2015). RFC 7519, <i>JSON Web Token</i>,
 * sections 4.1.1 and 4.1.4 ({@code iss} and {@code exp}).
 * <p>Reference: Sheffer, Y., Hardt, D. &amp; Jones, M. (2020). RFC 8725, <i>JSON Web Token Best
 * Current Practices</i>, section 3.1 (do not let the token choose its algorithm) and section 3.8
 * (always validate the expiry).
 */
@Configuration
@EnableConfigurationProperties(TokenProperties.class)
public class JwtConfig {

    /**
     * The one algorithm, so the encoder, the decoder and the header the issuer writes cannot drift
     * apart. Public because the issuer states it in the header it builds, and a second constant there
     * would be the drift this one exists to prevent.
     */
    public static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    /** The {@code iss} claim: written by the issuer, required by the decoder, one constant for both. */
    public static final String ISSUER = "cryptopilot";

    private final SecretKeySpec key;
    private final Clock clock;

    JwtConfig(TokenProperties properties, Clock clock) {
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM.getName());
        this.clock = clock;
    }

    /** Signs an access token. */
    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Verifies a presented access token: the signature, with this key and this algorithm only; the
     * expiry, exactly and against the injected clock; and the issuer.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(ALGORITHM).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new AccessTokenExpiryValidator(clock), new JwtIssuerValidator(ISSUER)));
        return decoder;
    }
}
