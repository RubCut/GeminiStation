#!/usr/bin/env bash
# Run on the repository owner's computer, never in Actions or a shared shell.
# Creates an owner-only backup outside the repository; uploads via GitHub CLI.
set -euo pipefail
umask 077
repo="${GEMINI_GITHUB_REPOSITORY:-RubCut/GeminiStation}"
vault="${GEMINI_SIGNING_DIR:-$HOME/.geministation-signing}"
keystore="$vault/signing.p12"
password="$vault/password.txt"
mode="${1:-}"
if [[ -n "$mode" && "$mode" != '--generate-only' ]]; then
  echo "Usage: $0 [--generate-only]" >&2; exit 2
fi
for cmd in openssl base64 tr mktemp; do
  command -v "$cmd" >/dev/null || { echo "Required tool not found: $cmd" >&2; exit 1; }
done

# Never rotate an existing identity by accident. A partial setup must be resolved
# deliberately in GitHub settings after confirming the existing backup.
if [[ "$mode" != '--generate-only' ]]; then
  command -v gh >/dev/null || { echo "Required tool not found: gh" >&2; exit 1; }
  if ! existing=$(gh secret list --repo "$repo"); then
    echo "Cannot check Actions secrets. Use --generate-only and GitHub Settings if necessary." >&2
    exit 1
  fi
  if printf '%s\n' "$existing" | awk '{print $1}' | grep -Eq '^(GEMINI_SIGNING_KEYSTORE_B64|GEMINI_SIGNING_PASSWORD)$'; then
    echo "At least one Gemini signing secret already exists. Refusing to replace it automatically." >&2
    echo "Check GitHub Settings and your backup before making changes." >&2
    exit 1
  fi
fi

mkdir -p "$vault"
chmod 700 "$vault"
if [[ -e "$keystore" || -e "$password" ]]; then
  if [[ ! -s "$keystore" || ! -s "$password" ]]; then
    echo "Incomplete signing backup in $vault; refusing to overwrite it." >&2
    exit 1
  fi
  echo "Reusing the existing signing backup in $vault."
else
  temp=$(mktemp -d "$vault/.create.XXXXXXXX")
  trap 'rm -rf "$temp"' EXIT
  openssl rand -hex 32 | tr -d '\n' > "$temp/password.txt"
  openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 10000 \
    -subj '/CN=GeminiStation Signing/O=GeminiStation' \
    -keyout "$temp/key.pem" -out "$temp/cert.pem" >/dev/null 2>&1
  openssl pkcs12 -export -inkey "$temp/key.pem" -in "$temp/cert.pem" \
    -name geministation -out "$temp/signing.p12" -passout "file:$temp/password.txt"
  # Keep these private files as a recovery backup. Never commit or share them.
  mv "$temp/signing.p12" "$keystore"
  mv "$temp/password.txt" "$password"
  chmod 600 "$keystore" "$password"
  echo "Created a private signing backup in $vault."
fi
# Verify the backup is a readable PKCS#12 with the expected alias before upload.
chmod 600 "$keystore" "$password"
if ! openssl pkcs12 -in "$keystore" -passin "file:$password" -info -noout >/dev/null 2>&1; then
  echo "Signing backup could not be opened with its password; refusing to upload." >&2; exit 1
fi
if ! openssl pkcs12 -in "$keystore" -passin "file:$password" -nokeys 2>/dev/null | grep -q 'friendlyName: geministation'; then
  echo "Signing backup does not contain the expected alias; refusing to upload." >&2; exit 1
fi
if [[ "$mode" == '--generate-only' ]]; then
  echo "Key and password are private files in $vault. Keep this backup safe."
  echo "Configure GEMINI_SIGNING_KEYSTORE_B64 and GEMINI_SIGNING_PASSWORD in GitHub Actions secrets."
  exit 0
fi
# Password first: if the second upload fails, the same local backup can be used
# to finish setup manually; no secret value is printed to the terminal.
gh secret set GEMINI_SIGNING_PASSWORD --repo "$repo" < "$password"
base64 < "$keystore" | tr -d '\n' | gh secret set GEMINI_SIGNING_KEYSTORE_B64 --repo "$repo"
echo "GitHub Actions signing secrets configured. Keep the private backup safe."
