<#
.SYNOPSIS
    Loads the WF-01 demo data into a running backend through its API (DEMO-01): the pairs and leverage brackets, and
    the three ACTIVE plans of demo.free. Safe to run again: what is already in place is left as it is.

.DESCRIPTION
    The demo accounts themselves come from Flyway (backend/src/main/resources/db/demo), applied by the dev profile and
    by the demo profile (SPRING_PROFILES_ACTIVE=prod,demo on staging). This script does the rest, as an administrator
    and as demo.free would do it on their screens, so every value passes the same checks it would there:

      1. As the administrator, for each pair of wf01.json: the Futures leverage brackets (UC-46; T-085 checks them:
         contiguous from 0, MMR in (0, 1), leverage never rising, maintenance amount by BR-27), then the pair ACTIVE
         on Spot and Futures with its leverage ceiling (UC-45). Each is written only when it differs from the file.
      2. As demo.free: Spot LIMIT LONG plans, saved and activated (UC-16, UC-18), until the account holds the count of
         ACTIVE plans in wf01.json, so a further activation meets ACTIVE_PLAN_MAX (BR-62, MSG27). Each entry is placed
         far below the current Spot price, so it waits in the book.

    Nothing here is production data: it writes only through endpoints an administrator and a Trader have anyway, and a
    production deployment has no demo account to sign in with.

    Passwords are read from the environment, never from a file of the repository:
      ADMIN_PASSWORD   the password of admin@cryptopilot.invalid (the one ADMIN_PASSWORD_HASH was made from)
      DEMO_PASSWORD    the password of the demo accounts (the one DEMO_PASSWORD_HASH was made from)
    Optional: DEMO_ADMIN_EMAIL (default admin@cryptopilot.invalid), CRYPTOPILOT_BASE_URL (default http://localhost:8080).

    Runs on Windows PowerShell 5.1 and PowerShell 7 (pwsh) on Windows and Linux. Exits non-zero on the first failure.

.EXAMPLE
    $env:ADMIN_PASSWORD = '...'; $env:DEMO_PASSWORD = '...'
    .\deploy\demo\load-wf01.ps1 -BaseUrl https://staging.example.com
#>
[CmdletBinding()]
param(
    [string]$BaseUrl,
    [string]$DataFile,
    # How long to wait for the first Spot price of a pair this run switched on.
    [int]$PriceWaitSeconds = 120
)

$ErrorActionPreference = 'Stop'

if (-not $BaseUrl) {
    $BaseUrl = if ($env:CRYPTOPILOT_BASE_URL) { $env:CRYPTOPILOT_BASE_URL } else { 'http://localhost:8080' }
}
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not $DataFile) {
    $DataFile = Join-Path $PSScriptRoot 'wf01.json'
}
$adminEmail = if ($env:DEMO_ADMIN_EMAIL) { $env:DEMO_ADMIN_EMAIL } else { 'admin@cryptopilot.invalid' }
foreach ($required in @('ADMIN_PASSWORD', 'DEMO_PASSWORD')) {
    if (-not (Get-Item "env:$required" -ErrorAction SilentlyContinue).Value) {
        throw "$required is not set. It is the password the matching *_PASSWORD_HASH was made from; see deploy/README.md."
    }
}

$invariant = [Globalization.CultureInfo]::InvariantCulture
$data = Get-Content -Raw -Encoding UTF8 -Path $DataFile | ConvertFrom-Json

# A decimal from the data file (a string) or from a response (a number, decimal or double depending on the shell),
# read without the culture of the machine.
function ConvertTo-Decimal($value) {
    return [decimal]::Parse([string]$value, [Globalization.NumberStyles]::Float, $invariant)
}

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

function Get-AccessToken([string]$Email, [string]$Password) {
    $session = Invoke-Api -Method Post -Path '/api/v1/auth/login' -Body @{ email = $Email; password = $Password; rememberMe = $false }
    return $session.accessToken
}

function Test-SameBrackets($stored, $wanted) {
    if (@($stored).Count -ne @($wanted).Count) {
        return $false
    }
    for ($index = 0; $index -lt @($wanted).Count; $index++) {
        $have = @($stored)[$index]
        $want = @($wanted)[$index]
        if ((ConvertTo-Decimal $have.notionalFloor) -ne (ConvertTo-Decimal $want.notionalFloor) -or
            (ConvertTo-Decimal $have.notionalCap) -ne (ConvertTo-Decimal $want.notionalCap) -or
            [int]$have.maxLeverage -ne [int]$want.maxLeverage -or
            (ConvertTo-Decimal $have.maintenanceMarginRate) -ne (ConvertTo-Decimal $want.maintenanceMarginRate) -or
            (ConvertTo-Decimal $have.maintenanceAmount) -ne (ConvertTo-Decimal $want.maintenanceAmount)) {
            return $false
        }
    }
    return $true
}

# The largest multiple of the tick not above the value: BR-30 refuses a price off the tick rather than moving it.
function Get-OnTick([decimal]$value, [decimal]$tick) {
    return [decimal]::Floor($value / $tick) * $tick
}

Write-Host "WF-01 demo data -> $BaseUrl"

