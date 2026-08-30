# 将来ロードマップ

## 現在地

Web UI をそのまま表示する独立した Compose / WebView シェルを維持しつつ、Android OS通知は既存Web通知APIの認証Cookie同期と環境分離FCMで実装した。App LinksとPlay Store release signingは未導入であり、FCM実配信は正式Production test identity作成後に確認する。

確認済みの前提:

- Production Application IDは `com.munitter.android` に確定。既存Development IDは互換性のため維持。
- Web Service Worker は scope `/` で、監査時点では fetch cache / push を持たない。
- Android通知はforeground 60秒同期とWorkManager 15分周期同期で、通知Channelの標準badgeとWeb既読状態を再利用する。
- X OAuth は Web セッションと PKCE に依存するため同じ WebView 内で完結させる。
- App Links に必要な最終署名証明書とサーバーの `assetlinks.json` は確定していない。

## Phase 1: 初期版の実機受け入れ

1. Android A57 で `docs/DEVICE_TEST_CHECKLIST.md` を実施する。
2. Turnstile をサードパーティ Cookie 無効のまま完走できるか確定する。
3. メール / X OAuth、Cookie 維持、R2 メディア、ファイル chooser、DM / Spaces SignalR を確認する。
4. 各受け入れ試験時にDevelopmentの稼働コミットとWebソースを再度対応付ける。
5. 不具合は Web 所有か Android OS 連携かを切り分け、Android 専用 UI 分岐を安易に追加しない。

完了条件は主要項目の実機結果と、未実施項目・既知不具合が記録されていること。自動テストだけでは完了しない。

## Phase 2: 公開アイデンティティと署名

1. ~~Play Storeで使う正式Application IDを所有者が決定する。~~ `com.munitter.android` に確定済み。
2. ~~仮IDから正式IDへ、初回公開前に一度だけ計画的に移行する。~~ Production flavorへ反映済み。Development IDは破壊せず維持。
3. Play App Signing、upload key、鍵の保管・復旧・担当者を決める。
4. release 設定、プライバシーポリシー、Data safety、権限説明、ストア素材、内部テスト track を準備する。
5. Production の署名済み AAB / APK と versionCode 運用を CI に追加する。

秘密鍵、パスワード、サービスアカウント JSON は Git に置かない。仮 ID のまま公開しない。

## Phase 3: App Links

正式 Application ID は確定済み。最終署名証明書の SHA-256 fingerprint が確定してから着手する。

1. 投稿、プロフィール、DM などの **既存 canonical HTTPS URL** を Web 側で棚卸しする。
2. 認証不要 URL と認証必要 URL、存在しない URL、アプリ未導入時の Web fallback を定義する。
3. `https://munitter.com/.well-known/assetlinks.json` を正式 package / fingerprint で配信する。
4. Development は本番と別の package / 証明書として扱うか、通常の Web URL のままにする。
5. Android の path allowlist を必要最小限にし、未知 path は通常の Web 起動へ安全にフォールバックする。
6. インストール済み / 未導入、ログイン済み / 未ログイン、期限切れセッションを実機で確認する。

現段階で App Links を部分実装しない理由は、`assetlinks.json`と最終署名がなく、検証済みリンクを保証できないためである。

## Phase 4: FCM（構成済み、実配信保留）

サーバーAPI、DB、環境境界、Android clientはPhase 3Bで追加済み。Production一般配信は無効のまま、Private Production Validationでtest tokenを作成して実配信を確認する。

1. ユーザー、端末、アプリ環境、FCM token の関連モデルを定義する。
2. token 登録、更新、失効、ログアウト時解除、アカウント切替を認証付き API にする。
3. 通知 payload は秘密情報や DM 本文を最小化し、canonical HTTPS URL または検証済み route を持たせる。
4. 通知タップから投稿、プロフィール、DM へ進み、未ログイン時はログイン後に安全に再開する。
5. Web 通知との二重通知を防ぐ配信ポリシーを決める。
6. Development / Production の Firebase project と token を分離する。
7. token や payload を Production ログに残さず、削除・同意撤回手順を用意する。

現 Service Worker に push 処理はないが、将来 Web push を導入する場合は FCM との重複を再評価する。Android が独自に SignalR を常時接続してプッシュ代替にする設計は採用しない。

## Phase 5: Web 契約の強化

- CSP の Report-Only 違反を整理し、Web 側で enforcing へ移行する。
- Turnstile、OAuth provider、CORS、SameSite の変更を release gate で確認する。
- 認証付きダウンロードの存在と安全な受け渡し方式を確定する。
- R2 / CDN の署名 URLをトップレベル許可先へ誤追加せず、HTTPS サブリソースとして扱う。
- Service Worker に fetch cache を導入する場合、認証済み HTML / API / メディアを保存しない方針と logout 時の消去を設計する。
- Web の URL、file input、権限、SignalR hub の契約テストを整備する。
- 稼働 commit、APK version、WebView version を秘密情報なしで相関できる診断情報を用意する。

## Phase 6: 品質と展開

- A57 に加え、minSdk 24、現行 Android、主要 WebView version、低メモリ端末を含む端末 matrix を作る。
- 認証を迂回せず、テストアカウントと安全な Development データで end-to-end 試験を整える。
- 回転、プロセス再生成、バックグラウンド制限、権限拒否、低速・切断を自動 / 手動の両方で継続確認する。
- アクセシビリティ、文字サイズ、キーボード、予測型戻るを release checklist に入れる。
- iOS 版を検討する際も Web 契約を再利用し、OS 連携層だけを実装する。

## 維持する非目標

- 投稿、プロフィール、DM などの完全ネイティブ二重実装。
- User-Agent による大規模な Android 専用 Web UI。
- 必要性が実証されていない JavaScript bridge。
- Web のアップロード制限、変換、SignalR、通知状態の Android 側二重実装。
- 未確定の URL、署名 fingerprint の推測。

## 未確認・意思決定待ち

- release signing / Play App Signing の運用。
- App Links 対象 path と `assetlinks.json` 配信責任者。
- FCM実配信、利用者同意、削除運用のProduction test identityによる検証。
- Web push 導入予定と二重通知ルール。
- 将来のDevelopment更新時における稼働commitとWeb監査snapshotの継続的な対応付け。
- Android A57 を含む実機受け入れ結果。
