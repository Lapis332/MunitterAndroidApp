# Android 初期版アーキテクチャ

## 目的

むにったー Web 版を UI、デザイン、主要機能の Single Source of Truth とし、Android は Jetpack Compose 上に WebView と OS 連携だけを載せる独立した薄いシェルとする。投稿一覧、プロフィール、DM などをネイティブで二重実装しない。WebView bridgeは、OSでしか取得できない情報やOS操作をexact internal originへ渡す、versionedで狭い契約だけに限定する。

## 基本構成

| 項目 | 値 |
|---|---|
| Production Application ID | `com.munitter.android` |
| Development Application ID | `com.munitter.android.development`（Debugは`.debug`） |
| UI | Kotlin / Jetpack Compose / Material 3 |
| Web | Android WebView / AndroidX WebKit |
| minSdk | 24 |
| targetSdk | 36 |
| compileSdk | 36 |
| Development | `https://dev.munitter.com` |
| Production | `https://munitter.com` |

minSdk 24 は Android 7.0 以降を対象にし、現行 WebView と Photo Picker の互換経路を保ちながら、古い OS 向け分岐を限定するための初期判断である。Photo Picker 非対応端末ではシステムのファイル選択へフォールバックする。

## レイヤー

1. **Compose ホスト**
   起動画面、WebView の配置、通信エラー表示、再読み込み操作、システムバーと edge-to-edge を担当する。
2. **WebView 状態**
   WebView を不用意に再生成せず、Cookie、履歴、スクロール、ファイル選択中の状態を Activity 再生成から可能な範囲で保護する。
3. **URL ルーター**
   トップレベル遷移だけを内部、OAuth、外部、安全に処理できる特殊 scheme、拒否に分類する。
4. **OS 連携**
   Photo Picker、カメラ、ファイル選択、マイク権限、外部ブラウザーを仲介する。Web の業務処理は持たない。
5. **障害境界**
   オフライン、名前解決、タイムアウト、HTTP / サーバー障害、SSL エラーを区別し、認証・投稿中に勝手な再読み込みを行わない。

## 責務分離

| Web が所有する | Android が所有する |
|---|---|
| 画面、テーマ、レイアウト、投稿、プロフィール、DM | WebView の生成・破棄とライフサイクル |
| 認証、Cookie の発行、CSRF、Turnstile、X OAuth | Cookie / DOM storage が動く安全な WebView 設定 |
| API、SignalR、再接続、R2 メディア URL | トップレベル URL の振り分け |
| ファイル種別、複数選択、上限、変換 | Photo Picker、カメラ、ファイル chooser、URI 権限 |
| CSP、CORS、Service Worker、Web App Manifest | OS 権限、外部 Intent、通信エラー表示 |
| モーダル、画像ビューア、Web 内スワイプ | Android の戻る操作と WebView 履歴の統合 |

Android 固有の大規模な CSS / JavaScript UI 分岐は増やさず、User-Agent の短い識別子も専用画面の条件には使わない。Development Edge-to-Edgeでは、全面WebViewへOS由来のCSS safe areaを適用する能力markerに限り、Development environmentと既存Android User-Agentの組み合わせを使用する。

端末画面そのものを縮小表示するsurfaceの外形だけは、Android 12 / API 31以上の`WindowInsets.getRoundedCorner()`をsource of truthとする。四隅、window bounds、orientation、safe area、display cutout、source、confidenceを[Device Screen Geometry契約](DEVICE_SCREEN_GEOMETRY.md)でWebへ一方向に渡す。通常のModal、Bottom Sheet、Dialog、カードへこのgeometryを適用しない。

## Development Edge-to-Edge

Developmentでは `enableEdgeToEdge()` のtransparent system barsの背後までWebViewを `[0,0]` からWindow全域へ配置する。背景surfaceはsystem bars背後まで描画し、操作UIはWebViewが公開する `env(safe-area-inset-*)` を既存CSS contractで避ける。端末固定のstatus/navigation bar pxやSystem Barだけの色合わせは使わない。Device Screen Geometry bridgeのsafe area/cutoutは画面外形の共通metadataであり、既存のCSS safe area転送を置換したりcorner radiusと同一視したりしない。

