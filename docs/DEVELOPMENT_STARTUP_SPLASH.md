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

Android's no-icon-background splash slot is nominally 288 dp with artwork kept
inside a 192 dp safe circle. The existing `ic_splash` inset is retained because
it is already installed and visually validated as the formal Development icon.

### Activity overlay

- The overlay owns the full edge-to-edge Activity surface, including the areas
  behind transparent system bars.
- It contains only opaque black and the tracked foreground PNG centered in a
  230 dp square. The PNG's non-transparent bounds are approximately 188 x 133 dp,
  matching the measured OS splash artwork width of about 187 dp on both target
  devices.
- The overlay is created visible for a new Development Activity and is never
  re-armed by `onResume` or later WebView navigations.

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
