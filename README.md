# ScreenshotEditor

iOS風のスクリーンショット撮影・編集機能をAndroidネイティブUIで実装したアプリケーションです。

## 概要

iPhoneの「撮影→編集→保存/コピー/破棄」のワークフローをAndroidで再現しました。
撮影から編集、保存まで一連の流れをシームレスに処理し、ユーザーの利便性を重視した設計になっています。

技術詳細は [GLOSSARY.md](./GLOSSARY.md) を参照してください。

## 主な機能

- **即座に撮影**: 通知、クイック設定タイルから瞬時にスクリーンショット撮影
- **編集機能**: 自由な範囲選択が可能なクロップツール（アスペクト比固定対応）
- **柔軟な保存オプション**: ギャラリー保存、クリップボードコピー、他アプリへの共有
- **プライバシー重視**: インターネット権限不要、全処理をローカルで完結

## 技術スタック

| カテゴリ | 技術 |
|----------|------|
| 言語 | Kotlin 100% |
| UI | Android View System + ViewBinding |
| 画面キャプチャ | MediaProjection API |
| 画像処理 | Bitmap API, Canvas |
| ストレージ | MediaStore API (Scoped Storage対応) |
| 設定管理 | DataStore Preferences |
| 非同期処理 | Kotlin Coroutines |
| minSdk | 30 (Android 11) |
| targetSdk | 36 (Android 16) |

## セキュリティ対策

本プロジェクトはセキュリティレビューを経て以下の対策を実装しています：

### 権限管理
- **INTERNET権限なし**: 完全オフライン動作、データ外部送信リスクゼロ
- **最小権限原則**: 必要最小限の権限のみ要求（POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_MEDIA_PROJECTION）
- **exported属性**: 内部コンポーネントはexported=false。例外は MainActivity・CaptureActivity（どちらもランチャー起点）と TileService（`BIND_QUICK_SETTINGS_TILE` 権限で保護）

### データ保護
- **FileProvider**: 安全なコンテンツURI共有（公開範囲は `cacheDir/images/` のみ）
- **URI権限管理**: 共有は `FLAG_GRANT_READ_URI_PERMISSION` で受け取り側にだけ一時付与（アプリ側からの時間指定revokeはしない）。共有用ファイルは24時間で削除
- **クリップボード自動クリア**: 既定は「しない」（15/30/60秒から選択可）。有効時は指定秒数後に `clearPrimaryClip()` し、クリップボード用ファイルも削除。クリア時点でクリップボードの中身が自アプリのものか確認できない（Android 10以降はバックグラウンドで読めない）ため、その間に他アプリでコピーした内容も消える
- **バックアップ無効**: allowBackup=false設定

### コード保護
- **ProGuard/R8**: リリースビルドでコード難読化・リソース縮約有効
- **ログ削除**: リリースビルドでは R8（`-assumenosideeffects`）で `Log.v/d/i/w` の呼び出しを除去（`Log.e` は残す）

## 動作環境

- Android 11（API Level 30）以上
- MediaProjection対応端末

## プロジェクト構成

```
app/
├── capture/          # スクリーンショット撮影
│   ├── CaptureActivity.kt
│   ├── CaptureService.kt
│   ├── ProjectionController.kt
│   ├── ScreenCaptureHelper.kt
│   ├── ServiceLauncher.kt
│   └── CaptureGeometry.kt    # 撮影サイズ・ステータスバー高さ・切り出し範囲
├── ui/               # UI関連
│   ├── EditorActivity.kt
│   ├── CropView.kt       # カスタムビュー実装
│   ├── CropGeometry.kt   # クロップ枠の計算（純粋関数、ユニットテスト対象）
│   └── SettingsActivity.kt
├── data/             # データ処理
│   ├── MediaStoreSaver.kt
│   ├── ClipboardShare.kt
│   ├── ClipboardAutoClear.kt
│   ├── TempCache.kt
│   └── SettingsDataStore.kt
└── tile/             # クイック設定タイル
    └── ScreenshotTileService.kt
```

## 実装の工夫点

### 1. メモリ効率
大きな画像でもOOMを回避するため、ストリーム処理とBitmapのリサイクルを徹底

### 2. ユーザビリティ
- 撮影タイミングの設定（即時/遅延）。遅延中は透明な撮影画面がタッチを下のアプリへ通す
- クリップボード自動クリアのタイマー設定

### 3. エラーハンドリング
- 撮影ごとにMediaProjectionの同意を取得（同意トークンは使い捨て）
- 最初のフレームが2秒以内に届かない場合（FLAG_SECURE画面など）は失敗として通知
- リソースリークを防ぐ確実な解放処理

## ビルド方法

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド（要署名設定）
./gradlew assembleRelease

# ユニットテスト（クロップ計算・撮影範囲・設定値の検証）
./gradlew testDebugUnitTest
```

## テスト

### テスト環境
- **実機テスト済み端末**
  - TORQUE G06（Android 13）
  - 各種画面密度・アスペクト比で動作確認済み

### テスト項目
- 画面回転時の動作確認
- 3ボタン/ジェスチャーナビゲーション両対応
- 各種画面密度での表示確認
- メモリリーク検証（LeakCanary使用）
- 異なるアスペクト比での表示・撮影確認
- ロック画面での撮影無効化動作
- クリップボード自動クリア動作

## License

This project is for portfolio purposes. All rights reserved.
