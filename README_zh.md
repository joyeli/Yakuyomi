<div align="center">

<img src="./.github/assets/yakuyomi-logo.png" alt="Yakuyomi" width="80"/>

# Yakuyomi

**裝置端 AI 翻譯的漫畫閱讀器。**

[English](README.md) ｜ 中文

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-0877d2?labelColor=27303D)](LICENSE-YAKUYOMI.md)
[![Release](https://img.shields.io/github/v/release/joyeli/Yakuyomi?include_prereleases&label=%E4%B8%8B%E8%BC%89&labelColor=27303D)](https://github.com/joyeli/Yakuyomi/releases/latest)

</div>

Yakuyomi 是 [mihon](https://github.com/mihonapp/mihon) 的 fork，邊下載 / 邊讀邊把漫畫翻譯掉——預設日翻繁中，語言對可任意設定。文字的**偵測、OCR、去字都在裝置上跑**（ONNX Runtime），只有**翻譯**這步呼叫雲端 LLM。翻譯引擎是另一個 repo [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine)，在這裡以 submodule 引入。

<div align="center">
<img src="./.github/assets/showcase.png" alt="Box-fill vs Yakuyomi 去字" width="100%"/>
<br>
<sub><b>字壓在畫面上（頭髮、臉、背景）——正是疊字 / box-fill 翻譯的死穴。</b>Yakuyomi 把原文擦掉、重建畫面（3），再把譯文嵌回去（4）。</sub>
</div>

## 下載

到 [**Releases 頁面**](https://github.com/joyeli/Yakuyomi/releases/latest) 抓最新的**簽章 APK**。多數手機用 `arm64-v8a`，不確定就用 `universal`。不用自己 build，想 build 才[從原始碼建置](#編譯)。

## 翻譯功能

- **下載時翻譯** — 章節一下載完就翻；只有翻成功才覆蓋該頁，絕不用更糟的東西蓋掉原圖。
- **即時翻譯** — 打開未翻的章，邊讀邊逐頁翻、翻好就換上。
- **自備服務商與金鑰** — 預設 DeepSeek；任何 OpenAI 相容服務商都行（OpenAI、Gemini、Groq、Qwen、OpenRouter、自架 Sakura、自訂）。金鑰每家一格、加密儲存，模型清單即時撈。見引擎的[服務商說明](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/PROVIDERS_zh.md)。
- **自動下載或自備模型** — 三顆 ONNX 模型可一鍵下載（含 sha256 驗證），或自己手動放。見[模型說明](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/MODELS_zh.md)。
- **品質旋鈕** — 三種去字模式（平塗 / 整頁 LaMa / 逐區 LaMa）、直 / 橫排版、約 20 個可調參數。
- 其餘全是 mihon——書庫、來源 / 擴充、下載、追蹤、備份、閱讀器。

## 翻譯怎麼運作

```
頁 → 偵測 (ONNX) → OCR (ONNX) → 分組 → 翻譯 (LLM) → 去字 (ONNX) → 排版 → 翻好的頁
```

五階段裡四個在裝置上跑，只有翻譯離開裝置。pipeline 與設計都在 [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine)。

## 編譯

引擎是 git submodule，所以要遞迴 clone：

```sh
git clone --recurse-submodules https://github.com/joyeli/Yakuyomi.git
cd Yakuyomi
./gradlew :app:assembleDebug
```

引擎透過 Gradle composite build（`includeBuild`）接進來。編譯不需要模型權重或 API 金鑰——模型在 app 內下載、LLM 金鑰在 app 內輸入。

## 與 mihon 的關係

Yakuyomi 是真正的 mihon fork：跟著 mihon 的閱讀器走，只加整合層——下載 / 翻譯 hook、翻譯設定、模型管理、品牌。裝置端 ML 隔離在引擎 submodule 裡，所以閱讀器維持 mihon 的樣子、引擎也能自己單獨測。

## 免責聲明

本應用程式的開發者與內容提供方無任何關聯，且本應用程式不託管任何內容。

## 授權

**GPL-3.0** — 見 [LICENSE-YAKUYOMI.md](LICENSE-YAKUYOMI.md)。Yakuyomi 把 mihon（Apache-2.0，見 [LICENSE](LICENSE)）與翻譯引擎結合；引擎移植了 manga-image-translator 的 prompt / 參數 / 分組、並用 GPL-3.0 模型，故組合後的 app 為 GPL-3.0。mihon 的 Apache-2.0 授權與歸屬予以保留。

## 致謝

- [mihon](https://github.com/mihonapp/mihon) — 本專案 fork 的閱讀器（Apache-2.0）
- [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) — 裝置端翻譯引擎
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — prompt 與行為參考
- 模型作者 — [comic-text-detector](https://github.com/dmMaze/comic-text-detector)、[Koharu](https://github.com/mayocream/koharu)、[LaMa](https://github.com/advimman/lama)
