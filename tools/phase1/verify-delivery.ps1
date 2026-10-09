param(
    [Parameter(Mandatory = $true)][string]$PlaywrightModulePath,
    [string]$BaseUrl = 'http://127.0.0.1:18010/'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'remote-json-response.ps1')
$taskProtocol = ConvertFrom-TaskJsonResponse -Response @(& (Join-Path $PSScriptRoot 'test-remote-json-response.ps1'))
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRemoteRoot = '/home/data_dev_zhm/dataease-phase1-test/w02-security'
$taskHelper = "$taskRemoteRoot/source/tools/phase1/login-test-context.py"
$taskRunId = [guid]::NewGuid().ToString()
$taskOutput = Join-Path $taskWorkspace "output/playwright/delivery/$taskRunId"
$taskBase = [uri]$BaseUrl
if ($taskBase.Scheme -ne 'http' -or $taskBase.Host -ne '127.0.0.1' -or
    $taskBase.Port -notin @(18010, 18110) -or $taskBase.AbsolutePath -ne '/' -or
    $taskBase.Query -or $taskBase.Fragment) { throw 'Unapproved browser target.' }
if (-not (Test-Path -LiteralPath $PlaywrightModulePath)) { throw 'Existing Playwright module required; no automatic installation.' }
$taskProcesses = Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'"
$taskForward = '127.0.0.1:' + $taskBase.Port + ':127.0.0.1:18100'
if (-not ($taskProcesses | Where-Object {
    $_.CommandLine -match [regex]::Escape($taskForward) -and
    $_.CommandLine -match 'data_dev_zhm@124\.221\.139\.87'
})) { throw 'Verified dedicated SSH forwarding required.' }

function Get-TaskJson([string]$TaskArguments) {
    $taskJson = & ssh -o BatchMode=yes -o StrictHostKeyChecking=yes data_dev_zhm@124.221.139.87 "python3 -E $taskHelper $TaskArguments"
    if ($LASTEXITCODE -ne 0) { throw 'Remote verification rejected; inspect dedicated logs.' }
    return (ConvertFrom-TaskJsonResponse -Response @($taskJson))
}

function Compare-TaskIdentity($TaskBefore, $TaskAfter) {
    foreach ($taskField in @('head', 'jarSha256', 'sourceSha256', 'toolSha256', 'pid', 'processStartTicks', 'environment')) {
        if ($TaskBefore.$taskField -cne $TaskAfter.$taskField) { throw "Source/runtime changed: $taskField" }
    }
    foreach ($taskField in @('pid', 'processStartTicks', 'argumentsSha256', 'configurationSha256')) {
        if ($TaskBefore.controlRuntime.$taskField -cne $TaskAfter.controlRuntime.$taskField) { throw "Control runtime changed: $taskField" }
    }
}

function Invoke-TaskBrowser([string]$TaskFault, [string]$TaskDirectory) {
    $taskArgs = @((Join-Path $PSScriptRoot 'browser-login-regression.cjs'), '--base-url', $BaseUrl,
        '--module-path', $PlaywrightModulePath, '--out-dir', $TaskDirectory, '--run-id', $taskRunId)
    if ($TaskFault) { $taskArgs += @('--fault', $TaskFault) }
    # Private context goes only to stdin. Never echo it, write it to disk or put it in argv.
    $taskPrivateInput | & node @taskArgs | Write-Host
    $taskBrowserExit = $LASTEXITCODE
    $taskReportFile = Join-Path $TaskDirectory 'browser-results.json'
    if (-not (Test-Path -LiteralPath $taskReportFile)) { throw 'Browser report missing.' }
    $taskBrowserReport = Get-Content -LiteralPath $taskReportFile -Raw | ConvertFrom-Json
    return @{ ExitCode = $taskBrowserExit; Report = $taskBrowserReport }
}

