$ErrorActionPreference = "Stop"

function New-SecureToken {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

$ingestToken = New-SecureToken
$readToken = New-SecureToken

Write-Host "INGEST_TOKEN=$ingestToken"
Write-Host "READ_TOKEN=$readToken"
Write-Host ""
Write-Host "Keep these values private. The Android app only needs INGEST_TOKEN."

