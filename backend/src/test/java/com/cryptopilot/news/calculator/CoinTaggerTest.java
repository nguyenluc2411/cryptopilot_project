package com.cryptopilot.news.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.TaggableCoin;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Coin tagging of NSF-10 and TECHNICAL_DESIGN 7.13: upper-case whole-word symbols, any-case whole-word names, short
 * symbols only with a dollar sign, and the first coin of the title primary.
 *
 * <p>Rule: NSF-10; TECHNICAL_DESIGN 7.13.
 */
class CoinTaggerTest {

    private static final UUID BTC = UUID.fromString("019b76da-a800-7c01-8000-000000000001");
    private static final UUID ETH = UUID.fromString("019b76da-a800-7c01-8000-000000000002");
    private static final UUID ONE = UUID.fromString("019b76da-a800-7c01-8000-000000000003");
    private static final UUID BCH = UUID.fromString("019b76da-a800-7c01-8000-000000000004");
    private static final UUID OP = UUID.fromString("019b76da-a800-7c01-8000-000000000005");
    private static final UUID NEAR = UUID.fromString("019b76da-a800-7c01-8000-000000000006");

    private final CoinTagger tagger = CoinTagger.of(List.of(
            new TaggableCoin(BTC, "BTC", List.of("Bitcoin")),
            new TaggableCoin(ETH, "ETH", List.of("Ethereum", "Ether")),
            new TaggableCoin(ONE, "ONE", List.of("ONE", "one")),
            new TaggableCoin(BCH, "BCH", List.of("Bitcoin Cash")),
            new TaggableCoin(OP, "OP", List.of("Optimism")),
            new TaggableCoin(NEAR, "NEAR", List.of("near"))));

    @Test
    void NSF10_aLowerCaseWordThatSpellsASymbol_tagsNothing() {
        assertThat(tagger.tag("One trader says the bottom is near", "Prices are one step from a breakout."))
                .isEmpty();
    }

    @Test
    void NSF10_anUpperCaseSymbolAsAWholeWord_tagsTheCoin() {
        assertThat(tagger.tag("ONE and NEAR rally", null))
                .containsExactly(new CoinTag(ONE, true), new CoinTag(NEAR, false));
        assertThat(tagger.tag("BTCUSDT, xBTC and BTC2 are not BTC alone", null))
                .containsExactly(new CoinTag(BTC, true));
    }

    @Test
    void NSF10_aNameMatchesInAnyCase_asAWholeWord() {
        assertThat(tagger.tag("ETHEREUM upgrade lands; bitcoin steady", null))
                .containsExactly(new CoinTag(ETH, true), new CoinTag(BTC, false));
        assertThat(tagger.tag("Bitcoiners and Etherscan users", null)).isEmpty();
    }

    @Test
    void NSF10_theLongerNameWins_soBitcoinCashIsNotAlsoBitcoin() {
        assertThat(tagger.tag("Bitcoin Cash halving draws miners", null)).containsExactly(new CoinTag(BCH, true));
        assertThat(tagger.tag("Bitcoin and Bitcoin Cash diverge", null))
                .containsExactly(new CoinTag(BTC, true), new CoinTag(BCH, false));
    }

    @Test
    void NSF10_aSymbolShorterThanThree_countsOnlyWithADollarSign_orByItsName() {
        assertThat(tagger.tag("OP ED: why markets fell", null)).isEmpty();
        assertThat(tagger.tag("$OP jumps 12%", null)).containsExactly(new CoinTag(OP, true));
        assertThat(tagger.tag("Optimism airdrop opens", null)).containsExactly(new CoinTag(OP, true));
    }

    @Test
    void NSF10_thePrimaryCoin_isTheFirstTheTitleNames_andTheTextAddsTheRest() {
        assertThat(tagger.tag("Ether ETFs shed $549M as BTC holds", "Bitcoin and ONE traders took profit."))
                .containsExactly(new CoinTag(ETH, true), new CoinTag(BTC, false), new CoinTag(ONE, false));
    }

    @Test
    void NSF10_aTitleThatNamesNoCoin_hasNoPrimary() {
        assertThat(tagger.tag("Markets wait for the Fed", "BTC and ETH traded flat."))
                .containsExactly(new CoinTag(BTC, false), new CoinTag(ETH, false));
    }

    @Test
    void NSF10_aNameTwoCoinsShare_isLeftOut_andNoCoinsTagNothing() {
        CoinTagger shared = CoinTagger.of(
                List.of(new TaggableCoin(BTC, "BTC", List.of("Gold")), new TaggableCoin(ETH, "ETH", List.of("gold"))));

        assertThat(shared.tag("Gold rallies", null)).isEmpty();
        assertThat(shared.tag("BTC rallies", null)).containsExactly(new CoinTag(BTC, true));
        assertThat(CoinTagger.of(List.of()).tag("BTC Bitcoin", "ETH")).isEmpty();
        assertThat(tagger.tag(null, "")).isEmpty();
    }

    @Test
    void NSF10_symbolsWithRegexCharacters_areQuoted() {
        UUID dot = UUID.fromString("019b76da-a800-7c01-8000-000000000007");
        CoinTagger quoted = CoinTagger.of(List.of(new TaggableCoin(dot, "1INCH", List.of("1inch.io"))));

        assertThat(quoted.tag("1INCH lists", null)).containsExactly(new CoinTag(dot, true));
        assertThat(quoted.tag("1inchXio is not the name", null)).isEmpty();
        assertThat(quoted.tag("Read 1inch.io today", null)).containsExactly(new CoinTag(dot, true));
    }
}
