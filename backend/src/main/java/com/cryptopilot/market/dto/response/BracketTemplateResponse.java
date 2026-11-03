package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.LeverageTier;
import java.util.List;

/**
 * A ready-made set of leverage brackets SCR-38 offers to start from, with the maintenance amounts BR-27 gives it.
 *
 * <p>Rule: UC-46; BR-26, BR-27.
 *
 * @param id {@code MAJOR}, {@code ALTCOIN} or {@code SMALL_CAP}
 * @param firstLeverage the highest leverage of the template; a pair whose ceiling is above it cannot use it (BR-26)
 * @param brackets the brackets as they would be saved
 */
public record BracketTemplateResponse(String id, int firstLeverage, List<LeverageTier> brackets) {}
