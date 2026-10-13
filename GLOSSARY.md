# Glossary

本プロジェクトの全モジュール・関数・定数・設定・セキュリティ対策の一覧。
コードリーディングの補助資料として使用。

updated: 2026-10-13

## モジュール・クラス

| 名前                      | 種別     | ファイル                         | 役割                                                         |
| ------------------------- | -------- | -------------------------------- | ------------------------------------------------------------ |
| `MainActivity`            | Activity | MainActivity.kt                  | ランチャー、サービス制御UI（状態は `CaptureService.running` を購読） |
| `CaptureActivity`         | Activity | capture/CaptureActivity.kt       | ロック中判定・MediaProjection同意取得・遅延待機・撮影オーケストレーション |
| `CaptureService`          | Service  | capture/CaptureService.kt        | 常駐前景サービス、mediaProjection型への昇格と完了ハンドシェイク |
| `ProjectionController`    | クラス   | capture/ProjectionController.kt  | MediaProjection/VirtualDisplayライフサイクル管理（使い捨て） |
| `ScreenCaptureHelper`     | クラス   | capture/ScreenCaptureHelper.kt   | ImageReader管理・最初のフレーム待機・切り出し・temp保存（使い捨て） |
| `CaptureGeometry`         | data class | capture/CaptureGeometry.kt     | 撮影サイズ・密度・ステータスバー高さ（Activityの WindowManager から取得） |
| `CaptureRegion`           | Object   | capture/CaptureGeometry.kt       | パディング列とステータスバーを除いた切り出し範囲の計算（純粋関数） |
| `ServiceLauncher`         | Object   | capture/ServiceLauncher.kt       | CaptureService起動（applicationContext正規化）               |
| `EditorActivity`          | Activity | ui/EditorActivity.kt             | 画像編集（クロップ・保存・コピー・共有）                     |
| `CropView`                | View     | ui/CropView.kt                   | タッチ操作による画像クロップUI                               |
| `CropGeometry` / `Box` / `CropHandle` | Object / data class / enum | ui/CropGeometry.kt | クロップ枠の計算（比率合わせ・移動・リサイズ、純粋関数） |
| `SettingsActivity`        | Activity | ui/SettingsActivity.kt           | アプリ設定画面                                               |
| `MediaStoreSaver`         | クラス   | data/MediaStoreSaver.kt          | MediaStore経由のギャラリー保存                               |
| `ClipboardShare`          | クラス   | data/ClipboardShare.kt           | クリップボードコピー・共有Intent作成                         |
| `ClipboardAutoClear`      | Object   | data/ClipboardAutoClear.kt       | クリップボード自動クリアのタイマー（プロセス単位）           |
| `TempCache`               | Object   | data/TempCache.kt                | 一時ファイル・共有/クリップボード用ファイルの置き場と削除規則 |
| `SettingsDataStore`       | クラス   | data/SettingsDataStore.kt        | DataStore Preferences ラッパー                               |
| `SettingsRules`           | Object   | data/SettingsDataStore.kt        | 設定値の許容値・既定値・読み込み時の検証                     |
| `ScreenshotTileService`   | Service  | tile/ScreenshotTileService.kt    | クイック設定タイル                                           |

## 主要メソッド

### CaptureActivity

| 名前                      | 役割                                                         |
| ------------------------- | ------------------------------------------------------------ |
| `startScreenCapture`      | `awaitPrepareComplete` で Deferred 取得 → Foreground昇格要求 → 完了待ち（2秒） |
| `continueScreenCapture`   | 遅延待機（待機中は `letTouchesPassThrough`）→ `ScreenCaptureHelper.capture` |
| `letTouchesPassThrough`   | 窓を NOT_TOUCHABLE / NOT_TOUCH_MODAL / NOT_FOCUSABLE・alpha 0 にし、タッチを下のアプリへ通す |
| `openEditor`              | 撮影結果を受けて EditorActivity 起動                          |

### CaptureService

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `running` / `isRunning`   | サービスの起動状態（StateFlow / 現在値）             |
| `awaitPrepareComplete`    | per-request `CompletableDeferred<Unit>` 生成・登録（既存があればcancel） |
| `cancelPrepare`           | 保留中の Deferred をキャンセル + null化              |

### ScreenCaptureHelper

