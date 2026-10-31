# Android security review — 31 October 2026

Release follow-up: the fixes below are included in the 0.2.6 release changes. The repository/state
descriptions record the initial audit; publication is handled through the pull-request/release workflow.

Reviewed `Hexalyse/wisprcheap-android` at baseline commit
`1a13f92f66faf1c7692cfbec0c5b0d050fc13c39`; the checkout matched `origin/main`.
Scope: network clients, sync/crypto, manifest/exported components, deep-link flow, API-key/sync-key
storage, backup rules, accessibility/password-field handling and dependencies. At the initial audit,
fixes were local and uncommitted; no release, publication or GitHub settings change had been made.

## GitHub and dependency reports

Authenticated repository APIs returned no Dependabot alerts, no repository security advisories and
no open secret-scanning alerts. The code-scanning endpoint returned `404: no analysis found`;
that is missing analysis, not evidence of safety.

Queried **110 resolved release runtime Maven coordinates** against OSV on the review date.
No known advisories were returned. This is a point-in-time dependency check, not a guarantee;
it does not scan Gradle plugins, the Android OS, native platform libraries or all historical code.
Added weekly Dependabot configuration for Gradle and GitHub Actions.

The medium advisory reported by GitHub belongs to desktop/Linux GLib 0.18.5:
[GHSA-wrw7-89jp-8q8g](https://github.com/advisories/GHSA-wrw7-89jp-8q8g).
Android does not use GLib. The desktop migration replaces GTK3/legacy GLib entirely; its
[combined review](https://github.com/Hexalyse/wisprcheap/blob/master/docs/security-review-2026-10-31.md)
records the initial backport and subsequent migration.

## Confirmed findings fixed

1. **Redirect disclosure — medium, conditional.** Automatic redirects could forward audio/LLM bodies,
   pairing codes and custom ElevenLabs `xi-api-key` headers from a configured endpoint to another
   destination. Disabled redirects in `Http.client`, and restrict caller-supplied OkHttp clients in
   `Http.fetch` and `SyncApi` too. HTTPS-to-HTTP redirect following is disabled. A redirect now produces
   an error; update the configured URL explicitly. Network tests use a separate destination and dummy keys.
2. **Untrusted KDF resource exhaustion — medium.** A malicious sync server could supply arbitrary
   Argon2 parameters, causing excessive allocation/work during unlock. Validate parameters before
   Bouncy Castle initialization: Argon2id only, at most 64 MiB, 10 iterations, 8 lanes, `m >= 8*p`,
   and a 16-byte salt. Desktop and server use compatible limits. Default settings and vectors are
   unchanged; unusually large custom existing keyrings need rewrapping using a trusted older client.
3. **Unbounded sync responses — medium.** A malicious server could supply a huge response to
   `ResponseBody.string()`. Sync success/error bodies are now capped at 2 MiB, including chunked
   bodies without Content-Length. Tests cover oversized fixed-length and chunked responses.

Severity labels above are reviewer estimates, not assigned CVSS scores.

## Existing controls checked

API keys and sync credentials use AES-GCM with keys in Android Keystore. Manifest backup is disabled;
data-extraction rules exclude cloud backup and device transfer. Exported accessibility and tile services
require their platform binding permissions. The exported launcher/pairing activity is intentional;
pairing links prefill a user-controlled connection form. Existing AES-GCM record binding, wrong-key,
interoperability, sync recovery and merge regressions pass. These observations do not replace device testing.

## Remaining work

- **Restrict cleartext endpoints.** `usesCleartextTraffic=true` permits intentional local HTTP speech/sync
  servers. Selecting HTTP exposes tokens, API requests and data to network interception. Use HTTPS
  for normal deployments; consider explicit per-host insecure opt-in instead of a global allowance.
- **Authenticate sync metadata in a coordinated protocol update.** Current AES-GCM binds user/kind/ID,
  but not HLC/deletion metadata; tombstones have no authenticated payload. A malicious server can
  replay ciphertext with changed ordering or forge deletions. Add authenticated metadata/tombstones
  and replay/reset protection together with desktop/server changes.
- **Add continuous SAST and resolved-dependency scanning.** GitHub had no code-scanning analysis.
  Dependabot's absence of alerts does not prove that all resolved transitive dependencies are monitored.
- **Cap provider response bodies as well.** This patch bounds sync responses. The general speech/LLM
  response path still reads the body into memory, which matters when allowing untrusted custom endpoints.
- **Test on devices.** Keystore lifecycle, lock-screen/accessibility transitions, backup/transfer behavior
  and device compromise scenarios were reviewed in source but not exercised through instrumentation.

## Validation

`gradlew.bat :core:test :app:assembleDebug :app:lintDebug` passed with **98 tests, zero test failures,
zero lint errors**. Eight lint warnings concern SwitchIntDef, unused resources, KTX suggestions,
click accessibility and constant locales. New tests cover hostile KDF values/salts, redirected provider
and sync requests, and oversized/chunked responses. Shared Rust/Kotlin crypto vectors remain unchanged.

Source pattern checks found no common embedded private-key/token formats; this was not a full
history/entropy secret scan. `git diff --check` passed. No production/device penetration test was performed.
