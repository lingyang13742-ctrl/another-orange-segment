<div align="center">
  <img src="docs/icon.png" alt="橘瓣" width="120" />
  <h1>橘瓣</h1>
  <p><strong>基於 RikkaHub 的增強版 Android AI 聊天客戶端</strong></p>
  <p>在 RikkaHub 的基礎上，加入更多實用工具與個人化能力 🍊💬</p>

  <p>
    <a href="README_ZH_CN.md">简体中文</a> ·
    繁體中文 ·
    <a href="README.md">English</a>
  </p>
</div>

---

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="140" />
  <img src="docs/img/desktop.png" alt="模型選擇" width="440" />
  <img src="docs/img/assistants.png" alt="智能體" width="140" />
</div>

---

## 🍊 橘瓣獨有功能

> 以下為橘瓣在 RikkaHub 基礎上新增或增強的功能

### 🎨 個人化訂製
- **頭像框** — 為 AI 助手頭像添加裝飾邊框，打造獨特視覺風格
- **氣泡透明度** — 自由調節聊天气泡透明度，營造沉浸式對話體驗
- **思維鏈樣式** — 自訂思維鏈（Chain of Thought）的顯示樣式
- **輸入框換背景** — 自訂聊天輸入框背景，告別單調預設樣式
- **字體包匯入** — 匯入自訂字體包，讓對話界面更具個性

### 📍 位置與生活
- **取得定位** — AI 可取得使用者目前位置資訊，提供基於位置的服務
- **附近搜尋** — 基於高德地圖 API，搜尋附近的興趣點（餐飲、商店等）
- **App 使用情況與軌跡** — AI 可讀取應用使用統計和活動軌跡，了解你的數位生活習慣

### 📸 硬體互動
- **拍照** — AI 可調用裝置相機拍照，實現視覺互動

### ⌚ 健康資料
- **Gadgetbridge 同步** — 從 Gadgetbridge 讀取智慧手環/手錶的健康資料（步數、心率、睡眠等），讓 AI 了解你的身體狀況

### ☁️ 資料同步
- **Supabase 同步** — 基礎資料透過 Supabase 雲端同步，多裝置無縫切換

### 🔍 搜尋增強
- **Custom JS 搜尋** — 透過 QuickJS 引擎支援自訂 JavaScript 腳本編寫搜尋服務，靈活擴展搜尋能力

---

## ✨ 繼承自 RikkaHub 的功能

### 🎨 精緻體驗
- **Material You 設計** — 動態取色，跟隨系統主題
- **暗色模式** — 護眼夜間體驗
- **預測性返回** — Android 14+ 原生返回動畫

### 🔄 多供應商支援
- 相容 OpenAI / Google / Anthropic 等主流 API
- 自訂 API 位址、模型、請求頭與請求體
- 二維碼快速匯入/匯出供應商配置

### 🖼️ 多模態輸入
- 圖片辨識與理解
- 文件解析（PDF、DOCX、PPTX）
- OCR 圖片文字提取

### 🛠️ 擴展能力
- **MCP 協議支援** — 連接外部工具與服務
- **搜尋整合** — Exa / Tavily / 智譜 / LinkUp / Brave / Perplexity 等
- **Prompt 變數** — 模型名稱、時間等動態注入
- **訊息分支** — 對話分叉，探索不同回覆方向
- **智能體訂製** — 獨立系統提示統提示詞、參數與對話隔離
- **類 ChatGPT 記憶** — 跨對話上下文記憶
- **AI 翻譯** — 一鍵翻譯訊息內容
- **會話獨立系統提示詞** — 每個會話可單獨定義系統提示詞
- **Chatbox 聊天紀錄匯入** — 從 Chatbox 匯入歷史對話

### 📝 富文本渲染
- Markdown 完整支援
- 程式碼語法高亮
- LaTeX 數學公式
- Mermaid 流程圖
- 表格渲染

### 🖥️ 多端存取
- Android 原生客戶端
- 內建 Web 服務，支援瀏覽器存取