| 名前                  | 役割                                                 |
| --------------------- | ---------------------------------------------------- |
| `capture`             | VirtualDisplay作成 → 最初のフレームを `setOnImageAvailableListener` で待機（2秒でタイムアウト）→ 切り出し → temp保存（`AtomicBoolean` 多重ガード） |
| `release`             | helperScope.cancel → ProjectionController.stop → リスナー解除 → ImageReader.close（冪等化） |

内部処理: `performCapture` / `awaitFirstImage` / `copyToBitmap`（1回の createBitmap でパディング列とステータスバーを除去）/ `saveBitmapToTemp`。Image に触れる処理は Main スレッドに限定。

### ProjectionController

| 名前      | 役割                                                         |
| --------- | ------------------------------------------------------------ |
| `start`   | `getMediaProjection` + Android 14+ Callback登録 + `createVirtualDisplay`（成否を Boolean で返す） |
| `stop`    | Callback unregister → VirtualDisplay.release → MediaProjection.stop → consent token null化（冪等化） |

### EditorActivity

| 名前                      | 役割                                                         |
| ------------------------- | ------------------------------------------------------------ |
| `runExclusive`            | クロップ結果を取り出し、実行中の操作があれば無視（多重タップ防止） |
| `saveAndFinish`           | ギャラリー保存して終了                                       |
| `copyAndFinish`           | クリップボードへコピー（自動クリア秒数は設定から）して終了   |
| `share`                   | 共有用 chooser を起動                                        |
| `showAspectRatioDialog`   | アスペクト比選択（自由/1:1/4:3/16:9/9:16）                   |
| `isPathSafe`              | 受け取ったパスが temp ディレクトリ配下か canonicalPath で検証 |

### CropView / CropGeometry

| 名前                  | 役割                                                 |
| --------------------- | ---------------------------------------------------- |
| `setBitmap`           | Bitmap読み込み、クロップ領域を全体に初期化（比率固定中は比率に合わせる） |
| `setAspectRatio`      | アスペクト比固定（null=フリー）                      |
| `getCroppedBitmap`    | 逆行列変換で元画像座標系のクロップ領域を抽出         |
| `reset`               | クロップ状態をリセット                               |
| `CropGeometry.fitAspect` | 今の枠と画像範囲の両方に収まる最大の矩形へ縮めて比率を合わせる |
| `CropGeometry.move`   | 移動量を画像範囲内にクランプして平行移動（サイズ不変） |
| `CropGeometry.resize` | ハンドルでリサイズ。比率固定時はドラッグしていない側の角/辺を固定 |

### MediaStoreSaver

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `saveBitmapToGallery`     | IS_PENDING=1 で挿入 → 書き込み・ストリームclose → IS_PENDING=0。失敗時は行を削除 |

### ClipboardShare / ClipboardAutoClear

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `copyImageToClipboard`    | クリップボード用ファイルに書き出し、FileProvider URI を ClipData に設定し、自動クリアを予約 |
| `createShareIntent`       | 共有用ファイルに書き出し、`FLAG_GRANT_READ_URI_PERMISSION` 付きの chooser Intent を返す |
| `ClipboardAutoClear.onClipSet` | 以前のタイマー取り消し・古いクリップボード用ファイル削除・指定秒後の `clearPrimaryClip()` とファイル削除を予約 |

### TempCache

| 名前                         | 役割                                                 |
| ---------------------------- | ---------------------------------------------------- |
| `createTempFile`             | 撮影直後の一時ファイル作成（24時間超の古いファイルを先に削除） |
| `createShareFile`            | 共有用ファイル作成（24時間超の古い共有用ファイルを先に削除） |
| `createClipboardFile`        | クリップボード用ファイル作成                         |
| `deleteClipboardFilesExcept` | 現在のクリップ以外のクリップボード用ファイルを削除   |
| `isExpired`                  | 保持期間の判定（純粋関数）                           |

### SettingsDataStore

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `settings`                | 設定値のFlow（読み込み失敗時は既定値、許容外の値は既定値に置換） |
| `updateDelaySeconds` / `updateClearSeconds` | 許容値のみ保存（それ以外は IllegalArgumentException） |
| `resetAllToDefaults`      | 全設定を初期値にリセット                             |

### CaptureGeometry

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `fromActivity`            | `maximumWindowMetrics` のサイズと statusBars インセットを Activity の WindowManager から取得 |
| `CaptureRegion.contentRegion` | パディング列とステータスバーを除いた切り出し範囲を返す |

