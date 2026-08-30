# Android Device Screen Geometry

## 目的

WebView内で「端末画面そのもの」を縮小表示するsurfaceへ、現在のAndroid WindowがOSから受け取った四隅の形状を渡す。Bottom Sheet、Modal、Dialog、カードなどのコンポーネント固有radiusは対象外であり、Web側は明示的なDevice Screen Surfaceだけへ適用する。

データフローは一方向である。

```text
WindowInsets / WindowMetrics
  -> AndroidDeviceScreenGeometryProvider
  -> DeviceScreenGeometry v1
  -> exact internal HTTPS document
  -> window.MunitterDeviceScreenGeometry.setNativeGeometry(payload)
```

## OS geometryの取得

- API 31以上では、現在WebViewへdispatchされたplatform `WindowInsets` に対して `getRoundedCorner()` を `TOP_LEFT`、`TOP_RIGHT`、`BOTTOM_RIGHT`、`BOTTOM_LEFT` の順で個別に呼ぶ。
- 各 `RoundedCorner` の `radius` とapplication-window座標の `center` をそのまま保持する。端末名、メーカー名、model名でradiusを選ばない。
- API 30以上では `WindowManager.currentWindowMetrics` のboundsとWindowInsetsを同一snapshotとして使用する。API 29以下では現在のroot view boundsとroot WindowInsetsを使用する。
- payloadの`windowBounds`、corner center、cutout rectはapplication-window座標へ統一し、`windowBounds`のoriginは常に`0,0`とする。画面上でのwindow配置は別のoptional raw field `screenPlacementBounds` に保持し、application座標と混在させない。
- `surfaceBounds` はWebViewのapplication-window内rect、`surfaceCoversWindow` はWebViewがwindow全体を覆うかを表す。覆わない構成では物理cornerをWebView自身のcornerとして誤適用せず、明示的なlow-confidence zero fallbackへ移行する。
- current safe areaとstable safe areaはAndroidX `WindowInsetsCompat` のsystem barsとdisplay cutoutから取得する。
- `DisplayCutout` のsafe insets、waterfall insets、bounding rectsはcorner radiusとは別フィールドで保持する。cutoutやsafe areaをcorner radiusへ読み替えない。
- API 31以上で`getRoundedCorner()`がnullの場合、現在のwindowがmaximum windowの該当display cornerを含むなら、OSの正規回答である「rounded cornerなし」として`radius={x:0,y:0}`、`confidence=high`を返す。windowがそのdisplay cornerを含まない場合だけ`outside-or-unavailable`のlow-confidence fallbackとする。取得できたcornerは他cornerの欠損のために捨てない。
- API 30以下は`radius={x:0,y:0}`、`center=null`、`confidence=low` のgeneric fallbackとする。

`WindowInsets.getRoundedCorner()` はcornerが存在しない場合だけでなく、そのcorner領域が現在のapplication bounds内にない場合もnullを返し得る。このため、attach前やlayout前にはfallbackを確定せず、WebViewがattachされ、正のviewportとroot WindowInsetsが揃うまでcaptureを保留する。

## Payload v1

座標とradiusの単位はAndroidがapplication windowへdispatchしたpixel単位であり、`coordinateSpace` は互換性のため `application-window-physical-px` である。これはhardware panelのnative pixelを別途推測した値ではなく、WindowInsetsとWindowMetricsで共通のwindow pixel座標である。

```json
{
  "schemaVersion": 1,
  "platform": "android",
  "coordinateSpace": "application-window-physical-px",
  "windowBounds": {
    "left": 0,
    "top": 0,
    "right": 1080,
    "bottom": 2340,
    "width": 1080,
    "height": 2340
  },
  "screenPlacementBounds": {
    "left": 0,
    "top": 0,
    "right": 1080,
    "bottom": 2340,
    "width": 1080,
    "height": 2340
  },
  "surfaceBounds": {
    "left": 0,
    "top": 0,
    "right": 1080,
    "bottom": 2340,
    "width": 1080,
    "height": 2340
  },
  "surfaceCoversWindow": true,
  "viewport": { "width": 1080, "height": 2340 },
  "orientation": { "type": "portrait-primary", "angle": 0 },
  "corners": {
    "topLeft": {
      "radius": { "x": 113, "y": 113 },
      "center": { "x": 113, "y": 113 },
      "source": "android-window-insets-rounded-corner",
      "confidence": "high"
    },
    "topRight": {},
    "bottomRight": {},
    "bottomLeft": {}
  },
  "safeAreaInsets": { "top": 97, "right": 0, "bottom": 135, "left": 0 },
  "stableSafeAreaInsets": { "top": 97, "right": 0, "bottom": 135, "left": 0 },
  "displayCutout": {
    "safeInsets": { "top": 82, "right": 0, "bottom": 0, "left": 0 },
    "waterfallInsets": { "top": 0, "right": 0, "bottom": 0, "left": 0 },
    "boundingRects": [
      { "left": 511, "top": 24, "right": 569, "bottom": 82, "width": 58, "height": 58 }
    ]
  },
  "source": "android-window-insets-rounded-corner",
  "confidence": "high",
  "curve": "circular",
  "fallback": false
}
```

