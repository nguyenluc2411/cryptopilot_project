<#
.SYNOPSIS
    Loads the demo trading data of the defence into a running backend through its API (TR-33): demo.pro's paper
    account with a Spot fill, a Spot order waiting in the book, USDT moved to Futures, a Futures position and a Futures
    order waiting. Safe to run again: every order and the transfer carry a client id, so a second run finds them.

.DESCRIPTION
    Run after load-wf01.ps1, which switches the pairs on. Everything goes through the endpoints demo.pro has on the
    trading screens, so every value passes the checks it would pass there (BR-70, BR-71, BR-74):

      1. The paper account is opened (BR-68: the starting funds are granted once; a second opening answers 200).
      2. Spot BTCUSDT: a MARKET buy for 100 USDT (fills at once against the live book), and a LIMIT buy of 0.001 BTC
         at 80 % of the last trade price, which waits.
      3. 1 000 USDT from Spot to Futures (one transfer, by its clientTransferId).
      4. Futures BTCUSDT at 10x: a MARKET buy of 0.002 BTC (opens a long position), and a LIMIT buy of 0.002 BTC at
         80 % of the last price, which waits.

    The prices are the live ones of the exchange, read just before; nothing here is invented market data.

    The password is read from the environment, never from a file of the repository:
      DEMO_PASSWORD    the password of the demo accounts (the one DEMO_PASSWORD_HASH was made from)
    Optional: DEMO_EMAIL (default demo.pro@cryptopilot.invalid), CRYPTOPILOT_BASE_URL (default http://localhost:8080).

    Runs on Windows PowerShell 5.1 and PowerShell 7 (pwsh). Exits non-zero on the first failure.

.EXAMPLE
    $env:DEMO_PASSWORD = '...'
    .\deploy\demo\load-paper.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl,
    [string]$Symbol = 'BTCUSDT'
)

$ErrorActionPreference = 'Stop'

if (-not $BaseUrl) {
    $BaseUrl = if ($env:CRYPTOPILOT_BASE_URL) { $env:CRYPTOPILOT_BASE_URL } else { 'http://localhost:8080' }
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$email = if ($env:DEMO_EMAIL) { $env:DEMO_EMAIL } else { 'demo.pro@cryptopilot.invalid' }
if (-not $env:DEMO_PASSWORD) {
    throw 'DEMO_PASSWORD is not set. It is the password DEMO_PASSWORD_HASH was made from; see deploy/README.md.'
}
$invariant = [Globalization.CultureInfo]::InvariantCulture

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token)
    $arguments = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = @{}; UseBasicParsing = $true }
    if ($Token) {
        $arguments.Headers['Authorization'] = "Bearer $Token"
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 10 -Compress))
    }
    try {
        return Invoke-RestMethod @arguments
    } catch {
        throw "$Method $Path failed: $($_.Exception.Message) $($_.ErrorDetails.Message)"
    }
}

# The last trade price of the pair on the market, from the public trades of the backend (TR-03).
function Get-LastPrice([string]$Market) {
    $trades = @((Invoke-Api -Method Get -Path "/api/v1/market/$Market/$Symbol/trades").trades)
    if ($trades.Count -eq 0) {
        throw "No $Market trade of $Symbol yet: start the streams (dev profile) and run load-wf01.ps1 first."
    }
    return [decimal]::Parse([string]$trades[0].price, [Globalization.NumberStyles]::Float, $invariant)
}

# 80 % of the price, down to whole USDT: a limit far enough below the market to wait, on any tick of 0.01 or 1.
function Get-WaitingPrice([decimal]$last) {
    return ([decimal]::Floor($last * 0.8)).ToString($invariant)
}

Write-Host "Demo trading data -> $BaseUrl as $email"
$session = Invoke-Api -Method Post -Path '/api/v1/auth/login' -Body @{ email = $email; password = $env:DEMO_PASSWORD; rememberMe = $false }
$token = $session.accessToken

# 1. The account.
Invoke-Api -Method Post -Path '/api/v1/paper/account' -Token $token | Out-Null
Write-Host '  paper account open'

# 2. Spot.
$pairs = Invoke-Api -Method Get -Path '/api/v1/market/pairs?market=spot&pageSize=100'
$pair = @($pairs.items) | Where-Object { $_.symbol -eq $Symbol } | Select-Object -First 1
if (-not $pair) {
    throw "$Symbol is not enabled on Spot: run load-wf01.ps1 first."
}
$spotLast = Get-LastPrice 'spot'
Invoke-Api -Method Post -Path '/api/v1/paper/orders' -Token $token -Body @{
    pairId = $pair.pairId; market = 'SPOT'; side = 'BUY'; type = 'MARKET'; quoteOrderQty = 100; clientOrderId = 'demo-spot-market-1'
} | Out-Null
Invoke-Api -Method Post -Path '/api/v1/paper/orders' -Token $token -Body @{
    pairId = $pair.pairId; market = 'SPOT'; side = 'BUY'; type = 'LIMIT'; timeInForce = 'GTC'
    price = (Get-WaitingPrice $spotLast); quantity = '0.001'; clientOrderId = 'demo-spot-limit-1'
} | Out-Null
Write-Host "  Spot: a MARKET buy for 100 USDT and a LIMIT buy waiting at $(Get-WaitingPrice $spotLast)"

# 3. USDT to Futures.
Invoke-Api -Method Post -Path '/api/v1/paper/transfers' -Token $token -Body @{
    asset = 'USDT'; from = 'SPOT'; amount = 1000; clientTransferId = 'demo-transfer-1'
} | Out-Null
Write-Host '  1000 USDT moved to Futures'

# 4. Futures.
$futuresLast = Get-LastPrice 'futures'
Invoke-Api -Method Post -Path '/api/v1/paper/futures/leverage' -Token $token -Body @{ symbol = $Symbol; leverage = 10 } | Out-Null
Invoke-Api -Method Post -Path '/api/v1/paper/futures/order' -Token $token -Body @{
    symbol = $Symbol; side = 'BUY'; type = 'MARKET'; quantity = '0.002'; newClientOrderId = 'demo-futures-market-1'
} | Out-Null
Invoke-Api -Method Post -Path '/api/v1/paper/futures/order' -Token $token -Body @{
    symbol = $Symbol; side = 'BUY'; type = 'LIMIT'; timeInForce = 'GTC'; price = (Get-WaitingPrice $futuresLast)
    quantity = '0.002'; newClientOrderId = 'demo-futures-limit-1'
} | Out-Null
Write-Host "  Futures: a long of 0.002 at 10x and a LIMIT buy waiting at $(Get-WaitingPrice $futuresLast)"
Write-Host 'Done.'
