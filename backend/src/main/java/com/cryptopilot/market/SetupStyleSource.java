package com.cryptopilot.market;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the style preset of a signed-in user comes from when a setup score is read. The {@code market} module declares
 * it and the module that owns the user's profile implements it; while no bean implements it, every reader gets the
 * default preset.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53 (the profile's {@code trading_style} is the only source of the preset).
 */
public interface SetupStyleSource {

    /**
     * The preset selected by the user's profile, or empty when the profile selects none.
     *
     * @param userId the signed-in user
     */
    Optional<SetupStyle> styleOf(UUID userId);
}
