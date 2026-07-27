# 初期版検証報告

実施日: 2026-07-28（JST）

## 判定

| 対象 | 判定 | 根拠 |
|---|---|---|
| Androidプロジェクトの構成・コンパイル | **Verified** | Gradle Wrapper 8.13、JDK 21、SDK 36で全対象タスク成功 |
| JVM単体テスト | **Verified** | Development 41件、Production 41件、失敗・error・skip 0 |
| Android Lint | **Verified** | 両Debug Flavorともerror 0。残る各7 warningは、意図的に保守的な互換版へ固定した依存更新通知のみ |
| Debug APK | **Verified** | Development / Productionを生成し、AAPTメタデータとAPK Signature Scheme v2署名を検証 |
| Production releaseビルド | **Verified** | R8 / resource shrinkを通したunsigned APKを生成。正式鍵がないため署名・配布は未実施 |
| Compose instrumentationテスト | **Partially Verified** | 2件を実装し、test APKのコンパイル・パッケージ成功。端末未接続のため実行は未確認 |
| Development公開ページ | **Partially Verified** | モバイルChromium 412×915で未認証ページを確認。Android System WebViewではない |
| Development runtime到達性 | **Verified** | 最終スナップショットで公開ホスト、live、ready、healthが200 |
| Development runtimeとWebソースの一致 | **Failed** | 稼働DLL `e5411b9…` とソースHEAD `377aa1b…` が不一致。Web作業ツリーも外部変更でdirty |
| Android A57 / エミュレーター実動作 | **Not Verified（Blocked）** | `adb devices -l` は0台。Android StudioとEmulator packageも未導入 |

`Blocked` は実機・エミュレーターがこの環境に存在しないことだけを示し、APKビルド失敗を意味しない。

## 実行したAndroid検証

最終ソースに対して次を一括実行し、成功した。

```text
testDevelopmentDebugUnitTest
testProductionDebugUnitTest
lintDevelopmentDebug
lintProductionDebug
assembleDevelopmentDebug
assembleProductionDebug
assembleProductionRelease
assembleDevelopmentDebugAndroidTest
```

SDKは読み取り専用配置のため、Gradleが`package.xml`を更新できない旨の警告を出すが、Platform 36 / Build Tools 36.0.0の検出と全タスクは成功した。Gradle 8.13配布物のSHA-256をWrapper設定へ固定した。

単体テストはURL許可判定、内部・外部・危険URL、X OAuth状態、非標準port、Development / Production定数、戻る判定、ファイルMIMEと複数選択、障害分類、安全なダウンロードホストを対象とする。

## APK

| APK | サイズ | SHA-256 | 状態 |
|---|---:|---|---|
| `app-development-debug.apk` | 12,266,812 bytes | `9AC6995BA50B8B7ACEADBFB5334C55675BF72F41DEFF6A0A8EDDCD737C894539` | debug署名済み、install可能 |
| `app-production-debug.apk` | 12,266,948 bytes | `5FBD3BA208A72A2BAE09806CDEAE5F3E87FF7C5708F5821BC4A2D14B97EAF200` | debug署名済み、install可能 |
| `app-production-release-unsigned.apk` | 955,678 bytes | `094E99E7CF6FBA40078B6BDA686B05E42C8A0C9F34613BF8055A40B159AABD74` | unsigned、直接install不可 |

AAPT確認値:

- Development Debug: `com.munitter.android.provisional.development.debug`、minSdk 24、targetSdk 36、`むにったー (開発)`。
- Production Debug: `com.munitter.android.provisional.debug`、minSdk 24、targetSdk 36、`むにったー`。

## Webとブラウザーの確認

モバイルChromiumでは `/`、`/terms`、`/privacy`、`/contact`、`/password/forgot`、`/email/register` の直接200応答と表示を確認した。ルートは横overflowなし、Turnstile表示、Service Worker制御あり。規約・プライバシー等にはdocument幅がviewportより約39〜40px大きい計測があり、見た目の切れは確認できなかったもののA57で再確認する。

Turnstileの補助的DNS失敗とPAT 401、CSP Report-Only / WebGL由来のnoiseは観測したが、主要ページの致命的request failureとpage error、同時間帯のサーバーErrorログは0だった。X OAuthはセッション状態を作るため開始せず、ソースから同一WebViewで必要なtop-level遷移を確認しただけである。

ブラウザー確認中もDevelopment runtimeが他作業で複数回入れ替わった。したがってブラウザー結果は公開Webの限定的なスモーク確認であり、最終稼働DLLと現在のWebソースを一致させたE2E保証ではない。

## 未確認

- Android A57へのインストール、Android System WebViewでの初回表示。
- メール / X OAuthログイン、Turnstile完走、Cookie維持、ログアウト・再ログイン。
- Home、各スワイプ、サイドバー、モーダル、画像ビューア、予測型戻る。
- 画像・複数画像・動画・カメラ、認証付きダウンロード、マイク権限。
- DM / Spaces SignalR、通知画面、低速・切断・復帰、回転、キーボード、文字サイズ。
- 正式Application ID、release署名、App Links、FCM、Play Store公開。

実機では [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) を先頭から記録し、成功していない項目を合格扱いしない。

## 秘密値と既存Webの扱い

Android側はCookie、token、認証header、署名URL、投稿内容をログへ出すコードを持たない。`local.properties`、build生成物、keystore、署名設定、秘密設定はGit除外対象である。

既存Webリポジトリのファイル、dirty変更、stash、プロセス、Tunnel、DB、Redis、R2は変更していない。Development runtimeの不一致は観測結果としてのみ記録した。
