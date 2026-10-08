# Yakuyomi — 授權說明 / License notice

本 repo 是 [mihon](https://github.com/mihonapp/mihon)（Apache-2.0）的 fork，加上 Yakuyomi 的裝置端 AI 翻譯整合層，並以 git submodule 內含 **Yakuyomi 翻譯引擎**（GPL-3.0，移植自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)）。

- **mihon 原始碼**：Apache-2.0 — 保留原 [`LICENSE`](LICENSE) 與其著作權 / attribution。
- **Yakuyomi 整合層 + 內含的翻譯引擎 + 組合後的 app 整體**：**GPL-3.0**（因含 GPL-3.0 引擎；Apache-2.0 與 GPL-3.0 相容 → 組合作品為 GPL-3.0）。
- **模型權重（GPL-3.0）**：三顆全部來自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)，裝置端檔是我方轉／量化——DBNet 偵測（NCNN 轉換）、48px CTC OCR（NCNN 轉換，混合精度）、AOT-GAN 去字（NCNN 轉換）。可 BYOM 手動放，或透過 engine repo 的 release 一鍵自動下載（本專案散布這些 GPL-3.0 權重供下載）。
- **夜讀用人物分割模型權重（非 GPL-3.0；選配、預設關）**：兩顆皆非 manga-image-translator 的，裝置端檔是我方 NCNN 轉檔；透過 engine repo 的 `models-v5` release 散布，**僅供研究／非商業用途**、附下列出處歸屬，權利人提出要求即下架；使用者亦可自行從出處取得權重（BYOM）。
  - **YOLO11-seg**（`manga_seg_s`）：權重來自 Hugging Face [anonimkaq4/manga-page-element-segmentation](https://huggingface.co/anonimkaq4/manga-page-element-segmentation)（模型卡 `license: other`；[Ultralytics](https://github.com/ultralytics/ultralytics) YOLO11 為 AGPL-3.0）。以 MangaSeg／Manga109-s 標註訓練；模型卡要求再散布或商用前自行確認 MangaSeg、Manga109-s 與 Ultralytics 的授權、標註「Copyrighted by Minshan Xie」、並引用 MangaSeg 論文（CVPR 2025）。
  - **CartoonSegmentation**（`cartoonseg`，RTMDet-Ins）：權重來自 Hugging Face [Jakaline/CartoonSegmentationOnnx](https://huggingface.co/Jakaline/CartoonSegmentationOnnx)；上游 [CartoonSegmentation/CartoonSegmentation](https://github.com/CartoonSegmentation/CartoonSegmentation) 無 LICENSE 檔、README 亦未寫授權；原始 PyTorch 權重的 Hugging Face 模型卡（[dreMaz/AnimeInstanceSegmentation](https://huggingface.co/dreMaz/AnimeInstanceSegmentation)）寫 MIT，我們轉檔所用的 ONNX 版沒有模型卡；訓練資料含 Manga109。
- **字型**：未 bundle（系統 CJK fallback）。

---

This repository is a fork of [mihon](https://github.com/mihonapp/mihon) (Apache-2.0) plus Yakuyomi's on-device AI-translation integration layer, bundling the **Yakuyomi translation engine** (GPL-3.0, ported from manga-image-translator) as a git submodule.

- **mihon's original code**: Apache-2.0 — its [`LICENSE`](LICENSE) and attribution are preserved.
- **Yakuyomi's integration layer + the bundled engine + the combined app as a whole**: **GPL-3.0** (it includes the GPL-3.0 engine; Apache-2.0 is GPL-3.0-compatible, so the combined work is GPL-3.0).
- **Model weights** (GPL-3.0): all three come from [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — DBNet detection (our NCNN conversion), 48px CTC OCR (our NCNN conversion, mixed precision), and AOT-GAN inpaint (our NCNN conversion). Bring your own, or one-tap auto-download from the engine repo's releases, which redistributes these GPL-3.0 weights.
- **Character-segmentation weights for night reading** (not GPL-3.0; optional, off by default): neither comes from manga-image-translator; the on-device files are our NCNN conversions, redistributed from the engine repo's `models-v5` release **for research / non-commercial use only**, with the attribution below, and taken down on a rights holder's request. You can also obtain the weights from the sources yourself (bring your own model).
  - **YOLO11-seg** (`manga_seg_s`): weights from Hugging Face [anonimkaq4/manga-page-element-segmentation](https://huggingface.co/anonimkaq4/manga-page-element-segmentation) (model card `license: other`; [Ultralytics](https://github.com/ultralytics/ultralytics) YOLO11 is AGPL-3.0). Trained on MangaSeg / Manga109-s annotations; the model card asks anyone redistributing or using it commercially to verify the MangaSeg, Manga109-s and Ultralytics licenses themselves, to credit it as "Copyrighted by Minshan Xie", and to cite the MangaSeg paper (CVPR 2025).
  - **CartoonSegmentation** (`cartoonseg`, RTMDet-Ins): weights from Hugging Face [Jakaline/CartoonSegmentationOnnx](https://huggingface.co/Jakaline/CartoonSegmentationOnnx); the upstream [CartoonSegmentation/CartoonSegmentation](https://github.com/CartoonSegmentation/CartoonSegmentation) repository has no LICENSE file and its README states no license; the Hugging Face model card of the original PyTorch weights ([dreMaz/AnimeInstanceSegmentation](https://huggingface.co/dreMaz/AnimeInstanceSegmentation)) says MIT, while the ONNX version we converted from has no model card; training data includes Manga109.
- **Fonts** are not bundled (system CJK fallback).

When distributing: keep this notice, mihon's `LICENSE` (Apache-2.0), and the engine's `LICENSE` (GPL-3.0) together.
