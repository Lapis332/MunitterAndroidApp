# Web 契約監査

更新日: 2026-07-28

## 判定の読み方

- **確認済み（ソース）**: 監査時点の Web ソースから確認した事項。
- **確認済み（Android 設計）**: Android 初期版の実装方針として固定した事項。
- **未確認**: 実アカウント、実機、または稼働中サーバーとの組み合わせで確認が必要な事項。

この文書は実機試験の成功を表明するものではない。調査中に Web の PID、稼働コミット、ソース HEAD、dirty 状態が他作業によって複数回変化した。2026-07-28 01:08 JST の最終 Runtime Reflection では公開ホスト、live、ready、health は 200 だった一方、稼働 DLL は `e5411b9afaae29df21eadae2a64b912f4eba5319`、ソース HEAD は `377aa1b737a073fbf11a56f3281c114646c7c8d7` で不一致、Web 作業ツリーも dirty だった。Android作業からWeb側は変更・再起動していない。Webが安定した時点でリリース前に同じ監査を再実行する。

## 接続先と認証

| 項目 | 確認結果 | 状態 |
|---|---|---|
| Development | `https://dev.munitter.com` | 確認済み（設計） |
| Production | `https://munitter.com` | 確認済み（設計） |
| セッション Cookie | `__Host-Munitter.SessionState`、HttpOnly、Secure、SameSite=Lax、Path=/、サーバー idle 30 日、セッション Cookie | 確認済み（ソース） |
| 認証 Cookie | `__Host-MunitterSessionToken`、HttpOnly、Secure、SameSite=Lax、30 日 | 確認済み（ソース） |
| CSRF | `__Host-Munitter.Antiforgery`、Secure、SameSite=Strict、ヘッダー `X-CSRF-TOKEN` | 確認済み（ソース） |
| X OAuth 開始先 | `https://twitter.com/i/oauth2/authorize` | 確認済み（ソース） |
| X OAuth callback | 各環境の `/Auth/XCallback` | 確認済み（ソース） |
| Turnstile | ログイン画面から第三者 iframe / challenge を利用 | 確認済み（ソース） |

X OAuth は OAuth state、PKCE、開始時のサーバーセッションに依存する。したがって `x.com` / `twitter.com` の認証遷移と callback は、開始した WebView と同じ Cookie jar・履歴で完結させる。通常の投稿内 X リンクは標準ブラウザーへ出し、認証中の遷移と混同しない。

サードパーティ Cookie は初期値を無効とする。Turnstile がこの設定で完了するかは Android A57 の実機で確認し、失敗が再現した場合に限り、対象 WebView での有効化を最小変更として再検討する。認証回避は追加しない。

## ナビゲーションとネットワーク境界

トップレベル遷移は厳格に判定する。

- 選択中環境のむにったー HTTPS URLは WebView 内で開く。
- X OAuth 中に必要な `x.com` / `twitter.com` の HTTPS 遷移は、認証フローとして同じ WebView 内で開く。
- 一般の外部 HTTPS URLは標準ブラウザーへ渡す。
- `intent:`、`mailto:`、`tel:` は明示的に処理し、解決できない Intent や危険な scheme は拒否する。
- HTTP、`file:`、`content:`、`javascript:` などをトップレベルで読み込ませない。
- SSL エラーは続行せず、mixed content は許可しない。

一方、画像、動画、CSS、JavaScript、Turnstile、SignalR などの **HTTPS サブリソース** はトップレベル URL と同じホスト制限を掛けない。直接署名された R2 URLを含め、Web が正当に参照する HTTPS リソースを Android の固定ホスト一覧で遮断しないためである。CSP、CORS、署名 URL、サーバー認可は Web 側の契約をそのまま尊重する。

Web 側の CSP は監査時点で Report-Only 運用を含む。Android 側で CSP を回避・緩和せず、違反レポートやコンソール警告をアプリ固有障害と決めつけない。Production で WebView デバッグや秘密情報を含むログは有効にしない。

## メディアと Web 権限

確認済みの Web 入力は次のとおり。

- 投稿と DM: 画像・動画、複数選択。
- プロフィールとグループ: 画像。
- Spaces: `getUserMedia({ audio: true })` によるマイク。

Android のファイル選択は Web の MIME type と複数選択指定を尊重し、Photo Picker、カメラ撮影、通常のファイル選択を必要に応じて提示する。広いストレージ権限、Android 側の独自圧縮、Web 上限の二重実装は行わない。カメラ撮影用 URI は一時権限として扱う。

Web 権限要求は次の境界にする。

- マイク: 選択中の信頼済みむにったー origin からの音声要求だけを対象に、Android の `RECORD_AUDIO` 許可後に承認する。
- WebRTC カメラ: 拒否する。ファイル chooser 経由のカメラ撮影とは別経路である。
- 位置情報: 拒否する。
- その他の Web 権限: 既定で拒否する。

## SignalR、Service Worker、PWA

- DM は `/hubs/dm`、Spaces は `/hubs/spaces` を使用し、Web 側に再接続処理がある。Android はネイティブ SignalR 接続を重複実装せず、WebView の接続を維持する。
- Web App Manifest と、scope `/` で登録される Service Worker がある。
- 監査した Service Worker には fetch cache と push 処理がない。初期版は Service Worker をオフラインキャッシュや通知基盤として扱わない。
- Cookie、Web storage、Service Worker データを通常起動や一時的な通信エラーで消去しない。

## 確認済み

- 認証・セッション・CSRF Cookie の属性と X OAuth callback 構造。
- Turnstile、投稿・DM・プロフィール・グループのファイル入力、Spaces の音声取得。
- DM / Spaces の SignalR hub と Web 側再接続。
- Manifest / Service Worker の存在、および fetch cache / push 非搭載。
- トップレベル遷移を制限しつつ HTTPS サブリソースを Web 契約に委ねる Android 設計。
- 最終確認時のDevelopment公開ホスト、live、ready、healthの200応答。

## 未確認

- Android A57 上でのメールログイン、Turnstile challenge、X OAuth 完走。
- サードパーティ Cookie 無効のままでの Turnstile の全条件。
- 再起動・プロセス再生成後のログイン維持。
- 署名付き R2 の画像・動画表示、複数選択、動画投稿、認証付きダウンロード。
- DM / Spaces の SignalR 再接続、バックグラウンド復帰、低速・切断時の挙動。
- Android System WebView 上の Service Worker 更新挙動。
- CSP Report-Only 違反の実環境内容、CORS、OAuth provider 側ポリシーの将来変更。
- 将来のWeb更新後も同じ契約が維持されること。
- 最終スナップショットで不一致だった稼働DLLとWebソースHEADが、次回配布時に一致すること。

## Web 変更時の再確認

次の変更は Android のコード変更が不要でも回帰確認する。

1. 認証 URL、callback、Cookie、SameSite、CSRF、Turnstile の変更。
2. トップレベルで遷移する外部ドメインの追加・削除。
3. `input type="file"` の accept、multiple、capture の変更。
4. WebRTC 権限、ダウンロード、ポップアップの追加。
5. SignalR hub、再接続、認証方式の変更。
6. CSP / CORS、R2 / CDN、署名 URLの変更。
7. Service Worker の fetch、cache、push 導入。
8. 戻る操作、モーダル、画像ビューア、スワイプ、safe area の変更。
