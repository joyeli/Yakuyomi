# Yakuyomi — 授權說明 / License notice

本 repo 是 [mihon](https://github.com/mihonapp/mihon)（Apache-2.0）的 fork，加上 Yakuyomi 的裝置端 AI 翻譯整合層，並以 git submodule 內含 **Yakuyomi 翻譯引擎**（GPL-3.0，移植自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)）。

- **mihon 原始碼**：Apache-2.0 — 保留原 [`LICENSE`](LICENSE) 與其著作權 / attribution。
- **Yakuyomi 整合層 + 內含的翻譯引擎 + 組合後的 app 整體**：**GPL-3.0**（因含 GPL-3.0 引擎；Apache-2.0 與 GPL-3.0 相容 → 組合作品為 GPL-3.0）。
- **模型（BYOM，使用者自源頭取，本專案不散布權重）**：
  - comic-text-detector（[dmMaze](https://github.com/dmMaze/comic-text-detector)）— GPL-3.0（偵測模型）
  - 48px CTC OCR（[manga-image-translator](https://github.com/zyddnys/manga-image-translator)）— GPL-3.0
  - lama-manga（[Koharu](https://github.com/mayocream/koharu)）— GPL-3.0；底層 LaMa（[advimman/lama](https://github.com/advimman/lama)）— Apache-2.0
- **字型**：未 bundle（系統 CJK fallback）。

---

This repository is a fork of [mihon](https://github.com/mihonapp/mihon) (Apache-2.0) plus Yakuyomi's on-device AI-translation integration layer, bundling the **Yakuyomi translation engine** (GPL-3.0, ported from manga-image-translator) as a git submodule.

- **mihon's original code**: Apache-2.0 — its [`LICENSE`](LICENSE) and attribution are preserved.
- **Yakuyomi's integration layer + the bundled engine + the combined app as a whole**: **GPL-3.0** (it includes the GPL-3.0 engine; Apache-2.0 is GPL-3.0-compatible, so the combined work is GPL-3.0).
- **Model weights** are **not redistributed** here (bring-your-own); obtain them from the sources above under their respective licenses.
- **Fonts** are not bundled (system CJK fallback).

When distributing: keep this notice, mihon's `LICENSE` (Apache-2.0), and the engine's `LICENSE` (GPL-3.0) together.
