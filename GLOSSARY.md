# Glossary

本プロジェクトの全モジュール・関数・定数・設定・セキュリティ対策の一覧。
コードリーディングの補助資料として使用。

updated: 2026-02-02

## モジュール・クラス

| 名前                      | 種別     | ファイル                         | 役割                                                         |
| ------------------------- | -------- | -------------------------------- | ------------------------------------------------------------ |
| `MainActivity`            | Activity | MainActivity.kt                  | ランチャー、サービス制御UI                                   |
| `CaptureActivity`         | Activity | capture/CaptureActivity.kt       | MediaProjection権限取得・撮影実行                            |
| `CaptureService`          | Service  | capture/CaptureService.kt        | 常駐前景サービス、撮影トリガー管理                           |
| `ProjectionController`    | クラス   | capture/ProjectionController.kt  | MediaProjection/VirtualDisplayライフサイクル管理             |
| `StatusBarInsets`         | Object   | capture/StatusBarInsets.kt       | ステータスバー高さ取得                                       |
| `EditorActivity`          | Activity | ui/EditorActivity.kt             | 画像編集（クロップ・保存・コピー・共有）                     |
| `CropView`                | View     | ui/CropView.kt                   | タッチ操作による画像クロップUI                               |
| `SettingsActivity`        | Activity | ui/SettingsActivity.kt           | アプリ設定画面                                               |
| `MediaStoreSaver`         | クラス   | data/MediaStoreSaver.kt          | MediaStore経由のギャラリー保存                               |
| `ClipboardShare`          | クラス   | data/ClipboardShare.kt           | クリップボードコピー・共有・自動クリア                       |
| `TempCache`               | Object   | data/TempCache.kt                | 一時ファイル管理・自動クリーンアップ                         |
| `SettingsDataStore`       | クラス   | data/SettingsDataStore.kt        | DataStore Preferences ラッパー                               |
| `NotificationHelper`      | Object   | notif/NotificationHelper.kt      | 通知チャネル作成・通知ビルド                                 |
| `PersistentNotifier`      | クラス   | notif/PersistentNotifier.kt      | 常駐通知管理（Capture/Stopアクション）                       |
| `ScreenshotTileService`   | Service  | tile/ScreenshotTileService.kt    | クイック設定タイル                                           |

## 主要メソッド

### CaptureActivity

| 名前                      | 役割                                                         |
| ------------------------- | ------------------------------------------------------------ |
| `startScreenCapture`      | MediaProjection権限応答を受けて撮影準備                      |
| `performScreenCapture`    | ImageReader/VirtualDisplay作成、ピクセルキャプチャ           |
| `captureScreenshot`       | ピクセル抽出、ステータスバートリム、temp保存、エディタ起動   |
| `trimStatusBar`           | Bitmap上部のステータスバー領域を除去                         |
| `cleanup`                 | ImageReader/VirtualDisplay/MediaProjectionリソース解放       |

### CaptureService

| 名前                  | 役割                                                 |
| --------------------- | ---------------------------------------------------- |
| `handleCaptureAction` | ロック状態確認、遅延適用、CaptureActivity起動        |
| `isScreenLocked`      | KeyguardManagerでデバイスロック状態を判定            |

### EditorActivity

| 名前                      | 役割                                                         |
| ------------------------- | ------------------------------------------------------------ |
| `handleComplete`          | 記憶アクション確認、アクション選択ダイアログまたは自動実行   |
| `showActionDialog`        | 保存/コピー/破棄の選択ダイアログ表示                         |
| `executeAction`           | アクション実行（save/copy_discard/discard）                  |
| `handleShare`             | FileProvider経由で共有インテント発行                         |
| `showAspectRatioDialog`   | アスペクト比選択（free/1:1/4:3/16:9/9:16）                   |

### CropView

| 名前                  | 役割                                                 |
| --------------------- | ---------------------------------------------------- |
| `setBitmap`           | Bitmap読み込み、クロップ領域を全体に初期化           |
| `setAspectRatio`      | アスペクト比固定（null=フリー）                      |
| `getCroppedBitmap`    | 逆行列変換で元画像座標系のクロップ領域を抽出         |
| `reset`               | クロップ状態をリセット                               |

### MediaStoreSaver

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `saveBitmapToGallery`     | IS_PENDINGフロー対応のMediaStore保存                 |
| `generateFilename`        | タイムスタンプ形式ファイル名生成                     |

### ClipboardShare

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `copyImageToClipboard`    | FileProvider URI経由でClipData設定                   |
| `scheduleClear`           | 指定秒数後のクリップボード自動クリア                 |
| `shareImage`              | 共有インテント発行、30秒後URI権限自動revoke          |

### TempCache

| 名前                  | 役割                                                 |
| --------------------- | ---------------------------------------------------- |
| `createTempFile`      | 24時間自動クリーンアップ付き一時ファイル作成         |
| `cleanTempFiles`      | 一時ファイルの手動クリーンアップ                     |
| `cleanCacheFiles`     | キャッシュファイルの手動クリーンアップ               |

### SettingsDataStore

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `settings`                | 設定値のFlowプロパティ                               |
| `resetAllToDefaults`      | 全設定を初期値にリセット                             |

### StatusBarInsets

| 名前                      | 役割                                                 |
| ------------------------- | ---------------------------------------------------- |
| `getStatusBarHeight`      | WindowMetrics API (30+) + フォールバックでステータスバー高さ取得 |

## 定数

### CaptureService

