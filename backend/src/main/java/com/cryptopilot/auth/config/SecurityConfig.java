package com.cryptopilot.auth.config;

import com.cryptopilot.user.UserRole;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may reach what: the authorization matrix of SRS section 3.1.3, written as the filter chain
 * that enforces it.
 *
 * <h2>Everything is denied unless it is named</h2>
 *
 * <p>The chain ends in {@code denyAll}. A path that no rule above claims is unreachable by anybody,
 * authenticated or not, which is the direction a default should fail in: a controller added next
 * sprint stays closed until somebody decides who may open it, rather than inheriting whatever the
 * last rule happened to say.
 *
 * <h2>The three roles, and where Guest lives</h2>
 *
 * <p>SRS 3.1.3 names Guest, Trader and Admin. Only two of them are roles an account can hold
 * (BR-05); Guest is a visitor with no account at all, so it is not a value in {@link UserRole} and
 * could not be - there would be no row to put it on. Guest is expressed here instead, as the set of
 * rules that grant before authentication is considered: the six authentication endpoints and the
 * public market data. That is the whole of the public area, and everything outside it needs a role.
 *
 * <p>"Trader (Subscribed)" is not a fourth role either. SRS 3.1.3 says so in its own words - it is an
 * entitlement a Trader has while a subscription is active - so the two rows that depend on it (the
 * AI Assistant, and posting a video) are TRADER here, with the entitlement a second check on top
 * that belongs to the module owning subscriptions. Encoding it as a role would put a fact that
 * expires into a token that cannot be recalled.
 *
 * <h2>Why a wildcard is right here and was wrong for the authentication endpoints</h2>
 *
 * <p>The six public endpoints are still named one at a time, because a wildcard over
 * {@code /api/v1/auth/**} would open whatever that controller is given next - including the password
 * change, which must not be open. The areas below are wildcards for the opposite reason: a rule that
 * <em>restricts</em> a whole area is safe to widen, since an endpoint added under
 * {@code /api/v1/plans/**} tomorrow is Trader-only the moment it exists rather than denied until
 * somebody remembers it. The distinction is not the wildcard, it is what the rule grants: opening a
 * path to everyone is a decision per path, confining a path to a role is a decision per area.
 *
 * <h2>The areas, row by row</h2>
 *
 * <table border="1">
 * <caption>SRS 3.1.3 mapped onto the endpoint catalogue of TECHNICAL_DESIGN section 8</caption>
 * <tr><th>Row of SRS 3.1.3</th><th>Paths</th><th>Who</th></tr>
 * <tr><td>SCR-02…06 Register, Verify, Log In, Password Reset</td><td>the six below</td><td>anyone</td></tr>
 * <tr><td>SCR-01 Public Home, Query Public Data</td><td>{@code GET /market/**}</td><td>anyone</td></tr>
 * <tr><td>SCR-07 Security tab</td><td>{@code /me/password}</td><td>TRADER, ADMIN</td></tr>
 * <tr><td>SCR-07 Profile, Notifications, Subscription tabs</td><td>{@code /me/**}</td><td>TRADER</td></tr>
 * <tr><td>SCR-08…15 Dashboard, Market, Watchlist, Alerts, Notifications</td><td>{@code /analysis/**}, {@code /watchlist/**}, {@code /alerts/**}, {@code /notifications/**}</td><td>TRADER</td></tr>
 * <tr><td>SCR-16…19 Trading Plans and Position</td><td>{@code /plans/**}</td><td>TRADER</td></tr>
 * <tr><td>SCR-20…23 Journal and Performance</td><td>{@code /journal/**}, {@code /performance}</td><td>TRADER</td></tr>
 * <tr><td>SCR-24…27 Community</td><td>{@code /posts/**}, {@code /comments/**}, {@code /media/**}</td><td>TRADER</td></tr>
 * <tr><td>SCR-28 News Feed</td><td>{@code /news/**}</td><td>TRADER</td></tr>
 * <tr><td>SCR-29 AI Assistant</td><td>{@code /ai/**}</td><td>TRADER + entitlement</td></tr>
 * <tr><td>SCR-30…32 Subscription and Payment</td><td>{@code /billing/packages}, {@code /billing/orders/**}, {@code /billing/subscription}</td><td>TRADER</td></tr>
 * <tr><td>SCR-33…44 Administration console</td><td>{@code /admin/**}</td><td>ADMIN</td></tr>
 * </table>
 *
 * <p>Two of those deserve their reason stated rather than inferred.
 *
 * <p><b>Admin is absent from the Trader area on purpose.</b> SRS 3.1.3 says in its own preamble that
 * administrators use only the administration console, and the matrix leaves every Trader row blank
 * for them. So an administrator's token is refused at {@code /plans}, {@code /journal} and the rest -
 * not because an administrator is untrusted, but because an account that both moderates the community
 * and trades in it is a conflict the SRS chose to avoid. The one row they share is the Security tab,
 * since an administrator must be able to change their own password.
 *
 * <p><b>The payment callbacks are deliberately not listed.</b> {@code /billing/ipn/vnpay},
 * {@code /billing/ipn/momo} and {@code /billing/return/{gateway}} are not screens and not requests
 * from a signed-in Trader: the first two arrive from the payment gateway's servers and are
 * authenticated by their signature (BR-53), and the third is a browser redirect that carries no
 * header a client could have attached. Confining them to TRADER would break every payment. Naming
 * the three billing paths that are screens, rather than wildcarding {@code /billing/**}, leaves the
 * callbacks under {@code denyAll} until the task that builds them opens them together with the
 * signature check that is their real authentication.
 *
 * <h2>What this chain does not decide</h2>
 *
 * <p>It decides role, and only role. Two further checks sit above it and belong elsewhere, because
 * neither can be read from a path: <b>ownership</b>, which is a service comparing the owner of a row
 * with the caller, since one Trader must not read another Trader's plans although the matrix grants
 * them both the same area; and <b>entitlement</b>, which is a subscription being active now. SRS
 * 4.2.4 requires all three and this class is the first of them.
 *
 * <h2>The bearer token, sessions and CSRF</h2>
 *
 * <p>A token presented as {@code Authorization: Bearer ...} is verified by the resource server and
 * turned into a caller holding one role by {@link AccessTokenAuthenticationConverter}. It is
 * verified, not trusted: one algorithm, one key, and the expiry checked on every decode.
 *
 * <p>Stateless: no session is created and none is used. Cross-site request forgery protection is
 * therefore off, and that is safe for the reason the protection exists - the attack works by making a
 * browser replay an ambient credential, and there is no cookie, no session and no HTTP basic realm
 * for a browser to replay. It becomes a decision again the moment anything is kept in a cookie.
 *
 * <h2>The encoder</h2>
 *
 * <p>bcrypt at cost 12, published as a bean so that one strength applies everywhere and no caller can
 * quietly construct a cheaper one. Cost 12 means 2^12 key-expansion rounds - tens of milliseconds per
 * verification, which is nothing to one visitor signing in and is what makes an offline attack on a
 * stolen table expensive. The salt is per password and lives in the hash, which is why two accounts
 * with the same password store different strings.
 *
 * <p>Rule: BR-02, BR-05; SRS 3.1.3, SRS 4.2.4, UC-01, UC-02, UC-03, UC-05; TECHNICAL_DESIGN
 * sections 1.3, 5.3 and 8.
 *
 * <p>Reference: Sandhu, R., Coyne, E., Feinstein, H. &amp; Youman, C. (1996). <i>Role-Based Access
 * Control Models</i>. IEEE Computer 29(2) (permissions are assigned to roles and users acquire them
 * only by holding a role).
 * <p>Reference: Saltzer, J. H. &amp; Schroeder, M. D. (1975). <i>The Protection of Information in
 * Computer Systems</i>. Proceedings of the IEEE 63(9), section I.A.3 (fail-safe defaults: the default
 * is lack of access, and the protection scheme names what is permitted rather than what is excluded).
 * <p>Reference: Provos, N. &amp; Mazières, D. (1999). <i>A Future-Adaptable Password Scheme</i>.
 * USENIX (the cost factor is raised as hardware improves, which is why it is stored in the hash).
 * <p>Reference: OWASP Application Security Verification Standard v4, sections 1.4 and 4.1 (access
 * control is enforced on a trusted server and fails closed).
 */
@Configuration
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SecurityConfig {

    /** The cost factor of TECHNICAL_DESIGN 1.3; it is written into every hash this bean produces. */
    static final int BCRYPT_STRENGTH = 12;

    /**
     * The endpoints reachable without a session, each named one by one.
     *
     * <p>A wildcard over {@code /api/v1/auth/**} would be shorter and would open every endpoint this
     * controller ever grows, including the ones that must not be open - changing a password from the
     * Security tab is an {@code auth} concern and needs a signed-in caller.
     */
    static final String[] PUBLIC_AUTH_ENDPOINTS = {
        "/api/v1/auth/register",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/resend-verification",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password"
    };

    /**
     * The public market overview of SCR-01, readable by a Guest: the enabled pairs, their candles and
     * their statistics. Read-only - the write side of the same data is pair management, which is an
     * administration screen and lives under {@code /admin/pairs}.
     *
     * <p>What is deliberately not public is {@code /analysis/**}: indicators, support and resistance
     * and the setup score are the computed part of SCR-10 and SCR-11, which SRS 3.1.3 places in the
     * Trader area.
     */
    static final String PUBLIC_MARKET_DATA = "/api/v1/market/**";

    /** Liveness and readiness. Narrower than {@code /actuator/**}, which describes the application. */
    static final String[] PUBLIC_HEALTH_PROBES = {"/actuator/health", "/actuator/health/**"};

    /**
     * The OpenAPI document and Swagger UI of TECHNICAL_DESIGN 8, for reading. Open in every profile at the chain,
     * because the switch is springdoc's: the {@code prod} profile turns both off, so there they answer 404 — no
     * handler — rather than exposing anything. The document describes the API; it grants nothing.
     */
    static final String[] PUBLIC_API_DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    /**
     * The Security tab of SCR-07, the one screen the matrix grants to both roles: an administrator
     * has a password and must be able to change it.
     *
     * <p>Declared before {@link #TRADER_AREA}, because the first matching rule wins and
     * {@code /api/v1/me/**} would otherwise swallow it.
     */
    static final String SHARED_PASSWORD_CHANGE = "/api/v1/me/password";

    /**
     * Everything SRS 3.1.3 grants to a Trader and to nobody else. The order of the entries carries no
     * meaning; the order relative to {@link #SHARED_PASSWORD_CHANGE} does.
     */
    static final String[] TRADER_AREA = {
        "/api/v1/me/**",
        "/api/v1/analysis/**",
        "/api/v1/watchlist/**",
        "/api/v1/alerts/**",
        "/api/v1/notifications/**",
        "/api/v1/plans/**",
        "/api/v1/journal/**",
        "/api/v1/performance",
        "/api/v1/posts/**",
        "/api/v1/comments/**",
        "/api/v1/media/**",
        "/api/v1/news/**",
        "/api/v1/ai/**",
        "/api/v1/billing/packages",
        "/api/v1/billing/orders/**",
        "/api/v1/billing/subscription"
    };

    /** SCR-33 to SCR-44. One prefix, because every administration screen is served under it. */
    static final String ADMIN_CONSOLE = "/api/v1/admin/**";

    private final AccessTokenAuthenticationConverter accessTokenConverter;
    private final SecurityProblemDetailHandler problemDetails;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(accessTokenConverter))
                        .authenticationEntryPoint(problemDetails)
                        .accessDeniedHandler(problemDetails))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(problemDetails).accessDeniedHandler(problemDetails))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_MARKET_DATA)
                        .permitAll()
                        .requestMatchers(PUBLIC_HEALTH_PROBES)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_API_DOCS)
                        .permitAll()
                        .requestMatchers(SHARED_PASSWORD_CHANGE)
                        .hasAnyRole(UserRole.TRADER.name(), UserRole.ADMIN.name())
                        .requestMatchers(TRADER_AREA)
                        .hasRole(UserRole.TRADER.name())
                        .requestMatchers(ADMIN_CONSOLE)
                        .hasRole(UserRole.ADMIN.name())
                        .anyRequest()
                        .denyAll())
                .build();
    }

    /**
     * The one encoder. Published rather than constructed at each call site, so that raising the
     * cost later is one edit and so that nothing can be hashed at a strength somebody chose inline.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
