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
| targetSdk | 35 (Android 15) |

## セキュリティ対策

本プロジェクトはセキュリティレビューを経て以下の対策を実装しています：

### 権限管理
- **INTERNET権限なし**: 完全オフライン動作、データ外部送信リスクゼロ
- **最小権限原則**: 必要最小限の権限のみ要求（POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION）
- **exported属性**: 内部コンポーネントは全てexported=false（TileServiceを除く）

### データ保護
- **FileProvider**: 安全なコンテンツURI共有
- **URI権限管理**: 共有後30秒で自動revoke
- **クリップボード自動クリア**: タイマー設定でクリップボードデータを自動削除
- **バックアップ無効**: allowBackup=false設定

### コード保護
- **ProGuard/R8**: リリースビルドでコード難読化・リソース縮約有効
- **ログ削除**: リリースビルドからデバッグログを完全除去

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
│   └── StatusBarInsets.kt
├── ui/               # UI関連
│   ├── EditorActivity.kt
│   ├── CropView.kt       # カスタムビュー実装
│   └── SettingsActivity.kt
├── data/             # データ処理
│   ├── MediaStoreSaver.kt
│   ├── ClipboardShare.kt
│   ├── TempCache.kt
│   └── SettingsDataStore.kt
├── notif/            # 通知管理
│   ├── NotificationHelper.kt
│   └── PersistentNotifier.kt
└── tile/             # クイック設定タイル
    └── ScreenshotTileService.kt
```

## 実装の工夫点

### 1. メモリ効率
大きな画像でもOOMを回避するため、ストリーム処理とBitmapのリサイクルを徹底

### 2. ユーザビリティ
- 撮影タイミングの設定（即時/遅延）
- 前回の保存アクションを記憶
- クリップボード自動クリアのタイマー設定

### 3. エラーハンドリング
- MediaProjection権限失効時の再取得フロー
- FLAG_SECURE画面の適切な処理
- リソースリークを防ぐ確実な解放処理

## ビルド方法

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド（要署名設定）
./gradlew assembleRelease
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
- URI権限の自動revoke確認

## License

This project is for portfolio purposes. All rights reserved.