| 名前                  | 値                                               | 役割                           |
| --------------------- | ------------------------------------------------ | ------------------------------ |
| `ACTION_CAPTURE`      | `com.example.screenshoteditor.ACTION_CAPTURE`    | 撮影アクションインテント       |
| `ACTION_STOP`         | `com.example.screenshoteditor.ACTION_STOP`       | サービス停止インテント         |
| `NOTIFICATION_ID`     | 1001                                             | サービス通知ID                 |
| `CHANNEL_ID`          | `screenshot_service`                             | サービス通知チャネルID         |

### EditorActivity

| 名前                  | 値               | 役割                           |
| --------------------- | ---------------- | ------------------------------ |
| `EXTRA_IMAGE_PATH`    | `image_path`     | 画像パス受け渡しキー           |
| `ACTION_SAVE`         | `save`           | ギャラリー保存アクション       |
| `ACTION_COPY_DISCARD` | `copy_discard`   | コピー後破棄アクション         |
| `ACTION_DISCARD`      | `discard`        | 破棄アクション                 |

### NotificationHelper

| 名前                      | 値                       | 役割                           |
| ------------------------- | ------------------------ | ------------------------------ |
| `CHANNEL_ID_SERVICE`      | `screenshot_service`     | サービス通知チャネル           |
| `CHANNEL_ID_CAPTURE`      | `screenshot_capture`     | 撮影完了通知チャネル           |
| `NOTIFICATION_ID_SERVICE` | 1001                     | サービス通知ID                 |
| `NOTIFICATION_ID_CAPTURE` | 1002                     | 撮影完了通知ID                 |

### MediaStoreSaver

| 名前                  | 値                           | 役割                           |
| --------------------- | ---------------------------- | ------------------------------ |
| `DIRECTORY_SCREENSHOTS` | `Pictures/Screenshots`     | 保存先ディレクトリ             |
| `MIME_TYPE_PNG`       | `image/png`                  | 保存形式                       |
| `DATE_FORMAT`         | `yyyy-MM-dd_HH-mm-ss_SSS`    | ファイル名日時フォーマット     |

### TempCache

| 名前          | 値        | 役割                                   |
| ------------- | --------- | -------------------------------------- |
| `TEMP_DIR`    | `temp`    | 一時ファイルディレクトリ（filesDir内） |
| `IMAGE_DIR`   | `images`  | キャッシュディレクトリ（cacheDir内）   |

## Settings データモデル

| プロパティ                | デフォルト値 | 役割                           |
| ------------------------- | ------------ | ------------------------------ |
| `immediateCapture`        | `true`       | 即時撮影                       |
| `delaySeconds`            | `3`          | 撮影遅延秒数                   |
| `rememberAction`          | `false`      | アクション記憶有効             |
| `rememberedAction`        | `null`       | 記憶されたアクション           |
| `autoClearClipboard`      | `false`      | クリップボード自動クリア       |
| `clearSeconds`            | `60`         | クリア遅延秒数                 |
| `persistentNotification`  | `true`       | 常駐通知表示                   |
| `disableOnLock`           | `true`       | ロック時撮影無効               |

## 列挙型

### CropView.TouchMode

| 名前      | 役割                           |
| --------- | ------------------------------ |
| `NONE`    | タッチなし                     |
| `DRAG`    | クロップ領域のドラッグ         |
| `RESIZE`  | クロップ領域のリサイズ         |

### CropView.Handle

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
| `FOREGROUND_SERVICE_MEDIA_PROJECTION`     | 34+     | MediaProjection前景サービス    |

### コンポーネント

#### Activity

| 名前                  | exported | launchMode        | 特記事項                       |
| --------------------- | -------- | ----------------- | ------------------------------ |
| `MainActivity`        | true     | singleTop         | ランチャーアクティビティ       |
| `CaptureActivity`     | false    | singleInstance    | 透明テーマ、excludeFromRecents |
| `EditorActivity`      | false    | singleTop         | 画面回転対応                   |
| `SettingsActivity`    | false    | standard          | 設定画面                       |

#### Service

| 名前                      | exported | foregroundServiceType | 特記事項                       |
| ------------------------- | -------- | --------------------- | ------------------------------ |
| `CaptureService`          | false    | mediaProjection       | 常駐前景サービス               |
| `ScreenshotTileService`   | true     | -                     | クイック設定タイル             |

#### Provider

| 名前              | exported | grantUriPermissions | 特記事項                       |
| ----------------- | -------- | ------------------- | ------------------------------ |
| `FileProvider`    | false    | true                | 安全なファイル共有             |

## セキュリティ対策

| 項目                         | 実装                                                              |
| ---------------------------- | ----------------------------------------------------------------- |
| INTERNET権限なし             | 完全オフライン動作、データ外部送信リスクゼロ                      |
| 最小権限原則                 | POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION のみ |
| コンポーネント非公開         | 内部Activity/Serviceは全て exported=false                         |
| FileProvider                 | ContentURI経由の安全なファイル共有                                |
| URI権限自動revoke            | 共有後30秒で自動revoke                                            |
| クリップボード自動クリア     | タイマー設定で自動削除                                            |
| バックアップ無効             | allowBackup=false                                                 |
| PendingIntent安全化          | FLAG_IMMUTABLE + FLAG_UPDATE_CURRENT                              |
| ProGuard/R8                  | リリースビルドでコード難読化・リソース縮約                        |
| デバッグログ除去             | リリースビルドからログ完全削除                                    |
| ロック時撮影禁止             | KeyguardManager判定で無効化                                       |
