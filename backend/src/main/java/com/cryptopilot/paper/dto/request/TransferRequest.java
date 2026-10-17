package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.WalletType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A move of USDT out of a wallet into the other one. Only USDT moves between them: the Futures wallet is USDⓈ-M.
 *
 * @param asset the coin's symbol; {@code USDT}
 * @param from the wallet it leaves
 * @param amount more than zero, at most 8 decimals as the balances hold
 * @param clientTransferId the idempotency key: a request sent again with the same key, e.g. after a timeout, returns the
 *     transfer already made instead of moving the amount twice; up to 64 letters, digits and {@code . _ : -}. Optional;
 *     without it a retry is a second transfer
 */
public record TransferRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG01")
        String asset,

        @NotNull(message = "MSG01") WalletType from,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal amount,

        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "MSG15")
        String clientTransferId) {}