## 定数

### CaptureService

| 名前                  | 値                                               | 役割                           |
| --------------------- | ------------------------------------------------ | ------------------------------ |
| `ACTION_STOP`             | `dev.screenshoteditor.ACTION_STOP`             | サービス停止インテント（通知の「停止」） |
| `ACTION_PREPARE_CAPTURE`  | `dev.screenshoteditor.ACTION_PREPARE_CAPTURE`  | Foreground昇格要求インテント   |
| `NOTIFICATION_ID`         | 1001                                           | サービス通知ID                 |
| `CHANNEL_ID`              | `screenshot_service`                           | サービス通知チャネルID         |

### CaptureActivity

| 名前                  | 値       | 役割                                               |
| --------------------- | -------- | -------------------------------------------------- |
| `PREPARE_TIMEOUT_MS`  | 2000L    | Foreground昇格完了待ちタイムアウト（ミリ秒）       |

### ScreenCaptureHelper

| 名前                | 値       | 役割                                   |
| ------------------- | -------- | -------------------------------------- |
| `FRAME_TIMEOUT_MS`  | 2000L    | 最初のフレームを待つ上限（ミリ秒）     |
| `MAX_IMAGES`        | 2        | ImageReader の最大保持枚数             |

### CropView

| 名前                     | 値     | 役割                           |
| ------------------------ | ------ | ------------------------------ |
| `HANDLE_RADIUS_DP`       | 10dp   | ハンドルの描画半径             |
| `HANDLE_TOUCH_RADIUS_DP` | 24dp   | ハンドルのタッチ判定半径（48dpのタッチ領域） |
| `MIN_CROP_SIZE_DP`       | 48dp   | クロップ枠の最小辺             |

### EditorActivity

| 名前                  | 値               | 役割                           |
| --------------------- | ---------------- | ------------------------------ |
| `EXTRA_IMAGE_PATH`    | `image_path`     | 画像パス受け渡しキー           |

### MediaStoreSaver

| 名前                  | 値                           | 役割                           |
| --------------------- | ---------------------------- | ------------------------------ |
| `DIRECTORY_SCREENSHOTS` | `Pictures/Screenshots`     | 保存先ディレクトリ             |
| `MIME_TYPE_PNG`       | `image/png`                  | 保存形式                       |
| `DATE_FORMAT`         | `yyyy-MM-dd_HH-mm-ss_SSS`    | ファイル名日時フォーマット（Locale.US） |

### TempCache

| 名前               | 値                 | 役割                                   |
| ------------------ | ------------------ | -------------------------------------- |
| `TEMP_DIR`         | `temp`             | 撮影直後の一時ファイル（filesDir内）   |
| `IMAGE_DIR`        | `images`           | FileProvider で公開するキャッシュ（cacheDir内） |
| `SHARE_SUBDIR`     | `share`            | 共有用ファイル（images内）             |
| `CLIPBOARD_SUBDIR` | `clipboard`        | クリップボード用ファイル（images内、常に最大1つ） |
| `MAX_AGE_MS`       | 24時間             | 一時ファイル・共有用ファイルの保持期間 |

### ClipboardAutoClear

| 名前                | 値     | 役割                                   |
| ------------------- | ------ | -------------------------------------- |
| `MAX_DELAY_SECONDS` | 3600   | 自動クリアまでの秒数の上限（破損した設定値への防御） |

## Settings データモデル

| プロパティ          | デフォルト値 | 許容値              | 役割                                   |
| ------------------- | ------------ | ------------------- | -------------------------------------- |
| `delaySeconds`      | `0`          | 0, 1, 2, 3, 5       | 撮影遅延秒数（0 = 即時撮影）           |
| `clearSeconds`      | `60`         | 0, 15, 30, 60       | クリップボード自動クリア秒数（0 = クリアしない） |
| `disableOnLock`     | `true`       | -                   | ロック時撮影無効（設定画面なし）       |

派生値: `isDelayedCapture`（delaySeconds > 0）、`isClipboardAutoClearEnabled`（clearSeconds > 0）。
旧バージョンのキー（`immediate_capture` / `auto_clear_clipboard` / `remember_action` / `remembered_action` / `persistent_notification` / `notification_confirmation`）は `ObsoleteSettingsMigration` が削除する。

## 列挙型

### CropView.TouchMode（private）

