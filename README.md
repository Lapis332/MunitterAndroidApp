# むにったー Android（初期版）

既存のむにったーWeb版を唯一のUI・機能本体として表示する、Kotlin + Jetpack Compose + WebViewの薄いAndroidシェルです。投稿一覧やプロフィール等をAndroid側へ二重実装していません。

> [!IMPORTANT]
> DevelopmentとProductionは同じ端末へ常時共存させます。Productionは`com.munitter.android`、Developmentは`com.munitter.android.development`（Debugは`.debug`）で、endpoint、Firebase、署名、storageをbuild時に固定・分離します。正式matrixは[Development / Production共存運用](docs/ENVIRONMENT_MATRIX.md)を参照してください。

## 構成

- Kotlin 2.3.21 / Compose compiler plugin 2.3.21
- Jetpack Compose BOM 2026.06.01 / Material 3
- AndroidX WebKit 1.16.0
- Android Gradle Plugin 8.13.2 / Gradle 8.13
- `compileSdk = 36` / `targetSdk = 36` / `minSdk = 24`
- JVM bytecode target 17（Gradle実行はJDK 17以上。確認環境はMicrosoft OpenJDK 21）

`minSdk 24` は現行WebKit 1.16.0の下限です。Android 7.0までインストールできますが、安全性とWeb互換性のため、Android System WebViewまたはChromeを最新に更新してください。古いOS/WebViewでの動作保証は行っていません。

AGP 9.x / API 37 previewへ追随するより、インストール済みの安定版API 36、Kotlin 2.3対応が明記されたAGP 8.13.2、対応Gradle 8.13の組み合わせを初期版の再現性優先で採用しています。依存更新は実機回帰試験とセットで行います。

## ビルドバリアント

| Variant | 接続先 | WebViewデバッグ | Application ID |
|---|---|---:|---|
| `developmentDebug` | `https://dev.munitter.com/` | 有効 | `com.munitter.android.development.debug` |
| `developmentRelease` | `https://dev.munitter.com/` | 有効 | `com.munitter.android.development` |
| `productionRelease` | `https://munitter.com/` | 無効 | `com.munitter.android` |

`productionDebug`は安全上生成しません。URL、内部ホスト、環境名、デバッグ可否は[app/build.gradle.kts](app/build.gradle.kts)のProduct Flavorへ集約し、実行時設定やユーザー入力では切り替えません。

## Android Studioで開く

1. Android Studio Quail 2（2026.1.2）またはAGP 8.13.2対応版をインストールします。
2. Android SDK Platform 36とBuild Tools 36.0.0をインストールします。
3. このリポジトリのルートをAndroid Studioで開きます。
4. Gradle JDKへJDK 17以上を指定します。
5. Build Variantsで `developmentDebug` を選びます。
6. USBデバッグを有効にした端末を接続し、Runします。

`local.properties` はAndroid StudioがローカルSDKパスを生成するためGit管理されません。

## コマンドライン

PowerShell例:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME = 'C:\Program Files (x86)\Android\android-sdk'

.\tools\Invoke-MunitterAndroidSigning.ps1 -Mode Gradle -GradleArguments @(
  'testDevelopmentDebugUnitTest',
  'testDevelopmentReleaseUnitTest',
  'testProductionReleaseUnitTest',
  'lintDevelopmentDebug',
  'assembleDevelopmentDebug',
  'assembleDevelopmentRelease',
  'assembleProductionRelease',
  'bundleProductionRelease'
)
```

主要APK:

- 実機確認用Development APK: `app\build\outputs\apk\development\debug\app-development-debug.apk`
- 正式Development APK: `app\build\outputs\apk\development\release\app-development-release.apk`
- 正式Production APK: `app\build\outputs\apk\production\release\app-production-release.apk`
- Play upload用Production AAB: `app\build\outputs\bundle\productionRelease\app-production-release.aab`

署名materialはrepository外のDPAPI保護領域からwrapperがprocess環境へ一時注入します。秘密値をGradle propertyやコマンドラインへ渡さないでください。

## A57向け Development Smoke

- 前提: A57 (`SM_A576Q` / `a57x` / product `a57xjpn`) にワイヤレスADBで接続済み
- 実行: `.\scripts\Invoke-A57DevelopmentSmoke.ps1`
- 対象: `developmentDebug` (`com.munitter.android.development.debug`)
- 自動確認:
  - `developmentDebug` APKビルド
  - 対象端末の厳密マッチング
  - `install -r`（更新インストール）
  - cold launch、foreground確認、logcat保存
  - screenshot保存
  - crash/ANRの簡易判定
- Production APKはインストールしないガード付き
- 生成ログ/画像: `artifacts\device-test\`（`.gitignore` で除外）

## 実機へインストール

端末側で開発者向けオプションとUSBデバッグを有効にし、接続許可を承認します。

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r `
  '.\app\build\outputs\apk\development\debug\app-development-debug.apk'