# 1. Pairs and leverage brackets, as the administrator.
$adminToken = Get-AccessToken $adminEmail $env:ADMIN_PASSWORD
$pairs = @{}
foreach ($wanted in $data.pairs) {
    $symbol = $wanted.symbol
    $pair = @(Invoke-Api -Method Get -Path "/api/v1/admin/pairs?q=$symbol" -Token $adminToken) |
        Where-Object { $_.symbol -eq $symbol } |
        Select-Object -First 1
    if (-not $pair) {
        throw "$symbol is not a pair yet. Start the backend and wait for the symbol synchronisation (NSF-01), or register it on SCR-37, then run this again."
    }

    $brackets = Invoke-Api -Method Get -Path "/api/v1/admin/pairs/$($pair.pairId)/brackets" -Token $adminToken
    if (Test-SameBrackets $brackets.brackets $wanted.brackets) {
        Write-Host "  $symbol brackets: unchanged ($(@($wanted.brackets).Count))"
    } else {
        $rows = @($wanted.brackets | ForEach-Object {
            @{
                notionalFloor = ConvertTo-Decimal $_.notionalFloor
                notionalCap = ConvertTo-Decimal $_.notionalCap
                maxLeverage = [int]$_.maxLeverage
                maintenanceMarginRate = ConvertTo-Decimal $_.maintenanceMarginRate
                maintenanceAmount = ConvertTo-Decimal $_.maintenanceAmount
            }
        })
        Invoke-Api -Method Put -Path "/api/v1/admin/pairs/$($pair.pairId)/brackets" -Token $adminToken -Body @{ brackets = $rows } | Out-Null
        Write-Host "  $symbol brackets: replaced ($($rows.Count))"
    }

    if ($pair.status -eq 'ACTIVE' -and
        [bool]$pair.spotEnabled -eq [bool]$wanted.spotEnabled -and
        [bool]$pair.futuresEnabled -eq [bool]$wanted.futuresEnabled -and
        $pair.maxLeverage -eq [int]$wanted.maxLeverage) {
        Write-Host "  $symbol pair: unchanged"
    } else {
        $pair = Invoke-Api -Method Put -Path "/api/v1/admin/pairs/$($pair.pairId)" -Token $adminToken -Body @{
            status = 'ACTIVE'
            spotEnabled = [bool]$wanted.spotEnabled
            futuresEnabled = [bool]$wanted.futuresEnabled
            maxLeverage = [int]$wanted.maxLeverage
            displayOrder = [int]$pair.displayOrder
        }
        Write-Host "  $symbol pair: ACTIVE, Spot $($pair.spotEnabled), Futures $($pair.futuresEnabled), up to $($pair.maxLeverage)x"
    }
    $pairs[$symbol] = $pair
}

# 2. The ACTIVE plans of demo.free, as demo.free.
$plans = $data.freePlans
$freeToken = Get-AccessToken $plans.account $env:DEMO_PASSWORD
$active = (Invoke-Api -Method Get -Path '/api/v1/plans?tab=ACTIVE&pageSize=100' -Token $freeToken).total
if ($active -ge [int]$plans.count) {
    Write-Host "  $($plans.account): $active ACTIVE plans, unchanged"
} else {
    # PowerShell 7 reads an ISO 8601 string of a JSON file as a date; the API wants it back as one.
    $expiresAt = if ($plans.expiresAt -is [datetime]) {
        $plans.expiresAt.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', $invariant)
    } else {
        [string]$plans.expiresAt
    }
    $prices = @{}
    for ($index = [int]$active; $index -lt [int]$plans.count; $index++) {
        $symbol = @($plans.symbols)[$index]
        $pair = $pairs[$symbol]
        if (-not $pair) {
            throw "freePlans names $symbol, which is not one of the pairs of $DataFile."
        }
        if (-not $prices.ContainsKey($symbol)) {
            # A pair switched on a moment ago has no stream yet (NSF-03 opens one per enabled pair): wait for its price.
            $deadline = (Get-Date).AddSeconds($PriceWaitSeconds)
            while ($true) {
                $stats = Invoke-Api -Method Get -Path "/api/v1/market/spot/$symbol/stats" -Token $freeToken
                if ($null -ne $stats.ticker -and $null -ne $stats.ticker.lastPrice) {
                    break
                }
                if ((Get-Date) -ge $deadline) {
                    throw "No Spot price for $symbol yet after $PriceWaitSeconds s. Check that the market stream (NSF-03) runs, then run this again."
                }
                Start-Sleep -Seconds 5
            }
            $prices[$symbol] = ConvertTo-Decimal $stats.ticker.lastPrice
        }
        $last = $prices[$symbol]
        $tick = ConvertTo-Decimal $pair.spot.tickSize
        $plan = Invoke-Api -Method Post -Path '/api/v1/plans' -Token $freeToken -Body @{
            pairId = $pair.pairId
            market = 'SPOT'
            direction = 'LONG'
            entryType = 'LIMIT'
            entryPrice = Get-OnTick ($last * (ConvertTo-Decimal $plans.entryFactor)) $tick
            stopLoss = Get-OnTick ($last * (ConvertTo-Decimal $plans.stopLossFactor)) $tick
            takeProfit = Get-OnTick ($last * (ConvertTo-Decimal $plans.takeProfitFactor)) $tick
            expiresAt = $expiresAt
            note = $plans.note
            activate = $true
        }
        if ($plan.status -ne 'ACTIVE') {
            throw "The plan on $symbol was saved as $($plan.status), not ACTIVE: $(ConvertTo-Json -InputObject $plan.warnings -Depth 5 -Compress)"
        }
        Write-Host "  $($plans.account): plan $($index + 1) of $($plans.count) ACTIVE, $symbol LIMIT at $($plan.entryPrice) (last $last)"
    }
}

Write-Host 'WF-01 demo data loaded.'
