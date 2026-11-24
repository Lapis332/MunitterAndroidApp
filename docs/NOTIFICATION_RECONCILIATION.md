# Native notification identity and unread reconciliation

## Notification tap acknowledgment and device badge hints (2026-11-24)

The native shell captures concrete positive user/group notification identities
from both cold and warm exact-origin notification intents. Automatic
acknowledgment additionally requires an app-generated
per-card private PendingIntent capability bound to its exact identity and
target. An external app's forged/missing proof still permits normal authorized
navigation but cannot initiate a read. Capabilities live in the existing
backup-disabled app-private notification preferences, are bounded, and are
removed when the native card is canceled. Existing old cards without a proof
remain navigable and gain a proof when ordinary reconciliation republishes them.
Navigation starts immediately; a separate bounded job acknowledges an ordinary notification using
the existing authenticated `MarkItemRead` POST and antiforgery endpoint. DM taps
do not masquerade as ordinary notification reads. A successful response must
explicitly acknowledge the requested owned identity, including already-read
rows; `markedCount` alone is not read proof. Only confirmed sources are removed,
and a grouped card retains unrelated unread sources. Session changes, newer
pushes/refreshes and late avatar completions retain their existing fences.

The notifications-page total belongs to the selected account. Launcher counts
instead use `deviceTotalUnreadCount` from the existing authenticated
`/api/realtime/ui-state`; a failure never falls back to an active-account zero.
The acknowledgment response uses the same device aggregate. No cookie, token,
notification body or server response is logged by the added request path.

An explicit `badge_sync` FCM message is a hint rather than an ordinary event. It
requires no synthetic notification identity, title, body or alert. Its supplied
count is not applied because the current wire format has no server ordering
revision. It requests a foreground authenticated reconciliation and the existing
bounded background worker. Badge hints invalidate older in-flight HTTP tickets
before replacing the existing unique refresh work, so a KEEP/coalescing window
cannot discard the last hint and let an older count commit. Ordinary scheduled
refreshes retain their existing KEEP policy. The exact-origin main-frame Web bridge accepts only
a fixed hint after `munitter:unread-state-changed`, and ignores JavaScript counts.
Periodic/background scheduling and launcher rendering remain OS-controlled;
these paths do not promise delivery under force-stop, offline or battery limits.

Local parser, intent, owned-read, session, stale-response and synthetic HTTP
tests cover the new contract. Build/test/lint results and private Development
APK installation are separate release evidence; preparation does not claim
physical-device badge reception or authorize store publication/provider changes.

FCM and the authenticated notifications page describe the same ordinary events
with different identifiers: `user:42` / `group:17` in FCM and `user-42` /
`group-17` in HTTP. The modern grouped-like loader instead emits a grouped
read-snapshot token as its sole CSV `sourceNotificationIds` entry. That token
does not expose the underlying `user-N` identities. Treating these unrelated
display IDs as independent events can create duplicate native children during
any foreground or background refresh, without a navigation or another push.

Android now normalizes the ordinary source IDs, persists each native display
card's source identities. A snapshot-only grouped row updates the summary but
does not create an uncorrelated detail card; obsolete snapshot-only native cards
are retired on refresh or a valid push while known source and DM cards remain. If a payload supplies
concrete underlying source IDs, the coordinator can coalesce them, but that is
not the modern grouped API contract. Explicit ordinary read rows cancel matching
source aliases. Impossible legacy
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
Each sync also captures one in-memory Cookie header for all pages and rejects
results, including unauthorized clears, if the current header has changed. No
cookie is persisted, logged or hashed. The push wire format supplies no recipient
owner identity, so this does not establish a client-side stale-FCM account fence.

## Verification boundary

The regression tests exercise the production coordinator with an in-memory
native-card ledger: push/poll aliases in both orders, snapshot-only summary
fallback, synthetic concrete grouped sources, old-state
migration, explicit read/zero cancellation, authoritative partial totals, DM and
filtered-row preservation, concurrent pushes, out-of-order HTTP, late avatar
completion, and delayed nonzero pushes following a confirmed read. State codec
tests cover restart persistence of grouped identities, counts and read evidence.

`app/src/test/resources/native-modern-grouped-notification.json` is an unchanged,
minimal native-relevant projection shared with the Web regression fixture. The
Web test invokes the modern `LoadNotificationsAsync` path and the actual
`BuildNotificationPayload`, then checks every projected item property against
that serialized output. Its grouped source is the read-snapshot token, not the
underlying `user-11` / `user-12` events. The Android parser and coordinator consume
this same fixture rather than assuming the unused legacy loader's source shape.
The corresponding Web regression is commit
`63bc61185969424b2b09c5332298cbeed4f84163` (23 pagination tests passed, no skips).

Local verification on 2026-11-22 used the existing JDK 21 / Gradle 8.13 / Android
SDK toolchain and pinned dependencies. `testDevelopmentDebugUnitTest`,
`testDevelopmentReleaseUnitTest` and `testProductionReleaseUnitTest` each passed
146 tests (438 total, zero failures/errors/skips). `lintDevelopmentDebug`,
`lintDevelopmentRelease` and `lintProductionRelease` each completed with zero
errors and 29 warnings (KTX suggestions and dependency-version availability
checks). Environment isolation and signing-task boundary checks also passed.
The initial offline Debug lint attempt lacked cached instrumentation-test
dependencies; normal dependency resolution completed the same pinned checks.

These checks establish logical identity, total selection and ordering behavior.
Exact per-source aggregate read cancellation while other notifications remain
unread is not established for the current snapshot-only API. Such source cards
remain until a concrete read row or authoritative zero; the server total still
controls the summary. These checks do not establish the exact cause of a historical launcher number, or verify
Samsung's numeric badge rendering. A formally signed Development APK, device
notification behavior and the private Production upgrade remain separate gates.
No source test authorizes a provider send or a public release.
