package com.cryptopilot.market.model;

/**
 * The weights, in percent, of the setup score components on one market.
 *
 * <p>Rule: BR-13; D-53.
 *
 * @param trend weight of the trend component
 * @param momentum weight of the momentum component
 * @param volume weight of the volume component
 * @param level weight of the level component
 * @param derivatives weight of the derivatives component, {@code null} on Spot where it does not exist
 */
public record ComponentWeights(int trend, int momentum, int volume, int level, Integer derivatives) {

    /** The sum of the weights present. */
    public int total() {
        return trend + momentum + volume + level + (derivatives == null ? 0 : derivatives);
    }
}
