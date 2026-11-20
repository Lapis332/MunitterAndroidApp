# Munitter Android Development / Production 共存運用

この文書は、同じ検証端末へDevelopment版とProduction版を同時に保持する正式運用を定義する。環境は実行時の選択項目ではなく、署名済みartifactのApplication IDとbuild flavorで固定する。

## 正式matrix

| Environment | Variant | 表示名 | Application ID | Web endpoint | Firebase project | Signing | Icon | 同時導入 |
|---|---|---|---|---|---|---|---|---|
| Development | `developmentDebug` | `むにったー DEV` | `com.munitter.android.development.debug` | `https://dev.munitter.com/` | `munitter-dev-fcm-2026-db973d` | Development専用 | 正式icon＋小型DEV badge | Productionと共存 |
| Development | `developmentRelease` | `むにったー DEV` | `com.munitter.android.development` | `https://dev.munitter.com/` | `munitter-dev-fcm-2026-db973d` | Development専用 | 正式icon＋小型DEV badge | Productionと共存 |
| Production | `productionRelease` | `むにったー` | `com.munitter.android` | `https://munitter.com/` | `munitter-production` | Production upload key／Play App Signing | 正式icon（badgeなし） | Developmentと共存 |

`productionDebug`は存在させない。Productionには正式Release identity以外のdebug署名packageを作らず、Private Production Validationも`productionRelease`またはPlay Internal Testing artifactを使う。

## Firebase / FCM

Productionの正式cloud identityは、所有者が実際のFirebase Consoleで確認したSpark project `munitter-production`（project number `760473226308`）と、その中に登録したAndroid app `1:760473226308:android:fe0fc56e78eef174f68011`です。公式client configurationは`com.munitter.android`だけを含み、build gateがproject ID・number・Android app ID・packageを照合します。

旧artifactに含まれる`munitter-prod-fcm-2026-df60ow`は、repository設定とAPKの一致だけが確認され、cloud ownershipは確認されていません。新しい署名済みartifactの配備・実token登録・限定FCM配信は別の実機gateです。ソース変更やlocal buildだけで実配信PASSにしません。DevelopmentのFirebase file、credential、projectは変更しません。

2026-11-21のDevelopment source検証では、environment/signing-task boundary、Development Debug・Development Release・Production ReleaseのJVM tests各108件（合計324、failure/error/skip 0）、Production lint（error 0、warning 17）が通過しました。旧project・異なるproject number・異なるAndroid app ID・Development packageの4つの変更は実際のbuild gateが拒否し、公式client fileを復元しました。コンパイル済みProduction resourcesのproject ID・sender ID・app IDも上記authorityと一致します。署名・配備・実機FCM配信は、このlocal検証とは別の証拠で確認します。

Firebase設定はenvironment-wide source setへ置かず、次のexact variant fileだけを許可する。

- `app/src/developmentDebug/google-services.json`
- `app/src/developmentRelease/google-services.json`
- `app/src/productionRelease/google-services.json`

Development Firebase projectへProduction packageを、Production Firebase projectへDevelopment packageを登録・配置しない。同じ物理端末でも各packageが取得したFCM tokenは別subscriptionとして扱い、環境間でDB、設定、ログへコピーしない。

両variantは自分のexact Firebase設定からtokenを取得し、WebViewの認証session確立後またはresume時にartifact固有endpointへ登録を再試行する。初回token callbackがログインより先に発生しても、別environmentのtokenやsessionへfallbackしない。

## Signingと秘密値

Development鍵とProduction upload鍵は別keystoreで、repository外の`%LOCALAPPDATA%\Munitter\AndroidSigning`へ保存する。password等はCurrentUser DPAPI envelopeで保護し、ACLは現在の利用者、SYSTEM、Administratorsだけに限定する。Git、Gradle properties、tracked settings、log、test artifactへ値を出さない。

初期化、状態確認、署名buildは`tools/Invoke-MunitterAndroidSigning.ps1`を使用する。

```powershell
.\tools\Invoke-MunitterAndroidSigning.ps1 -Mode Status
.\tools\Invoke-MunitterAndroidSigning.ps1 -Mode Gradle -GradleArguments @(
  'assembleDevelopmentDebug',
  'assembleDevelopmentRelease',
  'bundleProductionRelease',
  '--no-daemon',
  '--console=plain'
)
```

Production keystoreはPlay App Signingのapp signing keyではなくupload keyとする。Play側でupload certificateを登録し、配布artifactのapp signing certificateをFirebaseと`assetlinks.json`へ登録する。秘密値とkeystoreのoff-host recovery確認が完了するまで「signing recovery PASS」と判定しない。

## Endpoint・link ownership

- Developmentは`dev.munitter.com`だけを内部originおよびApp Link hostとして扱う。
- Productionは`munitter.com`だけを内部originおよびApp Link hostとして扱う。
- Private Productionの認証遷移に限り、Production artifactだけが`munitter.cloudflareaccess.com/cdn-cgi/access/*`と、Cloudflareが返すexact callback `www.munitter.com/cdn-cgi/access/authorized`をWebView内で許可する。通常の`www` route、lookalike path、Development artifactにはこの例外を持たない。
- Cloudflare AccessのService Tokenや共有credentialをappへ埋め込まず、One-time PIN等の人間向け認証で`CF_Authorization` cookieをProduction app固有WebViewへ発行する。
- build後にhostを選ぶUI、remote flag、Development/Production fallbackは設けない。
- OAuth中の外部X hostは認証遷移だけに限定し、callback後はartifact固有のWeb originへ戻す。
- `assetlinks.json`はhostごとにexact packageと正式SHA-256を列挙し、別environmentのpackageを含めない。

## Storageとsession

Application IDが異なるため、app sandbox、WebView data directory、Cookie、DOM storage、Keystore、preferences、cache、download、通知tokenはOS上で分離される。shared UIDや共通storage、token copy、cookie export/importは使用しない。DevelopmentのupdateはProduction packageを、ProductionのupdateはDevelopment packageを置換してはならない。

## Build gate

`verifyEnvironmentIsolation`を全buildの`preBuild`へ接続し、次を満たさないbuildを失敗させる。

- exact Application IDとendpoint
- exact Firebase projectとpackage
- environment-wide Firebase fallbackが存在しない
- Production/Development Firebase package混入がない
- ProductionとDevelopmentのsigning keystoreが別
- Production Debug variantが存在しない

Unit testは表示名、environment badge、endpoint、Application ID、Firebase project、Production限定Cloudflare Access host/path allowlistを併せて検証する。アイコンの目視確認は自動gateを補完し、Production iconの原本をDevelopment向け生成で上書きしない。

## 物理端末の正式確認

A57では既存Development packageをアンインストールせず、正式Development packageとProduction packageを同時導入する。Home画面の2アイコン、表示名、DEV badge、両origin、session、FCM token、App Link、update非上書きを確認する。Productionの一般公開、Google Play一般公開、第三者tokenへのpushはこの運用からは承認されない。
