# Security Report
updated: 2026-04-18

## 最新レビュー結果
- 日付: 2026-04-18
- レビュアー: クロ（t2-kuro）
- 判定: PASS（Phase 3 最終レビュー後）
- ASVS L1準拠率: 95%（Low 4件はドキュメント改善のみ、コード品質は100%相当）
- 内部スコア: 9/10
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

### 2026-04-18 Phase 2 初回レビュー（アーキテクチャ改善）
#### Critical
- なし

#### High
- H-1: `MutableSharedFlow<Unit>(replay=0)` を per-request ハンドシェイクに使用 → emit/collector順序の暗黙依存、低メモリ/Doze下のServiceタイミングで次回キャプチャに古いemit消費 + SecurityException リスク
  → **対応済み（2026-04-18）**: `CompletableDeferred<Unit>` per-request に置換、`awaitPrepareComplete()` + `cancelPrepare()` API 追加、Activity/Service双方のタイムアウト/onDestroy時に明示キャンセル
- H-2: `ScreenCaptureHelper` / `ProjectionController` が Activity Context 強参照、MediaProjection.Callback 匿名クラスでリーク経路
  → **対応済み（2026-04-18）**: `appContext = context.applicationContext` 正規化、Callback を `projectionCallback` named field 化、`stop()` 冒頭で `unregisterCallback` 実行
- H-3: `ScreenCaptureHelper.capture()` 多重呼び出しガード無し
  → **対応済み（2026-04-18）**: `AtomicBoolean` による CAS ガード（`isCapturing`/`isReleased`/`isStopped`）

#### Medium
- M-1: `Handler.postDelayed` キャンセル不能、Activity onDestroy 後に死んだ Context 参照
  → **対応済み（2026-04-18）**: `helperScope = CoroutineScope(Dispatchers.Main + SupervisorJob())` 追加、`delay(100ms)` に置換、`release()` で `helperScope.cancel()`、IO処理は `withContext(Dispatchers.IO)` 隔離
- M-3: `release()` / `stop()` 多重呼び出し時の ImageReader 例外リスク
  → **対応済み（2026-04-18）**: AtomicBoolean による冪等化（H-3と同時対応）
- M-4: `MediaProjection.Callback` unregister 未実装
  → **対応済み（2026-04-18）**: H-2と同時対応

#### Low
- なし

### 2026-04-18 Phase 2 再レビュー
- 判定: **PASS**（内部スコア 8.5/10、ASVS L1 100%）
- 前回指摘 High 3件・Medium 3件が全て適切に解消
- 残課題は Phase 3 送り（実害小・防御強化系）

### 2026-04-18 Phase 3 レビュー（品質改善）
#### Critical
- なし

#### High
- なし

#### Medium
- なし

#### Low（いずれもドキュメント改善、実害ゼロ、コミット・push可能判定）
- L-1: `CaptureService.awaitPrepareComplete()` の KDoc に「Main thread から呼ぶこと」の明記推奨（並走防御の現実用上は Main thread 前提で十分だが、将来の罠回避のため）
- L-2: `CaptureActivity.startScreenCapture()` の `CancellationException` catch コメント改訂（activityScope cancel と prepareDeferred cancel の区別明確化）
- L-3: `saveBitmapToTemp()` の `IOException` / `SecurityException` / `Exception` フォールバックで網羅性十分、追加分割不要
- L-4: `MediaProjection.Callback` unregister 順序（Phase 2 で実装済み）は安全

### 2026-04-18 Phase 3 最終判定
- 判定: **PASS**（内部スコア 9/10、ASVS L1 95%）
- Critical/High/Medium ゼロ
- Low 4件はドキュメント改善レベル、次回触る際の対応で可
- **コミット・push 可能状態に到達**

## 未対応（オプショナル改善、Phase 4以降の任意対応）

- L-1: `awaitPrepareComplete()` KDoc に Main thread 前提を明記（1行追加）
- L-2: `CancellationException` catch のコメント改訂（誤解回避）

## Phase 1〜3 通算成果

- **Phase 1**: パッケージ名 `com.example.screenshoteditor` → `dev.screenshoteditor`（Gem-Local-ini と同規約）
- **Phase 2**: CaptureActivity 責務分離（-47%）、static callback → `CompletableDeferred` per-request 置換、`ServiceLauncher` 抽出
- **Phase 3**: 並走防御、applicationContext 正規化、Bitmap recycle、例外粒度化、KDoc 契約明記

全フェーズ通算で、スクリーンショット系 Android アプリのセキュリティ要件を以下レベルで満たす：
- MediaProjection consent token の即時null化
- Activity Leak 防止の徹底（applicationContext 統一 + Callback unregister）
- Bitmap ライフサイクル管理（参照比較による二重recycle防止）
- Foreground Service 昇格の同期処理（per-request CompletableDeferred + タイムアウト + 明示キャンセル）
- 例外の粒度化と網羅フォールバック
- リソース解放の冪等化（AtomicBoolean CAS）

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
