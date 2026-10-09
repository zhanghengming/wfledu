$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'remote-json-response.ps1')
$taskCases = [Collections.Generic.List[object]]::new()
function Test-TaskJsonAccepted([string]$Id, [string[]]$Lines) {
    $taskValue = ConvertFrom-TaskJsonResponse -Response $Lines
    if ($taskValue.checks.good.passed -ne $true) { throw 'PROTOCOL_OBJECT_NOT_PRESERVED' }
    $taskCases.Add(@{ id = $Id; status = 'passed' })
}
function Test-TaskJsonRejected([string]$Id, [string[][]]$Inputs) {
    foreach ($taskInput in $Inputs) {
        try {
            $null = ConvertFrom-TaskJsonResponse -Response $taskInput
            throw 'PROTOCOL_INVALID_INPUT_ACCEPTED'
        } catch {
            if ($_.Exception.Message -ne 'INVALID_TASK_JSON_RESPONSE') { throw }
        }
    }
    $taskCases.Add(@{ id = $Id; status = 'passed' })
}
Test-TaskJsonAccepted 'protocol.object-single' @('{"checks":{"good":{"passed":true}}}')
Test-TaskJsonAccepted 'protocol.object-multiline' @('{', '"checks":{"good":{"passed":true}}', '}')
Test-TaskJsonRejected 'protocol.multiple-objects' (, @('{"kind":"assembly-refusal"}', '{"checks":{"good":{"passed":true}}}'))
Test-TaskJsonRejected 'protocol.array-root' (, @('[{"checks":{"good":{"passed":true}}}]'))
Test-TaskJsonRejected 'protocol.scalar-root' @((, 'null'), (, 'false'), (, '0'), (, '"text"'))
Test-TaskJsonRejected 'protocol.malformed-json' (, @('{"checks":'))
$taskSources = @{}
foreach ($taskName in @('remote-json-response.ps1', 'test-remote-json-response.ps1')) {
    $taskSources[$taskName] = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $taskName) -Algorithm SHA256).Hash.ToLowerInvariant()
}
@{ schemaVersion = 1; passed = $true; cases = @($taskCases.ToArray()); sources = $taskSources } | ConvertTo-Json -Depth 8 -Compress
