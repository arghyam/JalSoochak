#!/usr/bin/env bash
#
# Fails when a vendor name (Glific, FlowVision, MinIO) or a state name (Assam) appears anywhere in the
# tracked tree outside the places the rules below allow. Run from the repository root:
#
#   bash .github/scripts/vendor-neutrality-guard.sh
#
# Ports and business code stay generic; a vendor name belongs only in the adapter that speaks that
# vendor's protocol. This guard keeps it that way.
#
# Identifiers and prose are renamed, never exempted for convenience. What the rules below let through
# is either what cannot change yet — a wire name, a stored value, an applied migration — or what has
# to keep the real name — a historical record, a description of the deployment, a script run against
# one. Each sits under a named rule that says why, so that the list reads as a set of decisions rather
# than a set of oversights.
set -euo pipefail

# Rule VENDOR_ADAPTER — the adapter that speaks a vendor's protocol is where that vendor's name
# belongs, so these paths are not scanned.
ADAPTER_PATHS=(
  'backend/message-service/src/*/java/**/channel/provider/glific/**'
  'backend/telemetry-service/src/*/java/**/provider/whatsapp/glific/**'
  'backend/telemetry-service/src/*/java/**/provider/ocr/flowvision/**'
)

# Rule APPLIED_MIGRATION — Flyway checksums every migration it has run, so editing even a comment in
# one fails validation on every environment that has applied it. A new migration is scanned.
APPLIED_MIGRATIONS=(
  backend/database/V18__add_whatsapp_connection_id_to_user_table.sql
  backend/database/V30__add_reports_and_data_versions_per_tenant.sql
  backend/database/V32__add_flowvision_correlation_id.sql
  backend/database/V33__create_user_preference_tables.sql
  backend/database/V34__add_scheme_id_mismatch_tracking.sql
  backend/database/V35__add_confirmed_reading_source.sql
  backend/database/V38__create_language_master.sql
  backend/database/V43__add_flow_reading_id_to_anomaly_table.sql
  backend/analytics-service/src/main/resources/db/migration/V47__widen_fact_meter_reading_to_numeric.sql
  backend/analytics-service/src/main/resources/db/migration/V48__add_submission_linkage_to_anomaly_and_fact_meter_reading.sql
)

# Rule RENAMING_MIGRATION — a migration that renames a vendor-named column, index or function body
# has to name what it renames in order to find it.
RENAMING_MIGRATIONS=(
  backend/database/V46__rename_ocr_correlation_id_column.sql
)

# Rule HISTORICAL_RECORD — completed plans, code reviews and architecture decision records record
# decisions made under the old names. Rewriting them would change what they record.
HISTORICAL_RECORDS=(
  '**/*_PLAN.md'
  'docs/CODE_REVIEW_*.md'
  'docs/*-plan.md'
  '.claude/plans/**'
  gitbook/architecture-decision-records.md
)

# Rule DEPLOYMENT_DESCRIPTION — these pages describe what is actually deployed and installed: the
# products, Helm releases and hosts an operator has to recognise. Other pages link to them rather than
# naming the products.
DEPLOYMENT_DESCRIPTIONS=(
  gitbook/deployment-architecture.md
  gitbook/installation-guide.md
  gitbook/technology-stack.md
)

# Rule OPERATIONAL_SCRIPT — benchmark harnesses, master-data ingestion jobs and one-off data fixes,
# each run against a particular deployment. They name that deployment's state and vendors because they
# act on them.
OPERATIONAL_SCRIPTS=(
  'scripts/**'
)

# Rule GUARD_SELF — this script has to name what it forbids.
GUARD_SELF=.github/scripts/vendor-neutrality-guard.sh

# Rule FORBIDDEN_MARKER — the names this guard exists to keep out. Case-insensitive, so it also
# covers env vars (GLIFIC_API_URL) and bean or thread names (glific-sync-). It also matches the
# separated and misspelt forms (FLOW_VISION_FAILED, "Gliffic"). The negative lookahead spares
# "Assamese", which is a language name in the language seed data, not a state reference.
#
# Gupshup is deliberately absent: every reference to it either explains what Glific sits in front
# of or is the real media host filemanager.gupshup.io on the SSRF allowlist.
FORBIDDEN_MARKER='glif+ic|flow[\s_-]?vision|minio|assam(?!ese)'

# Tokens removed from a line before the marker is looked for; the rest of the line is still checked.
# One per line: a Perl regex on the file path, whitespace, then a case-insensitive Perl regex for the
# token.
ALLOWED_TOKENS="$(cat <<'RULES'
# Rule URL_OR_HOST — a marker inside a URL or a hostname names a real endpoint
# (https://api.arghyam.glific.com/api, flowvision-test.s3.ap-south-1.amazonaws.com), not our code.
.  [a-z][a-z0-9+.-]*://[^\s"'<>()]+
.  (?<![\w.-])[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|in|example)(?![\w.-])

