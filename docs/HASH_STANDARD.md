# BLAKE3 standard and SHA-256 inventory

Status: continuing policy, adopted 2026-10-27. The normative rules are in [AGENTS.md](../AGENTS.md). This inventory records the current contract; it does not assert that the runtime migration is complete.

## Scope and evidence

Repository: `Lapis332/MunitterAndroidApp`, default branch `master`. The audit used the tracked tree at the base commit recorded in the implementation PR, searched SHA-256 API calls, shell commands, manifest/configuration keys and documentation, and followed the cache writer/reader and signing consumers. No existing `AGENTS.md` or GitHub Actions workflow was present. Related Web contracts and Infrastructure policy remain owned by their respective repositories.

Classification: A = safely replaceable independent use; B = requires compatible old/new transition; C = external specification/compatibility requires retention; D = security/signature/authentication review; E = purpose or owner unresolved. A classification describes eligibility, not completed migration.

## Producer/verifier inventory and exception register

| ID / class | Locations | Producer and verifier | Decision / owner / remaining work |
|---|---|---|---|
| AND-01 / A, transition B | `app/src/main/java/com/munitter/android/notification/NotificationAvatarLoader.kt`, `NotificationAvatarCacheKey.from`, `cacheFile`, `writeCache`, `loadCached` | UTF-8 actor ID + version + relative URL produces a SHA-256 filename suffix. Both PNG writer and reader use `cacheFile`; in-flight locks use the same key. | Android notification cache owner. Eligible for BLAKE3; retained for now because this app has no BLAKE3 provider and this Windows audit host has no JDK/Android SDK for validation. A small reviewed JVM implementation and Android checks are prerequisites. New writes should use an explicit `blake3-` filename component and a versioned domain; readers must try the new name and the exact legacy SHA-256 name. Do not change the server's opaque avatar `version` token. No cache files were modified. |
| AND-02 / C (signature boundary D) | `tools/Invoke-MunitterAndroidSigning.ps1`, `docs/ENVIRONMENT_MATRIX.md`, `docs/FUTURE_ROADMAP.md`, README | RSA key creation requests `SHA256withRSA`; `X509Certificate2.RawData` produces a colon-separated SHA-256 fingerprint. Android App Links `assetlinks.json` and signature checks consume the certificate fingerprint. | Android signing/App Links owner. Keep the standard certificate/signature algorithm and exact Android fingerprint format. BLAKE3 must not replace these values. No signing material was read or printed. |
| AND-03 / C | `gradle/wrapper/gradle-wrapper.properties`, `docs/VERIFICATION_REPORT.md` | `distributionSha256Sum` pins the Gradle 8.13 download; Gradle Wrapper verifies the distribution before execution. | Gradle upstream package contract. Keep SHA-256. Revisit only if the upstream Wrapper specification changes. |
| AND-04 / B, historical evidence retained | `docs/VERIFICATION_REPORT.md`, `docs/DEVELOPMENT_STARTUP_SPLASH.md` | Historical build/device checks recorded APK hashes and PASS observations; humans and audit consumers read those exact records. | Android release/evidence owner. Preserve historical SHA-256 verbatim. Future independent artifact manifests use a versioned algorithm-tagged BLAKE3 entry; parallel SHA-256 is allowed only while a named legacy consumer needs it. Historical PASS is not evidence for this PR. |

No additional SHA-256 processing was found in tracked Android source, scripts, CI or manifests. No class E use was found. AND-02 is the explicit security-sensitive boundary; no authentication or password algorithm was migrated.

## Migration and verification boundary

This change formalizes policy and records producer/verifier contracts. It adds no runtime dependency, changes no stored values, and performs no production/device/Development operation. Runtime BLAKE3 helper implementation is pending the Android-specific dependency and toolchain review; the ecosystem's .NET Harness helper or Infrastructure `b3sum` CLI should not be embedded into the Android app merely to share code.

For a future Android runtime change, run the existing environment/signing-boundary checks and unit tests for Development Debug/Release and Production Release, lint, and the repository's formal build process. Building artifacts is separate from deploying them. Run official BLAKE3 vectors (including tree/chunk boundaries), empty/short input, bounded streaming of a large file, parallel/serial equivalence where supported, old SHA-256 cache reads, new BLAKE3 cache writes, and unsupported-algorithm rejection. Validate the chosen implementation on supported host platforms and Android; an unavailable mode must be reported as unsupported rather than PASS.

For this documentation change, `git diff --check`, local Markdown link checks, and changed-content secret-pattern review are the relevant checks. The Windows host has no JDK/Android SDK; Linux/macOS builds, Android unit tests, signed builds and a formal Security Gate were **NOT EXECUTED**. There is no repository Security Gate workflow to claim as PASS. No GitHub Actions workflow is added or dispatched.

## New SHA-256 exception procedure

Before adding a necessary SHA-256 use, add a row with a stable ID, A–E category, owner/contract, exact producer and verifier, justification, and a review/removal condition. A cache or artifact use does not inherit an exception from signing or package integrity. Unresolved ownership (E) blocks algorithm migration; security use (D) requires protocol review. This is an explicit review rule and is not presented as an enforced scanner.
