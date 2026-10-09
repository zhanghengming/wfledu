function ConvertFrom-TaskJsonResponse {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Response)
    $taskDocument = $null
    try {
        $taskText = $Response -join "`n"
        $taskDocument = [System.Text.Json.JsonDocument]::Parse($taskText)
        if ($taskDocument.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) {
            throw 'INVALID_TASK_JSON_RESPONSE'
        }
        $taskParsed = ConvertFrom-Json -InputObject $taskText -NoEnumerate -ErrorAction Stop
    } catch {
        # Do not echo a private response in a parsing exception.
        throw 'INVALID_TASK_JSON_RESPONSE'
    } finally {
        if ($taskDocument) { $taskDocument.Dispose() }
    }
    if ($taskParsed -isnot [pscustomobject]) { throw 'INVALID_TASK_JSON_RESPONSE' }
    return $taskParsed
}
