#!/usr/bin/env bash
# PII gate for test sources.
# Fails when a test file contains an email-shaped string outside the RFC 2606
# example.* / *.test / *.invalid / localhost domains, or a phone-shaped value
# outside the reserved fiction ranges (555-01XX NA, 7946 UK drama, 234567 FR
# cinema, +000... shapes). Run before committing changes under app/src/test*.
set -euo pipefail
cd "$(dirname "$0")/.."

TEST_DIRS=(app/src/test*)
EXTENSIONS=(-g '*.kt' -g '*.xml' -g '*.json' -g '*.txt' -g '*.properties')

status=0

emails=$(rg --pcre2 -n '[A-Za-z0-9._%+-]+@(?!(example\.(com|org|net)|test|invalid|localhost)\b)[A-Za-z0-9.-]+\.[A-Za-z]{2,}' "${EXTENSIONS[@]}" "${TEST_DIRS[@]}" || true)
if [[ -n "$emails" ]]; then
    echo "PII gate: email-shaped values outside the safe domains:"
    echo "$emails"
    status=1
fi

# Phone-shaped values: +international (8-15 digits), (555) 010-0000 parenthesized
# form, and whole national 3-3-4 / 3-4 tokens (lookarounds stop the 3-4 arm from
# matching a fragment like "020 7946" of the longer "020 7946 0001").
# Digits are reduced and checked against the full reserved shapes (substring
# markers like the guide's 234567 false-negative on +79551234567); a run of
# 7+ zeros marks the provably-unreachable 000... shapes. Anything else fails.
phones=$(rg --pcre2 -on '\+[0-9][0-9 ()-]{7,14}[0-9]|\(\d{3}\)[ ()-]*\d[\d ()-]{4,9}\d|(?<![0-9-])\d{3}[- ]\d{3,4}(?:[- ]\d{3,4})?(?![0-9-])' "${EXTENSIONS[@]}" "${TEST_DIRS[@]}" || true)
if [[ -n "$phones" ]]; then
    unsafe=$(echo "$phones" | awk -F: '{ cand = $NF; gsub(/[^0-9]/, "", cand); if (cand !~ /^(1?55501[0-9]{0,5}|44207946[0-9]{4}|0207946[0-9]{4}|331234567[0-9]{2})$/ && cand !~ /0{7,}/) print }')
    if [[ -n "$unsafe" ]]; then
        echo "PII gate: phone-shaped values outside the reserved fiction ranges:"
        echo "$unsafe"
        status=1
    fi
fi

if [[ "$status" -ne 0 ]]; then
    echo "PII gate failed: use the safe domains and reserved fiction ranges documented in this script's header"
    exit 1
fi
echo "PII gate: clean"