例の空corner objectは省略表現である。実payloadでは四隅すべてがtop-leftと同じ完全な構造を持つ。

## WebView bridge

- AndroidからWebへの送信だけに使い、`addJavascriptInterface` は使用しない。
- native側とdocument側の両方で、現在URLのschemeがHTTPS、hostがbuild flavorのexact internal host、portが既定または443であることを確認する。OAuthや外部documentへpayloadを送らない。
- JSONは文字列としてquoteしてからdocument側でparseし、値をJavaScript sourceへ直接連結しない。
- attach、WindowInsets、layout、Activity resume、configuration change、`onPageCommitVisible`、`onPageFinished` で再captureする。同一document・同一payloadはdedupeするが、新しいdocumentには同じgeometryでも再送する。rotationやwindow resize中にsurfaceとwindowが一時的に一致しないsnapshotはhigh-confidence geometryとして送らない。
- setterがまだロードされていない場合はdocument-localなpending値を残し、page-finished lifecycleで再送する。

## Web側のscale契約

Web側はraw physical pxをnative viewportとCSS viewportの比率でCSS pxへ正規化する。Device Screen Surface自身へ正規化後のradiusを設定してからCSS transformでscaleすれば、最終的な視覚radiusも同じ比率で自動的にscaleされる。drawer progressやtransform scaleをradiusへ再度掛けて二重縮小してはならない。

## Development検証

Development buildだけで次を確認する。Production variantのbuild、install、接続先、credentialはこの検証の対象外である。

```powershell
.\gradlew.bat :app:testDevelopmentDebugUnitTest :app:lintDevelopmentDebug :app:assembleDevelopmentDebug --no-daemon

node .\scripts\device-screen-geometry-a57-audit.mjs

adb -s '<A57 serial>' logcat -c
adb -s '<A57 serial>' shell am force-stop com.munitter.android.provisional.development.debug
adb -s '<A57 serial>' shell monkey -p com.munitter.android.provisional.development.debug 1
adb -s '<A57 serial>' logcat -d -s 'DeviceScreenGeometry:I' '*:S'
```

専用auditはAPKのapplication idと接続先originをDevelopmentへ固定し、同一物理A57の重複ADB endpointをhardware identityで集約する。portrait / landscapeそれぞれでCLOSED、OPEN、OPEN途中、CLOSE途中と自然なsettle animationを採取し、raw native radius/center、Web正規化値、computed radius、transform scale後の視覚radiusを照合する。実行前の`accelerometer_rotation`と`user_rotation`は成功・失敗のどちらでも復元し、結果とスクリーンショットはgit対象外の`artifacts/`へ保存する。

API 31以上のA57では1行ログに少なくとも次を要求する。

- `source=android-window-insets-rounded-corner`
- `fallback=false`
- current window boundsとorientation
- 四隅それぞれのradius、center、source

端末全体の`dumpsys window displays`は比較用baselineにはできるが、アプリが`WindowInsets.getRoundedCorner()`を使用した証拠の代替にはしない。portrait / landscape切替後には新しいorientationと四隅を再度ログで確認する。

API 30以下のfallbackはunit testに加え、API 30 emulatorまたは明示的なprovider test seamを用意した環境で実行確認する。safe area/cutout値がradiusへ流用されていないことを確認する。

## 2026-08-30 Development実測

対象はA57 `SM-A576Q / a57xjpn`、Android 16 / API 36、Development Debug `com.munitter.android.provisional.development.debug`。Production variant、Production URL、Production credentialには触れていない。

portraitでアプリ自身の`DeviceScreenGeometry`ログは次を返した。

```text
source=android-window-insets-rounded-corner fallback=false confidence=high
bounds=0,0,1080,2340 orientation=portrait-primary@0
topLeft=r=113/113,c=113/113
topRight=r=113/113,c=967/113
bottomRight=r=113/113,c=967/2227
bottomLeft=r=113/113,c=113/2227
```

landscapeへ切り替えた後は、Activity再生成に依存せずinsets lifecycleから次を再取得した。

```text
source=android-window-insets-rounded-corner fallback=false confidence=high
bounds=0,0,2340,1080 orientation=landscape-primary@90
topLeft=r=113/113,c=113/113
topRight=r=113/113,c=2227/113
bottomRight=r=113/113,c=2227/967
bottomLeft=r=113/113,c=113/967
```

試験後は`user-rotation=free`、`accelerometer_rotation=1`、`user_rotation=0`、portraitへ戻した。A57のplatform `dumpsys window displays`にも同じradius/centerが存在することを比較確認したが、合格根拠は上記のアプリ自身のAPI 31+ログである。

Development unit testはAPI 31+の四隅非対称入力、native absent-zero、window外の部分欠損、API 30 generic fallback、partial WebView surface fallback、application-local座標とraw screen placementの分離、safe area/cutout非流用、portrait/landscape回転、payload serialization、exact-origin bridge、delivery dedupeを含めて合格した。実際のAPI 30 OS imageはローカルに存在しないため、API 30以下のplatform実機fallback確認は未実施である。

native bridgeとWeb setterの結合確認は、同じpayload v1を受けるWeb実装がDevelopmentへ反映された後に行う。native側単独ではsetter未配布時にも外部originへ送らず、page lifecycleで安全に再送することまでを確認対象とした。