# Rule LEGACY_ALIAS — deprecated names still served, read or emitted for one release, so that each
# side of the contract can move on its own. They go when the aliases are removed.
.  GLIFIC_MESSAGE_TEMPLATES
.  glific_welcome_flow_id
.  \bglific_id\b|\bgetGlificId\b
.  glificLanguageId
.  flowvision_correlation_id

# Rule PUBLIC_ERROR_CODE — error codes returned to API callers. Renaming one is a contract change
# that needs its own transition.
.  FLOW_VISION_(?:FAILED|REJECTED)

# Rule OCR_PROVIDER_ID — the registry id of the built-in OCR provider. Tenants select a provider by
# this value in their ocr_provider config, so it is stored data rather than a name we choose.
.  "flowvision"|OCR_DEFAULT_PROVIDER:flowvision

# Rule TEST_CONTAINER_IMAGE — an integration test runs the object store's own image as its S3
# endpoint, so it names the image, the image's default credential and its readiness path.
/src/test/  "minio/minio:[^"\n]*"|"minioadmin"|"/minio/health/\w+"

# Rule TEST_FIXTURE — the test tenant is modelled on a real state deployment, so a string literal in
# a test may carry that state's name as data ("tenant_assam", "Assam PHED").
/src/test/  "[^"\n]*assam[^"\n]*"
RULES
)"

# Rule ADAPTER_REFERENCE — a wiring test or a @MockBean has to name the adapter class it wires or
# silences, and a document may point at the adapter that implements a port. Tests and documents only:
# production code outside the adapter reaches it through a port. The class names are read from the
# adapter paths, so the rule follows the adapters as they change.
ADAPTER_TYPES="$(git ls-files -- "${ADAPTER_PATHS[@]/#/:(glob)}" \
  | sed -n 's#^.*/src/main/java/\(.*\)\.java$#\1#p' | tr '/' '.')"

# Only tracked files are scanned, which keeps target/ and local logs out.
pathspecs=(.)
for path in "${ADAPTER_PATHS[@]}" "${HISTORICAL_RECORDS[@]}" "${OPERATIONAL_SCRIPTS[@]}"; do
  pathspecs+=(":(exclude,glob)$path")
done
for path in "${APPLIED_MIGRATIONS[@]}" "${RENAMING_MIGRATIONS[@]}" "${DEPLOYMENT_DESCRIPTIONS[@]}" "$GUARD_SELF"; do
  pathspecs+=(":(exclude)$path")
done

candidates="$(git grep -I -n -i -P -e "$FORBIDDEN_MARKER" -- "${pathspecs[@]}" || true)"

violations="$(printf '%s\n' "$candidates" \
  | FORBIDDEN_MARKER="$FORBIDDEN_MARKER" ALLOWED_TOKENS="$ALLOWED_TOKENS" ADAPTER_TYPES="$ADAPTER_TYPES" \
    perl -ne '
  BEGIN {
    for (split /\n/, $ENV{ALLOWED_TOKENS}) {
      next if /^\s*(#|$)/;
      my ($path, $token) = split /\s+/, $_, 2;
      push @rules, [qr/$path/, qr/$token/i];
    }
    for my $type (split /\n/, $ENV{ADAPTER_TYPES}) {
      (my $simple = $type) =~ s/.*\.//;
      push @rules, [qr{/src/test/|\.md$}, qr/\b(?:\Q$type\E|\Q$simple\E|\Q${\ lcfirst $simple}\E)\b/];
    }
  }
  chomp;
  next unless /^([^:]+):(\d+):(.*)$/;
  my ($file, $location, $original) = ($1, "$1:$2", $3);
  my $text = $original;
  for my $rule (@rules) {
    $text =~ s/$rule->[1]//g if $file =~ $rule->[0];
  }
  print "$location:$original\n" if $text =~ /$ENV{FORBIDDEN_MARKER}/i;
')"

if [[ -n "$violations" ]]; then
  echo "Vendor- or state-specific names found outside the places this guard allows:" >&2
  echo "$violations" >&2
  echo >&2
  echo "Use the generic name (WhatsApp, OCR, storage, canonical). A vendor name belongs in the adapter" >&2
  echo "for that vendor; the few other places that keep one are the rules in $GUARD_SELF." >&2
  exit 1
fi

echo "Vendor-neutrality guard passed."
