# Yakuyomi — 授權說明 / License notice

本 repo 是 [mihon](https://github.com/mihonapp/mihon)（Apache-2.0）的 fork，加上 Yakuyomi 的裝置端 AI 翻譯整合層，並以 git submodule 內含 **Yakuyomi 翻譯引擎**（GPL-3.0，移植自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)）。

- **mihon 原始碼**：Apache-2.0 — 保留原 [`LICENSE`](LICENSE) 與其著作權 / attribution。
- **Yakuyomi 整合層 + 內含的翻譯引擎 + 組合後的 app 整體**：**GPL-3.0**（因含 GPL-3.0 引擎；Apache-2.0 與 GPL-3.0 相容 → 組合作品為 GPL-3.0）。
- **模型權重（GPL-3.0）**：三顆全部來自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)，裝置端檔是我方轉／量化——DBNet 偵測（NCNN 轉換）、48px CTC OCR（int8 量化 ONNX export）、AOT-GAN 去字（NCNN 轉換）。可 BYOM 手動放，或透過 engine repo 的 release 一鍵自動下載（本專案散布這些 GPL-3.0 權重供下載）。
- **字型**：未 bundle（系統 CJK fallback）。

---

This repository is a fork of [mihon](https://github.com/mihonapp/mihon) (Apache-2.0) plus Yakuyomi's on-device AI-translation integration layer, bundling the **Yakuyomi translation engine** (GPL-3.0, ported from manga-image-translator) as a git submodule.

- **mihon's original code**: Apache-2.0 — its [`LICENSE`](LICENSE) and attribution are preserved.
- **Yakuyomi's integration layer + the bundled engine + the combined app as a whole**: **GPL-3.0** (it includes the GPL-3.0 engine; Apache-2.0 is GPL-3.0-compatible, so the combined work is GPL-3.0).
- **Model weights** (GPL-3.0): all three come from [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — DBNet detection (our NCNN conversion), 48px CTC OCR (our int8-quantized ONNX export), and AOT-GAN inpaint (our NCNN conversion). Bring your own, or one-tap auto-download from the engine repo's releases, which redistributes these GPL-3.0 weights.
- **Fonts** are not bundled (system CJK fallback).

When distributing: keep this notice, mihon's `LICENSE` (Apache-2.0), and the engine's `LICENSE` (GPL-3.0) together.
