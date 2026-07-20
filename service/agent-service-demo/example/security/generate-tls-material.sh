#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-$(cd "$(dirname "$0")" && pwd)/tls}"
PASSWORD="${TLS_DEMO_PASSWORD:-demo-tls-pass}"
KEYTOOL="${JAVA_HOME:?JAVA_HOME required}/bin/keytool"

mkdir -p "$OUTPUT_DIR"
SERVER_P12="$OUTPUT_DIR/server.p12"
CLIENT_P12="$OUTPUT_DIR/client.p12"
SERVER_TRUST="$OUTPUT_DIR/server-trust.p12"
CLIENT_TRUST="$OUTPUT_DIR/client-trust.p12"
SERVER_CRT="$OUTPUT_DIR/server.crt"
CLIENT_CRT="$OUTPUT_DIR/client.crt"

run_keytool() {
  "$KEYTOOL" "$@"
}

run_keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore "$SERVER_P12" -storepass "$PASSWORD" -keypass "$PASSWORD" -validity 365 \
  -dname "CN=localhost" -ext "SAN=DNS:localhost,IP:127.0.0.1"

run_keytool -genkeypair -alias client -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore "$CLIENT_P12" -storepass "$PASSWORD" -keypass "$PASSWORD" -validity 365 \
  -dname "CN=demo-client"

run_keytool -exportcert -alias server -keystore "$SERVER_P12" -storepass "$PASSWORD" -file "$SERVER_CRT"
run_keytool -exportcert -alias client -keystore "$CLIENT_P12" -storepass "$PASSWORD" -file "$CLIENT_CRT"
run_keytool -importcert -alias server -file "$SERVER_CRT" -keystore "$CLIENT_TRUST" \
  -storepass "$PASSWORD" -storetype PKCS12 -noprompt
run_keytool -importcert -alias client -file "$CLIENT_CRT" -keystore "$SERVER_TRUST" \
  -storepass "$PASSWORD" -storetype PKCS12 -noprompt

echo "Generated demo TLS material under $OUTPUT_DIR"
echo "Use password '$PASSWORD' in application-security-tls_local.yml (gitignored)."
