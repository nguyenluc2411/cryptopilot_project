package com.cryptopilot.ai.model.enums;

/**
 * What a conversation is about ({@code ck_ai_conversation_topic}, SRS 3.9.1). SCR-29 keeps MARKET and MY_TRADING in
 * two tabs, each with its own conversations; the floating chat keeps the GENERAL ones. A conversation keeps its topic,
 * and a question may only carry the data of that topic (BR-51):
 *
 * <ul>
 *   <li>GENERAL: any context, usually none (a knowledge question);
 *   <li>MARKET: the analysis of one pair on one market and timeframe, never a plan or the performance summary;
 *   <li>MY_TRADING: one of the Trader's plans (analysed on its own pair) and/or the performance summary, never a pair
 *       of its own.
 * </ul>
 */
public enum ConversationTopic {
    GENERAL,
    MARKET,
    MY_TRADING
}
