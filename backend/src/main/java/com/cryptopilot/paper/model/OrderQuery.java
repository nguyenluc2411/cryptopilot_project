package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.OrderStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The filters and the page of the order or trade history. Every filter is optional.
 *
 * @param pairId one pair, or {@code null} for every pair
 * @param status one status, or {@code null} for every status; ignored by the trade history
 * @param from the earliest instant, inclusive, or {@code null}
 * @param to the latest instant, exclusive, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record OrderQuery(UUID pairId, OrderStatus status, Instant from, Instant to, Integer page, Integer pageSize) {}
