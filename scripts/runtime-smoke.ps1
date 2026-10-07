param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$prefix = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
function Send-Rtgs([string]$Path, $Body) {
    Invoke-RestMethod -Method Post -Uri ($BaseUrl + $Path) -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Compress)
}
function Wait-Settled([string]$Id) {
    $limit = (Get-Date).AddSeconds(30)
    do {
        try {
            $payment = Invoke-RestMethod -Uri ($BaseUrl + '/api/payments/' + $Id)
            if ($payment.status -eq 'SETTLED') { return $payment }
        } catch { }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $limit)
    throw "Payment $Id did not settle"
}
$dependencies = Invoke-RestMethod -Uri ($BaseUrl + '/api/admin/dependencies')
if (-not ($dependencies.postgres -and $dependencies.kafka -and $dependencies.redis)) { throw 'Dependency check failed' }
foreach ($id in @('A','B','C')) {
    $opening = if ($id -eq 'A') { 100 } else { 0 }
    Send-Rtgs '/api/participants' @{ participantId=$prefix+$id; displayName='Smoke Bank '+$id; openingLiquidityMinor=$opening } | Out-Null
}
$payment = @{ paymentId=$prefix+'p'; sourceParticipantId=$prefix+'A'; destinationParticipantId=$prefix+'B'; amountMinor=40; priority='NORMAL'; createdAt='2026-01-01T00:00:00Z' }
Send-Rtgs '/api/payments' $payment | Out-Null
Wait-Settled $payment.paymentId | Out-Null
Send-Rtgs '/api/payments' $payment | Out-Null
Start-Sleep -Milliseconds 500
$a = Invoke-RestMethod -Uri ($BaseUrl + '/api/liquidity/' + $prefix + 'A')
$b = Invoke-RestMethod -Uri ($BaseUrl + '/api/liquidity/' + $prefix + 'B')
if ($a.availableMinor -ne 60 -or $b.availableMinor -ne 40) { throw 'Duplicate changed financial state' }
$rebuilt = Send-Rtgs '/api/admin/redis/rebuild' @{}
if (-not $rebuilt.rebuilt) { throw 'Redis rebuild failed' }
@{ result='PASS'; dependencies=$dependencies; duplicateFinancialEffects=0; sourceLiquidity=$a.availableMinor; destinationLiquidity=$b.availableMinor } | ConvertTo-Json
