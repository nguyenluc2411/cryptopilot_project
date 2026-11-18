package com.cryptopilot.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.user.TradingProfile;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.model.enums.RiskProfile;
import com.cryptopilot.user.model.enums.TradingStyle;
import com.cryptopilot.user.repository.UserAccountRepository;
import com.cryptopilot.user.repository.UserProfileRepository;
import com.cryptopilot.user.service.DeviceService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The trading preferences the AI Assistant reads from a profile (T-074): the style and the risk profile, never the
 * capital or the risk percentage beside them.
 *
 * <p>Rule: BR-51, BR-66; SRS 3.2.5; D-64.
 */
class UserTradingProfileTest {

    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserServiceImpl users = new UserServiceImpl(
            mock(UserAccountRepository.class),
            profiles,
            mock(FailedLoginRecorder.class),
            mock(DeviceService.class),
            TransactionOperations.withoutTransaction(),
            mock(ApplicationEventPublisher.class));

    @Test
    void T074_theStyleAndRiskProfileOfTheProfile_areRead() {
        UUID trader = UUID.randomUUID();
        UserProfile profile = UserProfile.createFor(trader, "Swing Trader");
        profile.updateTradingDefaults(new BigDecimal("5000"), new BigDecimal("1.5"), TradingStyle.SWING);
        profile.chooseRiskProfile(RiskProfile.BALANCED);
        when(profiles.findById(trader)).thenReturn(Optional.of(profile));

        assertThat(users.tradingProfileOf(trader))
                .contains(new TradingProfile(TradingStyle.SWING, RiskProfile.BALANCED));
    }

    @Test
    void D64_aProfileThatChoseNothing_hasNoStyle_andTheConservativeRiskProfile() {
        UUID trader = UUID.randomUUID();
        when(profiles.findById(trader)).thenReturn(Optional.of(UserProfile.createFor(trader, "New Trader")));

        assertThat(users.tradingProfileOf(trader)).contains(new TradingProfile(null, RiskProfile.CONSERVATIVE));
    }

    @Test
    void T074_noProfile_readsNothing() {
        assertThat(users.tradingProfileOf(UUID.randomUUID())).isEmpty();
    }
}
