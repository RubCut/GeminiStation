#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/secrets" "$tmp/home"
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == 'secret list' ]]; then
  for f in "$TEST_SECRET_DIR"/*; do [[ -f "$f" ]] && printf '%s\t2026-01-01\n' "${f##*/}"; done
  exit 0
fi
if [[ "$1 $2" == 'secret set' ]]; then
  cat > "$TEST_SECRET_DIR/$3"
  exit 0
fi
exit 1
MOCK
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" TEST_SECRET_DIR="$tmp/secrets" HOME="$tmp/home"
"$root/tools/setup_signing_secrets.sh" > "$tmp/result"
key="$HOME/.geministation-signing/signing.p12"
pass="$HOME/.geministation-signing/password.txt"
test -s "$key" && test -s "$pass"
[[ "$(wc -c < "$pass")" -eq 64 ]]
test "$(stat -c '%a' "$key")" = 600
test "$(stat -c '%a' "$pass")" = 600
openssl pkcs12 -in "$key" -passin "file:$pass" -info -noout >/dev/null 2>&1
if command -v keytool >/dev/null; then
  export TEST_SIGNING_PASSWORD="$(cat "$pass")"
  keytool -list -alias geministation -storetype PKCS12 -keystore "$key" -storepass:env TEST_SIGNING_PASSWORD >/dev/null
  mkdir -p "$tmp/jar-input"
  echo test > "$tmp/jar-input/test.txt"
  jar cf "$tmp/check.jar" -C "$tmp/jar-input" .
  jarsigner -keystore "$key" -storetype PKCS12 -storepass:env TEST_SIGNING_PASSWORD -keypass:env TEST_SIGNING_PASSWORD "$tmp/check.jar" geministation >/dev/null
  jarsigner -verify "$tmp/check.jar" >/dev/null
  unset TEST_SIGNING_PASSWORD
fi
base64 --decode "$tmp/secrets/GEMINI_SIGNING_KEYSTORE_B64" > "$tmp/fromsecret.p12"
cmp "$key" "$tmp/fromsecret.p12"
cmp "$pass" "$tmp/secrets/GEMINI_SIGNING_PASSWORD"
before=$(sha256sum "$key")
if "$root/tools/setup_signing_secrets.sh" > "$tmp/second-result" 2>&1; then
  echo 'Setup must not replace existing secrets' >&2; exit 1
fi
[[ "$before" == "$(sha256sum "$key")" ]]
# Generate-only reuses the existing backup and never needs the GitHub CLI.
"$root/tools/setup_signing_secrets.sh" --generate-only > "$tmp/generate-result"
[[ "$before" == "$(sha256sum "$key")" ]]
echo 'Signing setup: OK (private data removed with test directory)'