### 💌 角色匯入
- Silly Tavern 角色卡匯入

---

## 🚀 下載安裝

| 渠道 | 連結 |
|------|------|
| GitHub Releases | [sue1231513/orangechat/releases](https://github.com/sue1231513/orangechat/releases) |
| 源碼 | [github.com/sue1231513/orangechat](https://github.com/sue1231513/orangechat) |

---

## 🏗️ 專案架構

```
橘瓣/
├── app/          # 主應用模組（UI、ViewModel、核心邏邏輯）
├── ai/           # AI SDK 抽象層（OpenAI / Google / Anthropic）
├── common/       # 通用工具與擴展
├── document/     # 文件解析（PDF / DOCX / PPTX）
├── highlight/    # 程式碼語法高亮
├── search/       # 搜尋功能 SDK（Exa / Tavily / 智譜 / Custom JS）
├── speech/       # 語音辨識
├── tts/          # 文字轉語音
├── web/          # 內嵌 Web 伺服器（Ktor）
├── web-ui/       # Web 前端（React）
└── locale-tui/   # 國際化工具
```

---

## 🛠️ 開發構建

### 環境要求

- [Android Studio](https://developer.android.com/studio)
- JDK 17+
- Android SDK（compileSdk 37, minSdk 26）

### 構建命令

```bash
./gradlew assembleDebug                # 構建 Debug APK
./gradlew assembleRelease              # 構建 Release APK
./gradlew test                         # 執行單元測試
./gradlew connectedDebugAndroidTest    # 執行儀器測試
./gradlew lint                         # Android Lint 檢查
```

> [!TIP]
> 構建應用需要在 `app/` 目錄下放置 `google-services.json`（Firebase 設定檔）。

### 技術棧

| 技術 | 用途 |
|------|------|
| [Kotlin](https://kotlinlang.org/) | 開發語言 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI 框架 |
| [Material You](https://m3.material.io/) | 設計系統 |
| [Koin](https://insert-koin.io/) | 依賴注入 |
| [Room](https://developer.android.com/training/data-storage/room) | 本地資料庫 |
| [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) | 偏好儲存 |
| [OkHttp](https://square.github.io/okhttp/) | HTTP 客戶端 |
| [Ktor](https://ktor.io/) | 內嵌 Web 伺服器 |
| [Coil](https://coil-kt.github.io/coil/) | 圖片載入 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON 序列化 |
| [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation) | 頁面導航 |
| [Lucide Icons](https://composeicons.com/icon-libraries/lucide) | 圖標庫 |
| [QuickJS](https://github.com/nicholasgasior/quickjs-java) | 自訂 JS 搜尋引擎 |
| [Supabase](https://supabase.com/) | 雲端資料同步 |
| [高德地圖 SDK](https://lbs.amap.com/) | 定位與附近搜尋 |

---

## 🤝 貢獻指南

歡迎提交 Pull Request！

> [!IMPORTANT]
> 以下 PR 將被拒絕：
> 1. 翻譯相關變更（新增語言或更新翻譯會增加後續維護負擔）
> 2. 新增功能（本專案有自己的設計理念，不隨意接受功能 PR）
> 3. AI 生成的大規模重構與變更

---

## ⭐ Star History

喜歡這個專案？給個 Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=sue1231513/orangechat&type=Date)](https://star-history.com/#sue1231513/orangechat&Date)

---

## 📄 授權條款

本專案是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的衍生作品，遵循與上游一致的 **使用者分段雙重授權 (Segmented Dual Licensing)** 模式：

- **非商業 / 個人 / 教育 / 研究**，或 **使用者總數不超過 10 人** 的組織 —— 遵循 [GNU AGPL v3](https://www.gnu.org/licenses/agpl-3.0.html)（含原始碼公開義務）；
- 商業用途、使用者超過 10 人，或希望免除 AGPL v3 義務 —— 需購買商業授權（聯絡原作者 `re_dev@qq.com`）。

詳見 [LICENSE](LICENSE)。