# Android Development Edge-to-Edge 検証報告

実施日: 2026-08-26（JST）

## 判定と対象

- 対象は `developmentDebug` (`com.munitter.android.provisional.development.debug`) のみ。
- Android Development は正式な Edge-to-Edge Window と、全面 WebView、Web 側 safe area の組み合わせへ変更した。
- Production variantはビルド・実行しておらず、Production Web、Production DB、Production R2、Production Cloudflareには接続・変更していない。
- 実機は Samsung Galaxy A57 (`SM-A576Q` / `a57xjpn`)、Android 16 / API 36、1080 x 2340、density 450。
- Android System WebView は `151.0.7922.169`。

## 修正前の構造と根本原因

Activity の root / ComposeView 自体は `[0,0][1080,2340]` だったが、WebView を包む Compose `Box` が `WindowInsets.safeDrawing` と `imePadding()` の両方を受けていた。このため修正前の実機では次の境界になっていた。

```text
statusBars  [0,0][1080,97]
WebView     [0,97][1080,2205]
navigation [0,2205][1080,2340]
```

`MainActivity` はすでに `enableEdgeToEdge()` を呼んでいたため、Window 設定不足ではなく、子 WebView を system bars の内側へ再度縮めていたことが黒帯状の独立領域の根本原因だった。Development の3ボタン navigation barには固定黒 scrimとcontrast enforcementも残っていた。

## 採用した構成

- `ComponentActivity.enableEdgeToEdge()` を現行の正式 API として継続使用する。
- Developmentだけstatus/navigation barのscrimをtransparentにし、API 29以降は `window.isNavigationBarContrastEnforced = false` とする。
- DevelopmentだけWebView hostから `safeDrawing` / `imePadding()`を外し、WebView実体をWindow全域へ配置する。
- Productionは従来のWebView paddingとnavigation bar色を維持する。
- ネイティブ遷移用header bitmapは、`WindowInsetsCompat.Type.systemBars()` と `displayCutout()`から得た実測top insetを既存56dpへ加える。端末固有pxは使わない。
- WebView M151がCSS `env(safe-area-inset-*)`へ公開する値を既存の `--app-safe-area-*` 契約で使用する。JavaScript bridgeやAndroidからの固定値注入は追加しない。
- Web側のsafe-area layout分類は、Development-like environmentかつ既存 `MunitterAndroid/` User-Agentの場合だけ有効にする。Productionでは常にfalseである。
- IMEはCompose `imePadding()`とWeb側の二重所有にせず、現行WebViewのVisualViewport resizeをWeb Fixed Composerが一度だけ処理する。

## 実測した描画範囲とInsets

修正後は3ボタン・gestureの両方で、Compose host、`ViewFactoryHolder`、外側WebViewがすべて `[0,0][1080,2340]`。Chrome DevTools ProtocolのWebView descriptionも `screenY=0`, `height=2340` だった。

### 3ボタン navigation

- OS status bar: 97 physical px。CSS `safe-area-inset-top = 35px`。
- Home header: `y=0`, header innerは `y=35px`。背景だけがstatus bar背後へ入り、プロフィール、Mロゴ、タブはstatus iconsより下にある。
- OS navigation bar: `[0,2205][1080,2340]`、135 physical px。CSS `safe-area-inset-bottom = 48px`。
- BottomNav: `y=716.1889px`, `height=115.8111px`, `bottom=832px`。操作本体はsafe areaより上、下48pxは同じBottomNav背景だけである。
- 3ボタンglyphはOS表示のまま。contrast scrimを無効にしたため、独立した黒いOS面ではなくMunitter背景の上に表示される。

### gesture navigation

- AOSP gestural overlayで `navigation_mode=2` を実測後、必ず元の3ボタンへ復元した。
- OS navigation insetは `[0,2298][1080,2340]`、42 physical px。CSS `safe-area-inset-bottom = 15px`。
- BottomNavは `y=749.1889px`, `height=82.8111px`, `bottom=832px`。gesture handleの背後まで同じ背景が連続した。
- 検証終了時は `navigation_mode=0`、threebutton overlay enabled、bottom inset 135pxへ復元済み。

## IMEと復帰

- DM詳細とPost composerでIMEを実表示した。IME中も外側WebViewは `[0,0][1080,2340]` のまま、可視Web content / VisualViewportだけが縮む。
- DM詳細: `innerHeight=832px`, `visualViewport.height=473.6000px`。100ms間隔16回でviewport height、offset、input位置、BottomNav非表示状態は全て同一だった。
- Post composer: 同じVisualViewport値を100ms間隔20回測定し、textarea `y=173.9778px`, `bottom=429.1889px`を含め全サンプル同一。小刻みな上下振動は0回。
- IMEを閉じてもtextarea focusを維持する既存Fixed Composer仕様ではBottomNavを隠したままにする。その状態だけDevelopment Android markerで下48pxを予約し、toolbar bottomをCSS 784px = physical 2205pxに合わせた。操作UIは3ボタン領域へ入らない。
- backgroundからforegroundへ戻した結果、PID `20864`を維持し、`/post/create`を維持した。focus中はIMEが再表示され、同じVisualViewport layoutへ戻った。crash / ANRは検出されなかった。

## 画面回帰

- Home -> Search -> Post -> Notifications -> DM -> Home をBottomNavの実タップで往復し、routeとactive itemを確認した。
- DM一覧から `/dm/45` 詳細へ実タップで到達した。
- Home timelineは実スワイプで `scrollTop=460.8px`へ変化し、WebView境界とsystem bar背景は維持された。
- サイドバーを実タップでOPEN/CLOSEし、status/navigation領域を含む背景、backdrop、閉じた後のHomeを確認した。
- 白フラッシュ、追加の黒帯、crash、ANR、BottomNavの左右ずれ、system barとの操作UI重なりは確認されなかった。

## テスト

```text
gradlew testDevelopmentDebugUnitTest lintDevelopmentDebug assembleDevelopmentDebug
  BUILD SUCCESSFUL
  unit tests: 65 passed
  lint: errors 0 (既存のdependency/KTX warning 27)

Web Release solution build
  warnings 0 / errors 0

Android Development edge-to-edge / BottomNav / repost composer contracts
  9 passed / 0 failed
```

Production APK/AAB、Production test task、Production runtimeは実行していない。

## 証跡

証跡はgit管理外の `artifacts/device-test/edge-to-edge-20260826/` に保存した。

- 修正前3ボタンHome: `before-a57-3button-home.png`
- 修正後3ボタンHome: `after-a57-3button-home-final.png`
- 修正後gesture Home: `after-a57-gesture-home.png`
- Home / Search / Post / Notifications / DM: `after-a57-3button-*.png`
- DM詳細とIME: `after-a57-3button-dm-detail*.png`
- Post IME開閉: `after-a57-3button-post-ime-final.png`, `after-a57-3button-post-ime-closed-final.png`
- foreground復帰: `after-a57-3button-foreground-return.png`
- WebView bounds: 同名 `.xml`

Web側反映commitは `35851c4e7c209f03011e6302ab0e2aa607b2d181` と `c4619f14d30b818b061721a341a78457e8ef012c`。Development runtimeは最終commit `c4619f14d30b818b061721a341a78457e8ef012c`、branch `master`、dirty `false`でlive / ready / public readyを確認した。
