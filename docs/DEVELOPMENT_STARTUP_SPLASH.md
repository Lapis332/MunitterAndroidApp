# Development startup splash continuity

## Scope

This change is limited to the `development` product flavor. It must not alter,
build, sign, install, upload, or publish the Production application.

The target sequence is:

```text
Android OS splash -> Activity startup overlay -> rendered WebView -> 200 ms fade -> app
```

The overlay is a visual cover only. WebView construction, Development bootstrap,
and navigation continue immediately behind it.

## Baseline (2026-08-25 JST)

Baseline evidence was captured before source changes in:

`C:\Users\Wing\Documents\MunitterAndroidArtifacts\startup-overlay\baseline-20260825-003236`

- Galaxy A57 (`SM-A576Q`, Android 16 / API 36, WebView 151): the captured
  sequence was OS icon, spinner plus loading text, a frame with only the bottom
  navigation painted, then Home. `am start -W` reported an 816 ms cold Activity
  launch; `onPageCommitVisible` arrived 1.226 seconds after the Activity start
  event and 95 ms after the current main-frame navigation began.
- Emulator (`emulator-5554`, Android 16 / API 36, WebView 133): the captured
  sequence included an iconless dark frame between OS icon frames, followed by
  the spinner/loading panel. The sampled cold launches took 3.109-3.788 seconds.
- The installed A57 APK's two `munitter_app_icon*.png` resources are pixel-for-
  pixel identical to `origin/master` at `6d36dc1`. Uncommitted icon files in the
  original checkout differ and are explicitly excluded from this change.

## Root cause

AndroidX Core SplashScreen was already installed, but the OS surface and the
Activity's first-frame UI were independent:

- the Development starting theme used the brown `#24211E` app background;
- the Activity replaced the OS icon with a Compose spinner and loading text;
- the WebView became fully opaque at `onPageCommitVisible`, which can precede a
  complete, user-presentable frame;
- no Activity-owned icon surface bridged OS splash removal to WebView painting.

## Design

### OS splash and system surfaces

- Retain `androidx.core:core-splashscreen` and `installSplashScreen()`.
- Retain the tracked `munitter_app_icon_foreground.png` as the icon source.
- Override `munitter_background` to opaque black in `src/development` only.
- Keep Development status/navigation startup surfaces black; leave Production
  flavor values and behavior unchanged.
- Remove the Development OS splash immediately when its exit callback fires.
  The Activity overlay is already the first app frame, so retaining the platform
  exit fade would fade the icon to black and then reveal the same icon again.

Android's no-icon-background splash slot is nominally 288 dp with artwork kept
inside a 192 dp safe circle. Development overrides both `ic_splash` and the
adaptive launcher foreground with a 16% inset. The tracked foreground PNG's
farthest opaque point needs at least a 14% inset to remain inside that circle;
the extra 2% preserves antialiasing and a small launcher-mask margin. The common
resources remain unchanged for Production.

### Activity overlay

- The overlay owns the full edge-to-edge Activity surface, including the areas
  behind transparent system bars.
- It contains only opaque black and the tracked foreground PNG centered in a
  196 dp square. The PNG's non-transparent bounds are approximately 160 x 113 dp,
  matching the unmasked artwork produced by the OS 288 dp slot with the
  Development 16% inset.
- The overlay is created visible for a new Development Activity and is never
  re-armed by `onResume` or later WebView navigations.

## Icon safe-area follow-up (2026-08-25 JST)

Galaxy A57 evidence captured in
`C:\Users\Wing\Documents\MunitterAndroidArtifacts\icon-safe-area\a57-before-20260825-063606`
showed two related sizing defects:

- One UI cropped the Development adaptive launcher artwork at the upper left
  and right because the shared 10% foreground inset exceeded the circular safe
  region.
- The OS splash used an 8% inset and circularly cropped the mark to about
  524 x 393 px. At the OS-to-Activity boundary the overlay restored the complete
  528 x 373 px mark, making the silhouette visibly change for one frame.

The correction reuses the same tracked PNG without pixel edits. Development
alone resolves both drawable names to 16%-inset wrappers, and the overlay is
scaled to the corresponding unmasked size. Development also removes the OS
splash at its exit callback without the platform icon fade; the only remaining
fade is the Activity overlay's 200 ms WebView-ready exit. This keeps launcher,
OS splash, and Activity overlay geometry continuous while leaving Production
resources and behavior untouched.

### Follow-up verification

- API 36 emulator `Munitter_Development_API_36`: the OS artwork measured
  421 x 297 px and the Activity overlay 420 x 297 px. A 50 ms frame audit found
  no iconless transition frame. The corrected icon is fully inside the circular
  app-drawer mask. Development instrumented tests passed 6/6.
- Galaxy A57 `SM-A576Q`, Android 16 / API 36: the launcher icon has visible
  black clearance above and beside the complete mark. The full-resolution
  startup frames measured 452 x 320 px before the OS handoff and 449 x 317 px
  after it, with the same uncropped silhouette.
- A57 cold launch passed 10/10. The 50 ms sample audit found zero internal blank
  frames, silhouette aspect ratios of 1.4088-1.4277 (the cropped baseline was
  1.333), and at most 14 px of total width variation including the launcher
  enter scaling. Evidence and machine-readable results are in
  `C:\Users\Wing\Documents\MunitterAndroidArtifacts\icon-safe-area\a57-final-20260825-065300`.
- A57 background-to-foreground passed 10/10 with the same process and Activity
  task each time. Every captured frame showed the retained Home screen; the
  startup overlay did not reappear.
- `adb install -r` preserved the original first-install timestamp and existing
  signed-in Home session. The installed Development Debug APK SHA-256 is
  `6F4686CF3FD0B63D42D668BD0759675252506356C3E0FD792083265C2F21046A`.

### Ready signal

Each main-frame `onPageStarted` receives a monotonically increasing navigation
generation. After `onPageCommitVisible`, the WebView client:

1. probes for visible, meaningful document content;
2. requires two browser `requestAnimationFrame` passes;
3. posts `WebView.postVisualStateCallback` for that generation;
4. waits for three following Android compositor frames;
5. asks the Activity to start the overlay exit.

`onPageFinished` may relax the generic document-content fallback, but never
removes the overlay by itself. A callback from an older generation is ignored,
including redirects and stale visual-state completions. A formal main-frame
failure removes the overlay so the existing native error UI can be shown.

### Fade

The exit is opacity-only and 200 ms. Compose animation duration scaling applies,
so a disabled system animator scale results in an immediate removal. No minimum
display time or fixed-delay release is introduced.

## Rollback

Revert the implementation commit. No cookies, WebView data, application IDs,
signatures, Firebase settings, backend state, or Production resources are
migrated by this change.
