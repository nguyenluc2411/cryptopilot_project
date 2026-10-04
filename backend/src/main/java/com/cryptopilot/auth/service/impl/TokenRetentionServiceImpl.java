package com.cryptopilot.auth.service.impl;

import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.auth.service.TokenRetentionService;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired refresh tokens against the injected clock.
 *
 * <p>Rule: NSF-17; TECHNICAL_DESIGN 7.15.
 *
 * <p>Reference: Lodderstedt, T. et al. (2025). RFC 9700: OAuth 2.0 Security Best Current Practice, section 4.14.
 * IETF.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TokenRetentionServiceImpl implements TokenRetentionService {

    private final UserTokenRepository tokens;
    private final Clock clock;

    @Override
    @Transactional
    public int removeExpiredRefreshTokens() {
        return tokens.deleteExpiredRefreshTokens(clock.instant());
    }
}
