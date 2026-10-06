[简体中文](README.md) · [繁體中文](README.zh-TW.md) · [English](README.en.md)

# Crid Next

Crid Next 是一款 Android 課表應用程式，可以查看今天的安排、管理多個學期與課表方案，並在上課前提醒你。

**Android 12 及以上 · 简体中文 / 繁體中文 / English · 首個公開版本 2.0.0**

[GitHub 下載](https://github.com/Gliese-876/Crid-Next/releases/latest) · [Gitee 下載](https://gitee.com/gliese-876/crid-next/releases) · [GitHub 原始碼](https://github.com/Gliese-876/Crid-Next) · [Gitee 原始碼](https://gitee.com/gliese-876/crid-next) · [更新紀錄](CHANGELOG.md)

## 安裝

在 [GitHub 發行頁](https://github.com/Gliese-876/Crid-Next/releases/latest)或 [Gitee 發行頁](https://gitee.com/gliese-876/crid-next/releases)下載 `.apk` 檔案，開啟後依系統提示安裝。首次透過瀏覽器或檔案管理員安裝時，需要允許該應用程式安裝未知來源的應用程式。APK 支援 ARM64、ARM32、x86 和 x86_64 裝置。

## 你可以用它做什麼

- **看今天，也看整週。** 今天頁顯示當天行程和下一節課，週課表方便查看整週安排。
- **管理不同課表。** 為不同學期儲存多個方案，手動新增課程，或編輯課程的教師、地點和授課時間。
- **匯入後先核對。** 從教務系統或本機檔案匯入，檢查課程、缺項和時間衝突，再新增、合併或取代方案。
- **在桌面查看課程。** 新增今日或週課表小工具，也可開啟上課前提醒。
- **分享與備份。** 按日、週、月或學期匯出 PNG / PDF，或匯出能重新匯入的 JSON 課表檔案。
- **依你的習慣使用。** 支援淺色與深色主題、三種介面語言、手機和平板版面，以及節假日和調休設定。

## 第一次使用

1. 開啟「方案」，新增學期，確認學期起迄日期、週數和每日作息。按節次匯入的課程會使用這裡的上課時間。
2. 點選「匯入課表」。可以選擇已有檔案，也可以進入北京師範大學北京或珠海校區教務系統，登入並下載課表。內建解析支援這些教務系統的列表與網格課表匯出，以及 Crid Next JSON 檔案。
3. 在預覽中核對課程和目標學期，選擇「新增方案」並確認匯入。已有方案也可以合併或取代；想手動輸入時，先新增空白方案，再新增課程。
4. 回到「今天」或「課表」查看安排。點選課程可編輯；在「更多 → 管理課程」中可以找到全部課程，包括待排課程。在「方案」中切換目前課表。

珠海教務系統的學期選項若沒有載入，可以反覆切換「按列表显示」「按课表显示」，再選擇學期匯出。下載無法直接完成時，先用系統瀏覽器儲存檔案，再從本機匯入。

## 讓 AI 幫你轉換其他課表

內建解析暫不支援你的檔案時，可以請能讀取該檔案的 AI 將它轉成 Crid Next JSON。試算表、PDF 和圖片是否可讀，取決於你選擇的 AI。

1. 下載儲存庫中的 [JSON 課表示例](examples/timetable.json)。示例中的課程、教師和地點均為虛構。
2. 將**下方提示詞、你的課表原始檔案和 JSON 示例**一起傳送給 AI。傳送前可遮去姓名、學號等與排課無關的資訊。
3. 回答 AI 對缺失資訊的提問，儲存它產生的 `timetable.json`，然後在 Crid Next 中選擇「匯入課表 → 從本機檔案匯入」。核對週次、星期、時間、教師和地點後再儲存。

<details>
<summary>展開並複製 AI 轉換提示詞</summary>

```text
請把我提供的課表原始檔案轉換成可匯入 Crid Next 的 JSON。另附的 timetable.json 僅用於說明格式，不要把示例課程混入我的課表。

先讀取原始檔案，保留每門課程的全部授課安排、單雙週、分段週次，以及每次安排對應的教師、地點和備註。遇到無法辨認、含義不明或缺少的必要排課資訊，先向我提問，不要猜測或默默捨棄課程。原文未提供教師、地點、學分時使用空字串。

嚴格使用以下結構與規則：
1. 最外層只有 format、version、name、courses：format 為 "crid-next"，version 為數字 1，name 為非空方案名稱，courses 為非空課程陣列。應用程式版本 2.0.0 不改變這個檔案版本號。
2. 每門課程使用 name、credits、extra、lessons。name 為非空課程名；credits 為字串；extra 為鍵和值均是字串的物件，可儲存課程代碼等附加資訊；lessons 為非空授課安排陣列。省略 id 和 color，讓應用程式產生識別碼並選擇顏色。教師和地點屬於每條 lesson，不能放在課程最外層。
3. 每條 lesson 只使用 weeks、weekday、startPeriod、endPeriod、date、startTime、endTime、location、teacher、note、unscheduled。location、teacher、note 為字串，unscheduled 為布林值。
4. 按週重複時，weeks 是實際授課週次的正整數陣列，展開區間和單雙週，去除重複並遞增排列；date 為 null。按特定日期上課時，date 使用 "YYYY-MM-DD"，weeks 使用 []。weekday 為星期一=1 至星期日=7，並與特定日期一致，不受介面一週起始日設定影響。必要時先詢問學期與週次的對應關係。
5. 已排課的 unscheduled 為 false。時間表達選擇一種：按節次時，startPeriod 和 endPeriod 為正整數且結束節次不小於開始節次，startTime/endTime 為 null；按時刻時，startTime/endTime 為 24 小時制 "HH:mm" 且開始早於結束，startPeriod/endPeriod 為 null。不連續的節次或不同的時間、教師、地點分為不同安排，不要填滿中間的空檔。
6. 只有原始檔案明確標註待排時間且給出授課週次時，才使用 unscheduled:true、weekday:0，並保留 weeks；date、startPeriod、endPeriod、startTime、endTime 全部為 null。不要用待排標記掩蓋辨識失敗或缺失資訊。
7. 不新增學期物件、應用程式設定、帳號、學號、密碼、金鑰或其他未列出的欄位；extra 也不儲存個人身分或憑證。每個方案最多 500 門課程、3000 條授課安排，每條安排最多 366 個週次。

輸出前逐項核對原始檔案，確保課程及授課安排沒有遺漏、週次沒有擴大、教師與地點沒有錯配。所有必要資訊明確後，只輸出 UTF-8 編碼的 timetable.json 檔案內容或提供該檔案，不加 Markdown 程式碼圍欄或說明文字。
```

</details>

JSON 檔案只儲存一個課表方案及其課程。學期日期、作息和個人設定需要在應用程式中設定；若要試用示例，請建立至少 16 週、包含第 1–4 節作息的學期。

## 提醒與資料

在「設定」中開啟上課前提醒，並在「提醒設定」中檢查通知、準時提醒和背景權限；需要時開啟「鬧鐘級提醒」。部分中國大陸品牌手機還需要允許自動啟動、背景執行並解除電池限制。提醒送達受系統和廠商背景策略影響，強制停止應用程式也會影響提醒；更換手機或變更系統設定後，建議用一節測試課程確認。[提醒設定與送達說明](docs/提醒送达设计.md)

課表儲存在裝置本機，檔案匯入在本機完成。使用上述 AI 轉換時，檔案會交給你選擇的 AI 服務。解除安裝前請匯出 JSON 備份；PNG / PDF 用於查看和分享，還原課程請使用 JSON。

## 與 Crid 的關係

Crid Next 延續了 [Crid](https://github.com/Gliese-876/Crid) 的課表應用方向，由同一作者使用 Kotlin、Jetpack Compose 和 Material 3 從零重寫為原生 Android 應用程式。原 Crid 基於 Flutter，保留 Apache-2.0 授權並已封存，供查閱歷史實作；後續開發在 Crid Next 繼續。

兩個專案使用獨立的資料格式。從原 Crid 轉用 Crid Next 時，請重新匯入教務課表，或依上述方法轉換為 Crid Next JSON，並重新設定學期和作息。

這也是一次使用前沿模型進行 **vibe coding** 的實踐：透過自然語言協作推進需求、實作、介面迭代和測試，並將程式碼公開，供大家使用、檢查和改進。

## 回饋與貢獻

歡迎在 [GitHub Issues](https://github.com/Gliese-876/Crid-Next/issues) 回報問題或提出建議。匯入問題請附匿名課表、預期結果和應用程式版本；介面問題可附裝置型號及螢幕截圖。提交檔案前請移除真實姓名、學號和隱藏中繼資料。[公開測試樣例說明](tests/README.md)介紹了現有樣例和檢查方式。

感謝 [方緣（Fangyuanz06）](https://github.com/Fangyuanz06) 協助測試和改進，感謝 [ChiHuchen](https://github.com/ChiHuchen) 提供北京校區課表資料，也感謝參與回饋與貢獻的每個人。

## 開發者：建置與測試

準備 JDK 21、Android SDK 37 和 Build Tools 37，用 Android Studio 開啟儲存庫根目錄。首次建置需要下載 Gradle 和相依套件。Windows PowerShell 可執行：

```powershell
./tools/build.ps1 -Task ':core:test', ':app:testDebugUnitTest', ':app:assembleDebug'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

其他系統可使用 Gradle Wrapper：

```sh
sh ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

- `core/`：課表模型、匯入解析、資料校驗與行程計算。
- `app/`：Android 介面、儲存、提醒、小工具與匯出。
- `examples/timetable.json`：可匯入的虛構課表示例。
- `tests/`：公開匿名樣例及逐欄位解析預期；`tests/private/` 被 Git 忽略。
- `docs/`：設計與驗證紀錄。

正式建置與簽署見[發行流程](docs/发布流程.md)，實作說明見[技術方案](docs/技术方案.md)和[解析驗證](docs/解析验证.md)。JSON 格式以 [PlanCodec](core/src/main/kotlin/cn/crid/next/core/PlanCodec.kt)、[資料模型](core/src/main/kotlin/cn/crid/next/core/Models.kt)及[校驗規則](core/src/main/kotlin/cn/crid/next/core/DataValidator.kt)為準。

## 授權

Crid Next 的原創程式碼以 [MIT License](LICENSE) 開源。第三方元件保留各自授權，詳見[第三方開源聲明](app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt)；其中 JExcelAPI 使用 LGPL-2.1-or-later，其對應原始碼和替換建置說明保存在 [third_party/](third_party/README.md)。相關授權條款也可以在應用程式的「設定 → 關於」中離線閱讀。