try {
    $taskContext = Get-TaskJson '--private'
    $taskIdentity = $taskContext.identity
    $taskPrivateInput = $taskContext | ConvertTo-Json -Depth 12 -Compress
    $taskChecks = Get-TaskJson "--checks $taskRunId"
    Compare-TaskIdentity $taskIdentity $taskChecks.identity
    $taskManagementForward = '127.0.0.1:18020:127.0.0.1:18120'
    if (-not ($taskProcesses | Where-Object {
        $_.CommandLine -match [regex]::Escape($taskManagementForward) -and
        $_.CommandLine -match 'data_dev_zhm@124\.221\.139\.87'
    })) { throw 'Verified dedicated management SSH forwarding required.' }
    $taskManagementJson = & ssh -o BatchMode=yes -o StrictHostKeyChecking=yes data_dev_zhm@124.221.139.87 "python3 -E $taskRemoteRoot/source/tools/phase1/management-test-context.py"
    if ($LASTEXITCODE -ne 0) { throw 'Management private context rejected.' }
    $taskManagementContext = ConvertFrom-TaskJsonResponse -Response @($taskManagementJson)
    Compare-TaskIdentity $taskIdentity $taskManagementContext.identity
    $taskManagementInput = $taskManagementContext | ConvertTo-Json -Depth 12 -Compress
    $taskManagementDirectory = Join-Path $taskOutput 'management'
    $taskManagementInput | & node (Join-Path $PSScriptRoot 'browser-management-regression.cjs') --base-url 'http://127.0.0.1:18020/' --module-path $PlaywrightModulePath --out-dir $taskManagementDirectory --run-id $taskRunId | Write-Host
    if ($LASTEXITCODE -ne 0) { throw 'Management page acceptance failed.' }
    $taskManagementFile = Join-Path $taskManagementDirectory 'management-browser-results.json'
    $taskManagementReport = Get-Content -LiteralPath $taskManagementFile -Raw | ConvertFrom-Json
    if (-not $taskManagementReport.passed) { throw 'Management page report failed.' }
    & scp -o BatchMode=yes -o StrictHostKeyChecking=yes $taskManagementFile "data_dev_zhm@124.221.139.87:$taskRemoteRoot/logs/delivery-$taskRunId/management-browser-results.json"
    if ($LASTEXITCODE -ne 0) { throw 'Management evidence upload failed.' }
    $taskBrowser = Invoke-TaskBrowser '' (Join-Path $taskOutput 'healthy')
    if ($taskBrowser.ExitCode -ne 0 -or -not $taskBrowser.Report.passed) { throw 'Real desktop/mobile browser regression failed.' }
    $taskNegative = @{}
    $taskFaults = @{
        'unexpected-404' = 'API_HTTP_ERROR'
        'loading-mask' = 'LOADING_MASK_VISIBLE'
        'mobile-submit-blocked' = 'LOGIN_REQUEST_NOT_SENT'
    }
    foreach ($taskFault in @('unexpected-404', 'loading-mask', 'mobile-submit-blocked')) {
        $taskResult = Invoke-TaskBrowser $taskFault (Join-Path $taskOutput $taskFault)
        $taskFailures = @($taskResult.Report.cases | Where-Object { $_.status -eq 'failed' -and $_.code -eq $taskFaults[$taskFault] })
        if ($taskResult.ExitCode -eq 0 -or $taskResult.Report.passed -or $taskFailures.Count -ne 1) {
            throw "Fault was not detected correctly: $taskFault"
        }
        $taskNegative[$taskFault] = $true
    }
    $taskAfter = Get-TaskJson ''
    Compare-TaskIdentity $taskIdentity $taskAfter.identity
    $taskGate = @{
        schemaVersion = 1; runId = $taskRunId; identity = $taskIdentity; checks = $taskChecks.checks;
        browser = $taskBrowser.Report; managementBrowser = $taskManagementReport; negativeControls = $taskNegative; passed = $true;
        protocol = $taskProtocol;
        # Browser machine and server clocks can differ; receipt TTL uses the server's clock.
        finishedUnix = $taskAfter.serverUnix
    }
    $taskGateFile = Join-Path $taskOutput 'delivery-gate.json'
    $taskGate | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $taskGateFile -Encoding utf8NoBOM
    # Upload to a run-specific temporary receipt; preserve the last valid receipt until upload succeeds.
    & scp -o BatchMode=yes -o StrictHostKeyChecking=yes $taskGateFile "data_dev_zhm@124.221.139.87:$taskRemoteRoot/logs/delivery-gate-$taskRunId.json"
    if ($LASTEXITCODE -ne 0) { throw 'Receipt upload failed.' }
    & ssh -o BatchMode=yes -o StrictHostKeyChecking=yes data_dev_zhm@124.221.139.87 "mv $taskRemoteRoot/logs/delivery-gate-$taskRunId.json $taskRemoteRoot/logs/delivery-gate.json"
    if ($LASTEXITCODE -ne 0) { throw 'Receipt promotion failed.' }
    $taskVerified = Get-TaskJson '--verify-gate'
    if (-not $taskVerified.passed) { throw 'Delivery receipt rejected.' }
    Write-Output "Delivery gate PASS: $taskRunId; 21 management cases, 8 desktop/mobile cases and 3 fault controls; dedicated remote checks passed."
} finally {
    # No clipboard access, credential files, HAR, storageState or browser traces.
    Remove-Variable taskPrivateInput, taskContext, taskManagementJson, taskManagementContext, taskManagementInput -ErrorAction SilentlyContinue
}
