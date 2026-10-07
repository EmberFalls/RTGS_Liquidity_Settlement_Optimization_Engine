param([string]$BaseUrl = 'http://localhost:8080', [string]$Maven = 'mvn')
$ErrorActionPreference = 'Stop'
$prefix = 'recovery-' + [Guid]::NewGuid().ToString('N').Substring(0,8) + '-'
function Send-Rtgs([string]$Path,$Body) { Invoke-RestMethod -Method Post -Uri ($BaseUrl+$Path) -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Compress) }
function Wait-Rtgs([string]$Path,[string]$Expected) {
    $limit=(Get-Date).AddSeconds(60)
    do {
        try {
            $value=Invoke-RestMethod -Uri ($BaseUrl+$Path)
            if (($Path -eq '/health' -and $value.status -eq 'UP') -or ($value.status -eq $Expected)) { return $value }
        } catch { }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $limit)
    throw "Timed out: $Path"
}
function Invoke-Compose([string[]]$Arguments) {
    & docker compose @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Compose failed: $Arguments" }
}
foreach($id in @('A','B')) { Send-Rtgs '/api/participants' @{participantId=$prefix+$id;displayName='Recovery '+$id;openingLiquidityMinor=10000} | Out-Null }
Invoke-Compose @('stop','app')
try {
    & $Maven -B -ntp compile exec:java '-Dexec.mainClass=com.rtgs.benchmark.KafkaBacklogProducerMain' "-Dexec.args=$prefix 100"
    if($LASTEXITCODE -ne 0) { throw 'Backlog publish failed' }
} finally { Invoke-Compose @('start','app') }
$recoveryTimer=[Diagnostics.Stopwatch]::StartNew()
Wait-Rtgs '/health' 'UP' | Out-Null
Wait-Rtgs ('/api/payments/'+$prefix+'p99') 'SETTLED' | Out-Null
$recoveryTimer.Stop()
$a=Invoke-RestMethod -Uri ($BaseUrl+'/api/liquidity/'+$prefix+'A')
$b=Invoke-RestMethod -Uri ($BaseUrl+'/api/liquidity/'+$prefix+'B')
if($a.availableMinor -ne 9900 -or $b.availableMinor -ne 10100) { throw 'Backlog duplicate or liquidity invariant failed' }
Invoke-Compose @('restart','app')
Wait-Rtgs '/health' 'UP' | Out-Null
if((Invoke-RestMethod -Uri ($BaseUrl+'/api/liquidity/'+$prefix+'A')).availableMinor -ne 9900) { throw 'Restart lost durable balance' }
Invoke-Compose @('exec','-T','redis','redis-cli','FLUSHDB')
if(-not (Send-Rtgs '/api/admin/redis/rebuild' @{}).rebuilt) { throw 'Redis rebuild failed' }
Invoke-Compose @('stop','redis')
$outage=@{paymentId=$prefix+'outage';sourceParticipantId=$prefix+'A';destinationParticipantId=$prefix+'B';amountMinor=5;priority='NORMAL';createdAt='2026-01-01T00:00:00Z'}
try {
    Send-Rtgs '/api/payments' $outage | Out-Null
    Wait-Rtgs ('/api/payments/'+$outage.paymentId) 'SETTLED' | Out-Null
} finally { Invoke-Compose @('start','redis') }
Start-Sleep -Seconds 1
if(-not (Send-Rtgs '/api/admin/redis/rebuild' @{}).rebuilt) { throw 'Redis outage recovery failed' }
Send-Rtgs '/api/payments' $outage | Out-Null
Start-Sleep -Milliseconds 500
if((Invoke-RestMethod -Uri ($BaseUrl+'/api/liquidity/'+$prefix+'A')).availableMinor -ne 9895) { throw 'Outage duplicate changed money' }
$failId=$prefix+'fail'
$failureSql=@'
CREATE OR REPLACE FUNCTION rtgs_runtime_fail() RETURNS trigger AS $$
BEGIN
  IF NEW.payment_id = '__PAYMENT_ID__' THEN RAISE EXCEPTION 'injected transaction failure'; END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER rtgs_runtime_fail BEFORE INSERT ON settlements FOR EACH ROW EXECUTE FUNCTION rtgs_runtime_fail();
'@.Replace('__PAYMENT_ID__',$failId)
Invoke-Compose @('exec','-T','postgres','psql','-U','rtgs','-d','rtgs','-v','ON_ERROR_STOP=1','-c',$failureSql)
try {
    $failed=@{paymentId=$failId;sourceParticipantId=$prefix+'A';destinationParticipantId=$prefix+'B';amountMinor=7;priority='NORMAL'}
    Send-Rtgs '/api/payments' $failed | Out-Null
    $limit=(Get-Date).AddSeconds(30)
    do {
        $dlq=& docker compose exec -T postgres psql -U rtgs -d rtgs -Atc "SELECT count(*) FROM processing_failures WHERE failure_message LIKE '%$failId%'"
        if([int]$dlq -gt 0) { break }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $limit)
    if([int]$dlq -eq 0) { throw 'Failed transaction did not reach DLQ' }
    $fake=& docker compose exec -T postgres psql -U rtgs -d rtgs -Atc "SELECT count(*) FROM payments WHERE payment_id='$failId'"
    if([int]$fake -ne 0) { throw 'Failed transaction left fake committed payment' }
    if((Invoke-RestMethod -Uri ($BaseUrl+'/api/liquidity/'+$prefix+'A')).availableMinor -ne 9895) { throw 'Failed transaction changed liquidity' }
} finally {
    Invoke-Compose @('exec','-T','postgres','psql','-U','rtgs','-d','rtgs','-c','DROP TRIGGER IF EXISTS rtgs_runtime_fail ON settlements; DROP FUNCTION IF EXISTS rtgs_runtime_fail();')
}
$result=@{result='PASS';runPrefix=$prefix;backlogUniquePayments=100;backlogPublishedRecords=120;backlogRecoverySeconds=$recoveryTimer.Elapsed.TotalSeconds;duplicateFinancialEffects=0;consumerRestart=$true;applicationRestart=$true;redisDeletionRebuild=$true;redisOutageRecovery=$true;postgresTransactionFailureRollback=$true}
New-Item -ItemType Directory -Path 'docs/verification' -Force | Out-Null
$result | ConvertTo-Json | Set-Content -LiteralPath 'docs/verification/failure-recovery.json'
$result | ConvertTo-Json
