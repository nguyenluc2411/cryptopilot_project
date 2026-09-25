package com.cryptopilot.user.dto.response;

import com.cryptopilot.user.entity.TradingStyle;
import java.math.BigDecimal;

/**
 * What the Profile and Notifications tabs of SCR-07 show (SRS 3.2.5).
 *
 * <p>The address is included although it is not editable here, because SCR-07 is where a person sees
 * which address the account signs in with. Nothing else about the account is: no role, no status and
 * no key, which a profile screen has no use for.
 *
 * <p>The two figures are {@link BigDecimal} and reach the client as strings (TECHNICAL_DESIGN 5.4),
 * and any of the trading defaults may be {@code null} — a profile that has never said anything is
 * simply empty, and the plan form starts empty with it.
 *
 * <p>Rule: SRS UC-06, UC-08, section 3.2.5.
 *
 * @param email the address the account signs in with
 * @param displayName the name shown wherever the account appears to others
 * @param avatarUrl where the avatar image is stored, or {@code null}
 * @param defaultCapital the capital a new plan starts from, or {@code null}
 * @param defaultRiskPercent the risk percentage a new plan starts from, or {@code null}
 * @param tradingStyle how long the account usually holds a position, or {@code null}
 * @param notifyEmail whether notifications also go out by mail
 * @param notifyPush whether notifications also go out as push messages
 */
public record ProfileResponse(
        String email,
        String displayName,
        String avatarUrl,
        BigDecimal defaultCapital,
        BigDecimal defaultRiskPercent,
        TradingStyle tradingStyle,
        boolean notifyEmail,
        boolean notifyPush) {}