| 名前      | 役割                           |
| --------- | ------------------------------ |
| `NONE`    | タッチなし                     |
| `DRAG`    | クロップ領域のドラッグ         |
| `RESIZE`  | クロップ領域のリサイズ         |

### CropHandle

| 名前            | 役割                           |
| --------------- | ------------------------------ |
| `NONE`          | ハンドルなし                   |
| `TOP_LEFT`      | 左上ハンドル                   |
| `TOP_RIGHT`     | 右上ハンドル                   |
| `BOTTOM_LEFT`   | 左下ハンドル                   |
| `BOTTOM_RIGHT`  | 右下ハンドル                   |
| `TOP`           | 上辺ハンドル                   |
| `BOTTOM`        | 下辺ハンドル                   |
| `LEFT`          | 左辺ハンドル                   |
| `RIGHT`         | 右辺ハンドル                   |

## マニフェスト設定

### 権限

| 権限                                      | API要件 | 用途                           |
| ----------------------------------------- | ------- | ------------------------------ |
| `POST_NOTIFICATIONS`                      | 33+     | 通知表示                       |
| `FOREGROUND_SERVICE`                      | 28+     | 前景サービス実行               |
| `FOREGROUND_SERVICE_SPECIAL_USE`          | 34+     | 常駐通知用の specialUse 前景サービス |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION`     | 34+     | MediaProjection前景サービス    |

### コンポーネント

#### Activity

| 名前                  | exported | launchMode        | 特記事項                       |
| --------------------- | -------- | ----------------- | ------------------------------ |
| `MainActivity`        | true     | singleTop         | ランチャーアクティビティ       |
| `CaptureActivity`     | true     | singleInstance    | 撮影用ランチャー起点（TORQUEのハードキー割り当て用）、透明テーマ、excludeFromRecents、回転等で再生成しない |
| `EditorActivity`      | false    | singleTop         | 画面回転対応                   |
| `SettingsActivity`    | false    | standard          | 設定画面                       |

#### Service

| 名前                      | exported | foregroundServiceType | 特記事項                       |
| ------------------------- | -------- | --------------------- | ------------------------------ |
| `CaptureService`          | false    | specialUse \| mediaProjection | 常駐前景サービス       |
| `ScreenshotTileService`   | true     | -                     | クイック設定タイル（`BIND_QUICK_SETTINGS_TILE` 権限で保護） |

#### Provider

| 名前              | exported | grantUriPermissions | 特記事項                       |
| ----------------- | -------- | ------------------- | ------------------------------ |
| `FileProvider`    | false    | true                | 公開範囲は `cacheDir/images/` のみ |

## セキュリティ対策

| 項目                         | 実装                                                              |
| ---------------------------- | ----------------------------------------------------------------- |
| INTERNET権限なし             | 完全オフライン動作、データ外部送信リスクゼロ                      |
| 最小権限原則                 | POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_MEDIA_PROJECTION のみ |
| コンポーネント非公開         | 内部Activity/Serviceは exported=false（例外: ランチャー起点の MainActivity・CaptureActivity、権限保護された TileService） |
| FileProvider                 | ContentURI経由の安全なファイル共有。撮影直後の一時ファイル（filesDir/temp）は公開しない |
| URI権限                      | `FLAG_GRANT_READ_URI_PERMISSION` で共有先にのみ一時付与。共有用ファイルは24時間で削除 |
| クリップボード自動クリア     | 既定は無効（opt-in）。有効時は指定秒数後に `clearPrimaryClip()` とファイル削除。中身が自アプリのものかは確認できない（Android 10以降のバックグラウンド読み取り制限） |
| バックアップ無効             | allowBackup=false                                                 |
| PendingIntent安全化          | FLAG_IMMUTABLE + FLAG_UPDATE_CURRENT                              |
| ProGuard/R8                  | リリースビルドでコード難読化・リソース縮約                        |
| ログ除去                     | リリースビルドで `Log.v/d/i/w` を R8 の `-assumenosideeffects` で除去（`Log.e` は残す） |
| ロック時撮影禁止             | KeyguardManager判定で無効化（KeyguardManager取得不可時もロック扱い） |
| パス検証                     | EditorActivity は temp ディレクトリ配下のパスだけ受け付ける       |
| 設定値の検証                 | 保存時は許容値以外を拒否、読み込み時は許容値以外を既定値に置換    |
