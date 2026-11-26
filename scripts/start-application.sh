#!/usr/bin/env bash
set -euo pipefail

APPLICATION_ID="${1:?applicationId required}"
COUNTRY="${2:-KE}"
REGISTRATION_NUMBER="${3:-PVT-${APPLICATION_ID}}"
CAMUNDA_REST="${CAMUNDA_REST:-http://localhost:8080}"
THIRTY_DAYS_MS=2592000000

curl -sS -u "${CAMUNDA_USER:-demo}:${CAMUNDA_PASSWORD:-demo}" \
  -X POST "${CAMUNDA_REST}/v2/messages/publication" \
  -H "Content-Type: application/json" \
  -w "\nHTTP %{http_code}\n" \
  -d @- <<JSON
{
  "name": "ApplicationSubmitted",
  "correlationKey": "${APPLICATION_ID}",
  "messageId": "${APPLICATION_ID}",
  "timeToLive": ${THIRTY_DAYS_MS},
  "variables": {
    "applicationId": "${APPLICATION_ID}",
    "country": "${COUNTRY}",
    "registrationNumber": "${REGISTRATION_NUMBER}",
    "businessName": "TechWorld Traders Ltd",
    "legalForm": "LIMITED_COMPANY",
    "contactEmail": "owner@techworld.com",
    "documentRefs": ["doc-store://${APPLICATION_ID}/certificate-of-incorporation"]
  }
}
JSON
