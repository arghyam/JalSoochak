#!/usr/bin/env bash
#
# Fails when a vendor name (Glific, FlowVision, MinIO) or a state name (Assam) appears in a path
# that has been made vendor- and state-neutral. Run from the repository root:
#
#   bash .github/scripts/vendor-neutrality-guard.sh
#
# Ports and business code stay generic; a vendor name belongs only in the adapter that speaks that
# vendor's protocol. This guard keeps a cleaned path clean.
set -euo pipefail

# Paths the guard enforces. It starts with what is already clean and widens as each area is cleaned,
# so that it guards the work while the names are still moving rather than only once they have
# settled. Only tracked files are scanned, which keeps target/ and local logs out.
SCOPE=(
  backend/anomaly-service
  backend/logger
  backend/service-discovery
)

# Rule FORBIDDEN_MARKER — the names this guard exists to keep out. Case-insensitive, so it also
# covers env vars (GLIFIC_API_URL) and bean or thread names (glific-sync-). The negative lookahead
# spares "Assamese", which is a language name in the language seed data, not a state reference.
#
# Gupshup is deliberately absent: every reference to it either explains what Glific sits in front
# of or is the real media host filemanager.gupshup.io on the SSRF allowlist.
FORBIDDEN_MARKER='glific|flowvision|minio|assam(?!ese)'

# Rule URL_EXEMPTION — a marker inside a URL is a hostname or path naming a real endpoint
# (https://minio.example.com, https://api.arghyam.glific.com/api), not our code, so it is stripped
# before the marker is looked for. The rest of the line is still checked.
URL_PATTERN='[a-z][a-z0-9+.-]*://[^\s"'"'"'<>()]+'

candidates="$(git grep -I -n -i -P -e "$FORBIDDEN_MARKER" -- "${SCOPE[@]}" || true)"

violations="$(printf '%s\n' "$candidates" | FORBIDDEN_MARKER="$FORBIDDEN_MARKER" URL_PATTERN="$URL_PATTERN" perl -ne '
  chomp;
  next unless /^([^:]+:\d+):(.*)$/;
  my ($location, $original) = ($1, $2);
  (my $text = $original) =~ s/$ENV{URL_PATTERN}//gi;
  print "$location:$original\n" if $text =~ /$ENV{FORBIDDEN_MARKER}/i;
')"

if [[ -n "$violations" ]]; then
  echo "Vendor- or state-specific names found in a vendor-neutral path:" >&2
  echo "$violations" >&2
  echo >&2
  echo "Use the generic name (WhatsApp, OCR, storage, canonical). A vendor name is allowed only" >&2
  echo "in the adapter for that vendor, and none of the paths above is one." >&2
  exit 1
fi

echo "Vendor-neutrality guard passed for: ${SCOPE[*]}"
