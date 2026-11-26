param(
  [Parameter(Mandatory = $true)][string]$ApplicationId,
  [string]$Country = "KE",
  [string]$RegistrationNumber = ""
)

if (-not $RegistrationNumber) { $RegistrationNumber = "PVT-$ApplicationId" }
$camundaRest = if ($env:CAMUNDA_REST) { $env:CAMUNDA_REST } else { "http://localhost:8080" }
$user = if ($env:CAMUNDA_USER) { $env:CAMUNDA_USER } else { "demo" }
$password = if ($env:CAMUNDA_PASSWORD) { $env:CAMUNDA_PASSWORD } else { "demo" }

$body = @{
  name           = "ApplicationSubmitted"
  correlationKey = $ApplicationId
  messageId      = $ApplicationId
  timeToLive     = 2592000000
  variables      = @{
    applicationId      = $ApplicationId
    country            = $Country
    registrationNumber = $RegistrationNumber
    businessName       = "TechWorld Traders Ltd"
    legalForm          = "LIMITED_COMPANY"
    contactEmail       = "owner@techworld.com"
    documentRefs       = @("doc-store://$ApplicationId/certificate-of-incorporation")
  }
} | ConvertTo-Json -Depth 5

$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${user}:${password}"))

try {
  Invoke-RestMethod -Method Post -Uri "$camundaRest/v2/messages/publication" `
    -ContentType "application/json" -Body $body `
    -Headers @{ Authorization = "Basic $token" } | ConvertTo-Json
}
catch {
  Write-Host "Rejected: $($_.Exception.Message)"
  if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
}
