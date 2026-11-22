# Native notification identity and unread reconciliation

FCM and the authenticated notifications page describe the same ordinary events
with different identifiers: `user:42` / `group:17` in FCM and `user-42` /
`group-17` in HTTP. A grouped like row has its own display ID and a CSV
`sourceNotificationIds` containing the underlying `user-N` IDs. Treating these
display IDs as independent events can create duplicate native children during
any foreground or background refresh, without a navigation or another push.

Android now normalizes the ordinary source IDs, persists each native display
card's source identities, and cancels source cards when an aggregate replaces
them. Explicit read rows cancel matching source aliases. Impossible legacy
`user:0` / `group:0` cards are removed on a valid push or successful refresh.
Migration retains each old native ID until it can be canceled by that exact ID.

The HTTP aggregate remains the unread authority. Detail cards each use number
1; the single group summary carries the total. Local display-card cardinality
does not replace a supplied server total. DMs are omitted from the page, and
ordinary rows can be filtered or bounded by history limits, so missing rows
alone are not considered read. HTTP zero clears all app-owned native cards.

Activity, WorkManager and Firebase use one process-wide state coordinator.
Newer sync requests and FCM mutations invalidate older in-flight HTTP results.
State writes and native cancellation/rendering occur in the same transaction;
network requests remain outside the lock. Avatar completion checks the committed
revision before rendering. The latest 512 known read source IDs are retained to
reject delayed pushes for an event already acknowledged by HTTP; a later HTTP
unread row can restore that source. Every push requests an authenticated refresh
to reconcile its current count, including zero or invalid legacy identities.

## Verification boundary

The regression tests exercise the production coordinator with an in-memory
native-card ledger: push/poll aliases in both orders, grouped sources, old-state
migration, explicit read/zero cancellation, authoritative partial totals, DM and
filtered-row preservation, concurrent pushes, out-of-order HTTP, late avatar
completion, and delayed nonzero pushes following a confirmed read. State codec
tests cover restart persistence of grouped identities, counts and read evidence.

Local verification on 2026-11-22 used the existing JDK 21 / Gradle 8.13 / Android
SDK toolchain and pinned dependencies. `testDevelopmentDebugUnitTest`,
`testDevelopmentReleaseUnitTest` and `testProductionReleaseUnitTest` each passed
138 tests (414 total, zero failures/errors/skips). `lintDevelopmentDebug`,
`lintDevelopmentRelease` and `lintProductionRelease` each completed with zero
errors and 29 warnings (KTX suggestions and dependency-version availability
checks). Environment isolation and signing-task boundary checks also passed.
The initial offline Debug lint attempt lacked cached instrumentation-test
dependencies; normal dependency resolution completed the same pinned checks.

These checks establish logical identity, total selection and ordering behavior.
They do not establish the exact cause of a historical launcher number, or verify
Samsung's numeric badge rendering. A formally signed Development APK, device
notification behavior and the private Production upgrade remain separate gates.
No source test authorizes a provider send or a public release.