IMEはCompose `imePadding()`ではなく現行WebViewのVisualViewport resizeをWeb Fixed Composerが所有し、二重paddingを避ける。Productionは従来のWebView paddingとnavigation bar surfaceを維持する。実装とA57実測値は [DEVELOPMENT_EDGE_TO_EDGE_20260826.md](DEVELOPMENT_EDGE_TO_EDGE_20260826.md) に記録する。

## セキュリティ境界

- HTTPS のみ。mixed content、SSL エラー続行、ページおよび Service Worker からの `file://` / `content://` アクセスは許可しない。chooser が返す URI は WebView のファイル入力処理だけへ渡す。
- WebView デバッグは Development のみ、Production は無効。
- トップレベル URL は厳格に制限し、一般外部リンクは標準ブラウザーへ出す。
- HTTPS サブリソースは固定ホスト一覧で遮断しない。Turnstile、CDN、直接署名 R2、SignalR などは Web の CSP / CORS / 認可に従う。
- X OAuth は state / PKCE / セッションを維持するため、開始から `/Auth/XCallback` まで同じ WebView 内で処理する。
- サードパーティ Cookie は既定で無効。Turnstile の実機結果に基づかず広げない。
- Web 権限は既定拒否。信頼済み origin の音声要求だけを `RECORD_AUDIO` と二段階で許可し、WebRTC カメラと位置情報は拒否する。
- Production ログに Cookie、token、認証ヘッダー、署名 URL、投稿・DM 内容を出さない。
- Bridgeはexact internal HTTPS originへ限定し、version、型、方向を明示する。Device Screen GeometryはnativeからWebへの一方向送信、画像共有はorigin制限付きWeb Message listenerであり、汎用`addJavascriptInterface`は導入しない。通知はBridgeを新設せず、Webの既存認証済み通知APIをAndroidのforeground／WorkManager同期から再利用する。

## 戻る操作とライフサイクル

Android の戻る操作は WebView 履歴がある場合に戻り、ない場合に Activity を終了する。Web 内モーダル、サイドバー、画像ビューアの閉じる処理は Web 実装との競合を A57 で確認する。画面回転、バックグラウンド復帰、プロセス再生成では、認証 Cookie を消さず、保留中の chooser callback を二重完了させない。

Service Worker は Web の scope `/` を尊重するが、現状 fetch cache / push を持たない。Androidは独自の通知サーバーやSignalR接続を追加せず、既存の `/Notifications?handler=Refresh/More` をCookie付きで同期し、標準NotificationChannel／badgeへ反映する。SignalRもWebの `/hubs/dm`、`/hubs/spaces` と再接続をそのまま利用する。

## ビルド境界

Development / Production の接続先、表示名、デバッグ可否はビルド時に分離する。URL を画面コードへ散在させず、接続先と許可 origin の定義へ集約する。署名鍵、パスワード、`local.properties` はリポジトリに含めない。

Production正式Application IDとDevelopment正式Application ID、environment別FCM、固定endpoint、別署名、App Linksを共存契約とする。`verifyEnvironmentIsolation`と署名wrapperをbuild gateにし、Production releaseへDevelopmentのpackage、Firebase設定、署名を流用しない。Production Debug variantは生成しない。

## 現在の判定

### 確認済み

- 独立した Compose / WebView シェルという責務境界。
- Development / Production URL、環境別 Application ID、SDK 水準。
- トップレベル遷移と HTTPS サブリソースを分ける方針。
- X OAuth、ファイル chooser、信頼 origin のマイク権限に必要な境界。
- 通知権限、`munitter_notifications` Channelのbadge許可、既読APIの状態に合わせたOS通知解除、通知タップのsingleTask内部URL遷移。

### 未確認

- Android A57 での表示、認証、メディア、SignalR、ジェスチャー、プロセス再生成。
- 将来のWeb更新後も監査時の認証・URL・メディア契約が維持されること。
- Play signing 証明書、App Links の path 範囲。
- Production release signing と Play Store 配布。
