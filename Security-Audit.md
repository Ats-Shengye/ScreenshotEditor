# Security Report
updated: 2026-04-18

## 最新レビュー結果
- 日付: 2026-04-18
- レビュアー: クロ（t2-kuro）
- 判定: PASS
- ASVS L1準拠率: 92%（再レビュー後は100%相当、指摘全解消）
- ビルド検証: `./gradlew assembleDebug` PASS / `./gradlew assembleRelease` PASS（R8 `minifyReleaseWithR8` 通過）

## 指摘履歴

### 2026-04-18 Phase 1 初回レビュー（パッケージ名変更）
#### Critical
- `app/proguard-rules.pro:9` に旧パッケージ名 `com.example.screenshoteditor.data.SettingsDataStore$**` が残存 → R8難読化でDataStore preferences keysが保護対象外になるリスク
  → **対応済み（2026-04-18）**: `dev.screenshoteditor.data.SettingsDataStore$**` に修正、リリースビルドで正常動作確認

#### High
- なし

#### Medium
- `GLOSSARY.md:108-109` に旧パッケージ名参照（`ACTION_CAPTURE`/`ACTION_STOP` の完全修飾名） → ドキュメント乖離
  → **対応済み（2026-04-18）**: `dev.screenshoteditor.*` に更新

#### Low
- なし

### 2026-04-18 Phase 1 再レビュー
- 判定: **PASS**（内部スコア 9/10）
- 全指摘事項完全解消を確認

## 適用済みセキュリティ対策

### Android プラットフォーム
- **INTERNET権限なし**: 完全オフライン動作、データ外部送信リスクゼロ
- **最小権限原則**: POST_NOTIFICATIONS / FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PROJECTION のみ
- **exported=false**: 内部コンポーネント全て非公開（TileServiceのみ例外）
- **allowBackup=false**: バックアップ無効化
- **PendingIntent**: `FLAG_IMMUTABLE` 必須化

### データ保護
- **FileProvider**: `${applicationId}.fileprovider` の動的authority、安全なコンテンツURI共有
- **URI権限管理**: `FLAG_GRANT_READ_URI_PERMISSION` 付与、共有後30秒で自動revoke
- **クリップボード自動クリア**: タイマー設定で削除、即時クリアボタンも提供
- **MediaStore登録**: `IS_PENDING=1` 初期化→完了後 `IS_PENDING=0` のトランザクショナル保存

### コード保護
- **ProGuard/R8**: リリースビルドで `isMinifyEnabled = true` + `isShrinkResources = true`
- **DataStore保護**: `-keep class dev.screenshoteditor.data.SettingsDataStore$**` で難読化除外
- **ログ削除**: リリースビルドからデバッグログ完全除去

### Intent / IPC
- **Intent Action ハードコード**: `dev.screenshoteditor.ACTION_CAPTURE` / `ACTION_STOP` で明示的
- **Broadcast動的登録**: Service内のみ、exported=false
- **BroadcastReceiver intent-filter**: 使用なし（動的登録のみ）

## Conditional Application 判定

| セキュリティ対策 | 適用 | 理由 |
|---|---|---|
| SQLインジェクション防止 | N/A | DB未使用（DataStore Preferences） |
| コマンドインジェクション防止 | N/A | subprocess/shell未使用 |
| パストラバーサル防止 | 適用 | MediaStore API経由で制御、`createTempFile`は`File(context.cacheDir, ...)` |
| ネットワークセキュリティ/TLS | N/A | INTERNET権限なし |
| 認証/認可 | N/A | シングルユーザー端末アプリ |
| セッション管理 | N/A | ステートレス |
| CSRF保護 | N/A | Webアプリでない |
| レート制限 | N/A | パブリックAPI公開なし |
| SSRF防止 | N/A | 外部URL処理なし |
| セキュリティヘッダ | N/A | Webアプリでない |

## Phase 1 変更統計

- 変更ファイル: 17個（15 Kotlin + build.gradle.kts + activity_editor.xml + proguard-rules.pro + GLOSSARY.md）
- パッケージ名: `com.example.screenshoteditor` → `dev.screenshoteditor`
- ディレクトリリネーム: `app/src/main/java/com/example/screenshoteditor/` → `app/src/main/java/dev/screenshoteditor/`（git mv で履歴保持）
- ビルド成功: debug / release 両方

## 次フェーズ

### Phase 2（予定）
- `CaptureActivity` 責務分離（`ScreenCaptureHelper` 抽出）
- `CaptureService.onPrepareComplete` static callback → Flow/Channel置換
- `ServiceLauncher` ヘルパー抽出（`startForegroundService`/`startService` 切り分けの重複解消）

### Phase 3（予定）
- Bitmap `recycle()` 追加
- `Exception` catch → 具体例外粒度化
- `Handler(Looper.getMainLooper())` 重複削除
