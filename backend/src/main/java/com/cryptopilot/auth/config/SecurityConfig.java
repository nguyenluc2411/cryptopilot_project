package com.cryptopilot.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * What is reachable without signing in, and how a password is stored. Nothing else yet: there is no
 * sign-in to configure until T-012 issues a token, and no authorization matrix until T-015.
 *
 * <h2>Everything is denied unless it is named</h2>
 *
 * <p>The chain ends in {@code denyAll}, not {@code authenticated}. There is no way to authenticate
 * yet, so "authenticated" would be a rule that cannot be satisfied and would read as though it
 * could; {@code denyAll} says what is true — these endpoints are open, the rest of the application
 * is closed, and each later task opens exactly what it builds. A path added tomorrow is unreachable
 * until somebody decides who may reach it, which is the direction a default should fail in.
 *
 * <p>The three open endpoints are the ones a visitor must reach before they have an account
 * (UC-01, UC-02), plus the health probes the container orchestrator reads. Health is deliberately
 * narrower than {@code /actuator/**}: liveness and readiness say only whether the process is up,
 * while the rest of the actuator describes the application to anybody who asks.
 *
 * <h2>Sessions and CSRF</h2>
 *
 * <p>Stateless: no session is created and none is used, because the API will authenticate with a
 * bearer token and a request carries everything it needs. Cross-site request forgery protection is
 * therefore off, and that is safe for the reason it exists at all — the attack works by making a
 * browser replay an ambient credential, and there is no cookie, no session and no HTTP basic realm
 * for a browser to replay. It would have to become a decision again the moment anything is kept in
 * a cookie.
 *
 * <h2>The encoder</h2>
 *
 * <p>bcrypt at cost 12, published as a bean so that one strength applies everywhere and no caller
 * can quietly construct a cheaper one. Cost 12 means 2^12 key-expansion rounds — tens of
 * milliseconds per verification, which is nothing to one visitor signing in and is what makes an
 * offline attack on a stolen table expensive. The salt is per password and lives in the hash, which
 * is why two accounts with the same password store different strings.
 *
 * <p>Rule: BR-02; SRS 4.2.4 (a password is stored only as a salted hash); TECHNICAL_DESIGN sections
 * 1.3 and 5.3.
 *
 * <p>Reference: Provos, N. &amp; Mazières, D. (1999). <i>A Future-Adaptable Password Scheme</i>.
 * USENIX (bcrypt; the cost factor is raised as hardware improves, which is the point of storing it
 * in the hash).
 * <p>Reference: OWASP Application Security Verification Standard v4, section 2.1 (credential
 * storage: an adaptive, salted, work-factored one-way function).
 */
@Configuration
public class SecurityConfig {

    /** The cost factor of TECHNICAL_DESIGN 1.3; it is written into every hash this bean produces. */
    static final int BCRYPT_STRENGTH = 12;

    /** The endpoints UC-01 and UC-02 need before an account exists. */
    static final String[] PUBLIC_AUTH_ENDPOINTS = {
        "/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
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
