package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperAccountProperties;
import com.cryptopilot.paper.dto.request.TransferRequest;
import com.cryptopilot.paper.dto.response.BalanceResponse;
import com.cryptopilot.paper.dto.response.LedgerEntryResponse;
import com.cryptopilot.paper.dto.response.PaperAccountResponse;
import com.cryptopilot.paper.dto.response.TransferResponse;
import com.cryptopilot.paper.dto.response.WalletResponse;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.model.LedgerQuery;
import com.cryptopilot.paper.model.OpenedAccount;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.RecordedTransfer;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperLedgerEntryRepository;
import com.cryptopilot.paper.service.PaperAccountService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper account use cases of TR-04: open with the virtual funds, show the wallets, move coins between them and
 * read the transaction history.
 *
 * <p>Opening is the only write besides a transfer, and the only one that creates an account; the reads are read-only
 * transactions that answer MSG41 for a Trader who has not opened one. Every change runs under the Trader's paper lock
 * ({@link UserLock}, scope {@value #LOCK_SCOPE}), and every balance change goes through {@link PaperWallets}, which
 * writes its ledger entry. The account is found by the caller, so another Trader's account is never read.
 *
 * <p>An account is never opened without its funds: the coins of the grant are stored by the market module when the
 * symbol synchronisation has not stored them yet, and the account, its balances and its INITIAL_GRANT entries are
 * written in one transaction, so either all of them exist or none does. The funds are granted this once (Q-T5).
 *
 * <p>Rule: TR-04, TR-13; Q-T5.
 */
@Service
@RequiredArgsConstructor
public class PaperAccountServiceImpl implements PaperAccountService {

    /** The scope of the per-Trader lock every change of a paper account is made under. */
    static final String LOCK_SCOPE = "paper";

    /** The quote every wallet is valued in, and the only coin the USDⓈ-M Futures wallet holds. */
    static final String USDT = PaperAccountProperties.FUTURES_COIN;

    private static final String BTC = "BTC";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Logger log = LoggerFactory.getLogger(PaperAccountServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperBalanceRepository balances;
    private final PaperLedgerEntryRepository ledger;
    private final PaperWallets wallets;
    private final FuturesMargins futuresMargins;
    private final FuturesPositionRepository futuresPositions;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final UserLock lock;
    private final PaperAccountProperties properties;
    private final MarketApi market;
    private final PaperEquity equity;
    private final QuoteUsdtRate usdtRates;
    private final Clock clock;

    @Override
    @Transactional
    public OpenedAccount open(UUID userId) {
        Optional<PaperAccount> existing = accounts.findByUserId(userId);
        if (existing.isPresent()) {
            return new OpenedAccount(toResponse(existing.get()), false);
        }
        lock.lock(LOCK_SCOPE, userId);
        // Another request of the same Trader may have opened it while this one waited for the lock.
        existing = accounts.findByUserId(userId);
        if (existing.isPresent()) {
            return new OpenedAccount(toResponse(existing.get()), false);
        }
        grant(new PaperAccount(userId, clock.instant()));
        ledger.flush();
        // Read back, so the opening is the instant its grant was written, as every later read finds it.
        PaperAccount opened = accountOf(userId);
        // Today's PnL counts from the opening, as from 00:00 UTC on the days after (TR-13).
        equity.snapshot(opened);
        log.info("Paper account opened for user {}", userId);
        return new OpenedAccount(toResponse(opened), true);
    }

    @Override
    @Transactional(readOnly = true)
    public PaperAccountResponse account(UUID userId) {
        return toResponse(accountOf(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse wallet(UUID userId, WalletType wallet) {
        PaperAccount account = accountOf(userId);
        List<PaperBalance> held = balances.findByUserIdAndWalletType(account.userId(), wallet);
        Map<UUID, CoinListing> coins =
                coinsById(held.stream().map(PaperBalance::getCoinId).toList());
        // Every price the wallet needs, BTCUSDT for the BTC estimate included, in one read of the cache.
        Set<String> pairs = new HashSet<>();
        pairs.add(BTC + USDT);
        held.forEach(balance -> {
            String asset = assetOf(coins, balance.getCoinId());
            if (!USDT.equals(asset)) {
                pairs.add(asset + USDT);
            }
        });
        Map<String, BigDecimal> prices = market.currentLastPrices(MarketType.SPOT, pairs);
        BigDecimal estimatedUsdt = BigDecimal.ZERO;
        boolean complete = true;
        List<BalanceResponse> rows = new ArrayList<>();
        for (PaperBalance balance : held) {
            String asset = assetOf(coins, balance.getCoinId());
            BigDecimal value = usdtValue(asset, balance.total(), prices);
            if (value != null) {
                estimatedUsdt = estimatedUsdt.add(value);
            } else {
                complete = false;
            }
            rows.add(new BalanceResponse(
                    balance.getCoinId(),
                    asset,
                    Rounding.toAmount(balance.getFreeAmount()),
                    Rounding.toAmount(balance.getLockedAmount()),
                    Rounding.toAmount(balance.total()),
                    value));
        }
        rows.sort(Comparator.comparing(
                        BalanceResponse::usdtValue, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(BalanceResponse::asset));
        BigDecimal usdt = Rounding.toAmount(estimatedUsdt);
        BigDecimal btcPrice = prices.get(BTC + USDT);
        BigDecimal btc =
                btcPrice == null || btcPrice.signum() <= 0 ? null : Rounding.toAmount(Rounding.divide(usdt, btcPrice));
        return new WalletResponse(wallet, List.copyOf(rows), usdt, btc, complete);
    }

    @Override
    @Transactional
    public RecordedTransfer transfer(UUID userId, TransferRequest request) {
        // The account first: a Trader without one is told so (MSG41), whatever the request holds.
        lock.lock(LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        // Only USDT moves either way: the Futures wallet is USD-M and holds nothing else.
        String asset = request.asset().strip().toUpperCase(Locale.ROOT);
        if (!USDT.equals(asset)) {
            throw new FieldValidationException(
                    "only USDT moves between the Spot and the USD-M Futures wallet, not " + asset,
                    Map.of("asset", "MSG15"));
        }
        CoinListing coin = market.coinsBySymbol(Set.of(asset)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("coin " + asset + " of an open account is not stored"));
        if (request.clientTransferId() != null) {
            // Under the lock, so a retry that raced the original waited for it and finds it here.
            Optional<PaperLedgerEntry> made = ledger.findTransfer(account.userId(), request.clientTransferId());
            if (made.isPresent()) {
                return new RecordedTransfer(replayOf(made.get(), coin, request), false);
            }
        }
        if (request.amount().signum() <= 0) {
            throw new IllegalArgumentException("a transfer moves a positive amount, not "
                    + request.amount().toPlainString());
        }
        String key = request.clientTransferId() == null ? UuidV7.next().toString() : request.clientTransferId();
        // A transfer is its two entries: they share its id and its key (D-117).
        if (request.from() == WalletType.FUTURES) {
            requireWithdrawable(account, coin, request.amount());
        }
        Ref ref = Ref.transfer(UuidV7.next(), key);
        wallets.debit(account, request.from(), coin, request.amount(), LedgerEntryType.TRANSFER, ref);
        wallets.credit(account, other(request.from()), coin, request.amount(), LedgerEntryType.TRANSFER, ref);
        feed.futuresAccount(account);
        announceCrossChange(account.userId());
        PaperLedgerEntry debit = ledger.findTransfer(account.userId(), key)
                .orElseThrow(() -> new IllegalStateException("transfer " + key + " was not written"));
        return new RecordedTransfer(transferOf(debit, coin.symbol()), true);
    }

    /**
     * A transfer moves the cross wallet: the matching engine reads the Trader's cross account again after the commit,
     * as for a fill (plan 9.6 rule 3). It reads the whole cross account on one change, so one is told, named after any
     * pair of a cross position; with none, there is nothing to read again. Isolated levels do not move.
     */
    private void announceCrossChange(UUID userId) {
        futuresPositions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN).stream()
                .filter(position -> position.getMarginType() == MarginType.CROSSED)
                .map(FuturesPosition::getPairId)
                .findFirst()
                .ifPresent(pairId -> events.publishEvent(new FuturesAccountChanged(userId, pairId)));
    }

    /**
     * What leaves the Futures wallet is at most its {@code maxWithdrawAmount}: neither the margin its positions and
     * open orders need nor unrealized profit leaves the account (TR-18, plan 4.7). Without a mark price of a pair with a
     * position it cannot be known, and the transfer is refused ({@code MARKET_PRICE_UNAVAILABLE}).
     */
    private void requireWithdrawable(PaperAccount account, CoinListing coin, BigDecimal amount) {
        // What can be withdrawn depends on every cross mark (8.2.1 rule 3).
        futuresMargins.requireFreshCrossMarks(account.userId(), null);
        BigDecimal withdrawable =
                futuresMargins.crossState(account.userId(), null).margin().maxWithdrawAmount();
        if (amount.compareTo(withdrawable) > 0) {
            throw new BusinessException(
                    ErrorCode.PAPER_INSUFFICIENT_BALANCE,
                    "FUTURES " + coin.symbol() + " can transfer out at most " + withdrawable.toPlainString() + ", "
                            + amount.toPlainString() + " asked",
                    coin.symbol(),
                    amount.toPlainString(),
                    withdrawable.toPlainString());
        }
    }

    /** The wallet a transfer out of {@code from} enters: the Spot and the Futures wallet trade places. */
    private static WalletType other(WalletType from) {
        return from == WalletType.SPOT ? WalletType.FUTURES : WalletType.SPOT;
    }

    /**
     * The transfer a retry asks for again. A key reused for a different transfer is a client error, refused rather
     * than answered with a transfer the client did not ask for.
     */
    private static TransferResponse replayOf(PaperLedgerEntry made, CoinListing coin, TransferRequest request) {
        boolean same = made.getCoinId().equals(coin.coinId())
                && made.getWalletType() == request.from()
                && made.getAmount().negate().compareTo(request.amount()) == 0;
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "clientTransferId " + request.clientTransferId() + " was used for transfer " + made.getRefId()
                            + " of other values");
        }
        return transferOf(made, coin.symbol());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> transfers(UUID userId, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize, NEWEST_FIRST);
        Page<PaperLedgerEntry> found = ledger.findTransfers(accountOf(userId).userId(), request);
        Map<UUID, CoinListing> coins = coinsById(
                found.getContent().stream().map(PaperLedgerEntry::getCoinId).toList());
        return PageResponse.of(found, debit -> transferOf(debit, assetOf(coins, debit.getCoinId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> ledger(UUID userId, LedgerQuery query) {
        PageRequest request = Paging.of(query.page(), query.pageSize());
        TimeBounds.requireOrdered(query.from(), query.to());
        Page<PaperLedgerEntry> found = ledger.search(
                accountOf(userId).userId(),
                query.wallet() == null ? EnumSet.allOf(WalletType.class) : EnumSet.of(query.wallet()),
                query.type() == null ? EnumSet.allOf(LedgerEntryType.class) : EnumSet.of(query.type()),
                TimeBounds.fromOrEarliest(query.from()),
                TimeBounds.toOrLatest(query.to()),
                request);
        Map<UUID, CoinListing> coins = coinsById(
                found.getContent().stream().map(PaperLedgerEntry::getCoinId).toList());
        return PageResponse.of(found, entry -> toResponse(entry, assetOf(coins, entry.getCoinId())));
    }

    /** The caller's account; MSG41 when it is not opened yet, so the client knows to open it. */
    private PaperAccount accountOf(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    /**
     * The configured funds, in the order they are configured. The coins are stored first when the symbol
     * synchronisation has not stored them yet, so every coin of the grant is credited.
     */
    private void grant(PaperAccount account) {
        Set<String> symbols = new TreeSet<>(properties.spotGrant().keySet());
        symbols.addAll(properties.futuresGrant().keySet());
        Map<String, CoinListing> coins = market.ensureCoins(symbols).stream()
                .collect(Collectors.toMap(CoinListing::symbol, Function.identity()));
        Ref ref = Ref.to(LedgerRefType.USER, account.userId());
        grant(account, WalletType.SPOT, properties.spotGrant(), coins, ref);
        grant(account, WalletType.FUTURES, properties.futuresGrant(), coins, ref);
    }

    private void grant(
            PaperAccount account,
            WalletType wallet,
            Map<String, BigDecimal> grant,
            Map<String, CoinListing> coins,
            Ref ref) {
        grant.forEach((symbol, amount) -> {
            CoinListing coin = coins.get(symbol);
            if (coin == null) {
                // ensureCoins returns every symbol asked for, so this is a defect, and the whole opening rolls back.
                throw new IllegalStateException("coin " + symbol + " of the paper grant is not stored");
            }
            PaperBalance balance = wallets.grant(account, wallet, coin, amount, ref);
            // The coins of the Spot grant are valued at their price now, the cost their PnL starts from (TR-13);
            // without one, GrantCosts values them later (PT-R13).
            if (wallet == WalletType.SPOT && !QuoteUsdtRate.isUsdt(coin)) {
                UsdtRate price = usdtRates.current(coin);
                if (price.known()) {
                    balance.addCost(price.valueOf(amount), BigDecimal.ZERO);
                }
            }
        });
    }

    /**
     * The value of a balance in USDT at the current Spot last price; zero for an empty balance and {@code null} when
     * no current price is known.
     */
    static BigDecimal usdtValue(String asset, BigDecimal amount, Map<String, BigDecimal> prices) {
        if (amount.signum() == 0) {
            return Rounding.toAmount(BigDecimal.ZERO);
        }
        if (USDT.equals(asset)) {
            return Rounding.toAmount(amount);
        }
        BigDecimal price = prices.get(asset + USDT);
        return price == null ? null : Rounding.toAmount(amount.multiply(price));
    }

    private Map<UUID, CoinListing> coinsById(Collection<UUID> coinIds) {
        Map<UUID, CoinListing> found = new LinkedHashMap<>();
        market.coins(Set.copyOf(coinIds)).forEach(coin -> found.put(coin.coinId(), coin));
        return found;
    }

    // fk_wallet_balance_coin and its siblings keep every coin stored, so a missing one is a defect.
    private static String assetOf(Map<UUID, CoinListing> coins, UUID coinId) {
        CoinListing coin = coins.get(coinId);
        if (coin == null) {
            throw new IllegalStateException("paper coin " + coinId + " is not stored");
        }
        return coin.symbol();
    }

    private static PaperAccountResponse toResponse(PaperAccount account) {
        return new PaperAccountResponse(account.openedAt());
    }

    /** The transfer its debit entry stands for: the wallet it left, the amount it moved and the instant. */
    private static TransferResponse transferOf(PaperLedgerEntry debit, String asset) {
        return new TransferResponse(
                debit.getRefId(),
                debit.getClientTransferId(),
                asset,
                debit.getWalletType(),
                other(debit.getWalletType()),
                Rounding.toAmount(debit.getAmount().negate()),
                debit.getCreatedAt());
    }

    private static LedgerEntryResponse toResponse(PaperLedgerEntry entry, String asset) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getWalletType(),
                asset,
                entry.getEntryType(),
                Rounding.toAmount(entry.getAmount()),
                Rounding.toAmount(entry.getBalanceAfterAmount()),
                entry.getRefType(),
                entry.getRefId(),
                entry.getCreatedAt());
    }
}
