# Futures golden cases

Shared expected values for the paper Futures arithmetic. The backend runs every file here in
`FuturesGoldenTest` (JUnit); the web client runs the same files against `futures-math.ts` (Vitest).
Both sides must produce these numbers, so a change to a case is a change to both implementations.

## Format

Each `*.json` file (except `brackets.json`) is an object with a `cases` array. A case is:

| Field | Meaning |
|---|---|
| `id` | Stable id, `<GROUP>-<NN>`, used in test names |
| `source` | Where the numbers come from (worked example, Binance article) |
| `fn` | The function under test, one of the names below |
| `input` | Arguments by name; decimals are strings, leverage is a number |
| `expect` | Expected results by name; decimals are strings, `null` means "no value" |
| `tolerance` | Optional absolute tolerance for decimal results (default exact) |
| `error` | Optional: `true` when the call must be refused |

`brackets` in an input names a bracket set of `brackets.json`.

## Functions

| `fn` | Input | Expect |
|---|---|---|
| `bracket` | `brackets`, `notional` | `bracketNo`, `maintenanceAmount` |
| `maintenanceMargin` | `brackets`, `notional` | `value` |
| `maxNotionalAtLeverage` | `brackets`, `leverage` | `value` |
| `unrealizedPnl` | `direction`, `quantity`, `entryPrice`, `markPrice` | `value` |
| `applyFill` | `quantityBefore`, `entryPriceBefore`, `side`, `quantity`, `price` | `closedQuantity`, `realizedPnl`, `openedQuantity`, `quantityAfter`, `entryPriceAfter` |
| `orderCost` | `side`, `quantity`, `limitPrice` (null = MARKET), `lastPrice`, `markPrice`, `leverage` | `value` |
| `isolatedFillMargin` | `direction`, `quantity`, `fillPrice`, `markPrice`, `leverage` | `value` |
| `openOrderInitialMargin` | `positionQuantity`, `orders` (`side`, `quantity`, `cost`) | `value` |
| `accountMargin` | `crossWalletBalance`, `crossUnrealizedPnl`, `crossPositionInitialMargin`, `openOrderInitialMargin` | `availableBalance`, `crossFreeCash`, `maxWithdrawAmount` |
| `maxOpenQuantity` | `side`, `limitPrice`, `lastPrice`, `markPrice`, `leverage`, `budget`, `positionQuantity`, `brackets`, `stepSize` | `value` |
| `maxRemovableMargin` | `isolatedWallet`, `unrealizedPnl`, `markNotional`, `leverage` | `value` |
| `marginRatio` | `maintenanceMargin`, `marginBalance` | `value` |
| `liquidationPrice` | `direction`, `quantity`, `entryPrice`, `walletBalance`, `otherMaintenanceMargin`, `otherUnrealizedPnl`, `brackets`, `tickSize` | `value` |
| `closeFlow` | `isolatedWallet`, `closedQuantity`, `positionQuantity`, `realizedPnl`, `fee` | `branch`, `isolatedWalletChange`, `crossChange`, `insuranceCover` |
| `fundingFlow` | `isolatedWallet`, `fundingFee` | `branch`, `isolatedWalletChange`, `crossChange`, `insuranceCover` |
| `fundingFee` | `direction`, `quantity`, `markPrice`, `fundingRate` | `value` |
| `liquidationFee` | `notional`, `feeRate`, `remaining` | `value` |

Rounding: amounts to 8 decimals half-even, entry prices to 12 decimals, a liquidation price to the tick
(long up, short down), a quantity down to the step.