```

既存の同一debug Application IDと署名が異なる場合だけ、端末側で旧debug版をアンインストールしてから再実行します。本番アプリやそのデータは削除しないでください。

## Android側の責務

Android側に実装するのは次の境界機能だけです。

- 安全なWebView設定、Cookie/DOM Storage、WebView状態保存
- development/production接続先のビルド時分離
- 内部URL、X OAuth、通常の外部URL、危険スキームのルーティング
- Androidの予測型戻る操作とWebView履歴、全画面動画
- Photo Picker優先の画像・複数画像・動画選択、カメラアプリ、汎用ファイル選択
- 内部オリジンのマイク要求だけを対象にしたOS権限確認
- Cookie/User-Agentを必要な内部ホストだけへ渡す安全なダウンロード
- 起動、通信、DNS、タイムアウト、TLS、5xx、WebViewプロセス異常の表示と手動再読み込み

UI、投稿/DM処理、認証、CSRF、SignalR、アップロード制限、メディア変換、テーマはWeb版の責務です。JavaScriptインターフェースは追加していません。

## セキュリティ要点

- HTTPSのみ。cleartextとMixed Contentは禁止
- SSLエラーは必ず中止し、無視して続行しない
- ページとService Workerの`file://` / `content://`アクセス、file URLからの汎用アクセス、位置情報を無効化
- productionではWebViewデバッグを無効化
- top-level遷移は環境ごとの公式ホストだけをWebView内で開く
- X OAuth中だけ `twitter.com` / `x.com` を同じWebViewで開き、PKCE/Session Cookieを保持
- 通常の外部HTTPSリンクはユーザー操作時だけ標準ブラウザで開く
- HTTPSサブリソースはWeb側CSPへ委ねるため、Turnstile、フォント、media、署名付きR2 PUTをAndroid側で遮断しない
- サードパーティCookieは初期値で無効。Turnstile実機検証で必要性が実証された場合だけ再検討
- Web権限は内部オリジンの音声だけ許可候補とし、OSの `RECORD_AUDIO` 許可後に限定して付与
- Cookie、認証情報、署名URL、投稿内容をログへ出力しない
- keystore、署名設定、`local.properties`、秘密値は`.gitignore`で除外

## テストと確認資料

- [Web契約監査](docs/WEB_CONTRACT_AUDIT.md)
- [責務分離と設計](docs/ARCHITECTURE.md)
- [Android A57実機チェックリスト](docs/DEVICE_TEST_CHECKLIST.md)
- [Development / Production共存運用](docs/ENVIRONMENT_MATRIX.md)
- [Android Development Edge-to-Edge実機報告](docs/DEVELOPMENT_EDGE_TO_EDGE_20260826.md)
- [通知・App Links・Play Storeの次段階](docs/FUTURE_ROADMAP.md)
- [今回の検証結果](docs/VERIFICATION_REPORT.md)

Web版変更時は、認証/Cookie、外部ホスト、ファイルinput、マイク、ダウンロード、SignalR、PWA/Service Worker、CSP/Permissions-Policy、主要URLの変更有無を上記監査文書と照合してください。通常のHTML/CSS/JavaScript/UI変更はAndroid側へそのまま反映されます。

## 現時点の制約

- App Linksは環境別hostをmanifestで固定し、正式署名SHA-256とhost別`/.well-known/assetlinks.json`の整合を公開前gateとします。
- Android OS通知は、既存の認証済みWeb通知APIを再利用するforeground同期（60秒）と、ネットワーク接続時のWorkManager同期（最短15分）で実装しています。FCMも環境別Firebase設定、認証付きtoken登録、ログアウト・アカウント切替時の無効化、通知表示を実装していますが、Production実配信はPrivate Production Validationまで無効です。
- Android 13以降の通知権限は、認証済み画面が表示された自然なタイミングで一度だけ要求します。通知Channelは `munitter_notifications`、badge許可はChannel作成時に有効化します。
- 実アカウントを使うメール/Xログイン、投稿、DM、Spacesマイク、バックグラウンド復帰、回転、低速通信は実機チェックリストで確認してください。
- WebViewの `MediaRecorder` がSpacesのAAC/MP4を提供できるかは端末とSystem WebViewの版に依存します。
- Android DevelopmentのEdge-to-Edge、主要5画面、3ボタン/gesture navigation、IME、foreground復帰はA57で確認済みです。認証、全メディアchooser、Spaces、低速・障害系など未実施項目は実機チェックリストどおり未確認として扱います。
