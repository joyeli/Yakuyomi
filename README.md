<div align="center">

<img src="./.github/assets/yakuyomi-logo.png" alt="Yakuyomi" width="80"/>

# Yakuyomi

**A manga reader with on-device AI translation.**

English ｜ [中文](README_zh.md)

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-0877d2?labelColor=27303D)](LICENSE-YAKUYOMI.md)
[![Release](https://img.shields.io/github/v/release/joyeli/Yakuyomi?include_prereleases&label=download&labelColor=27303D)](https://github.com/joyeli/Yakuyomi/releases/latest)

</div>

Yakuyomi is a fork of [mihon](https://github.com/mihonapp/mihon) that translates manga as you download or read it — Japanese to Traditional Chinese by default, any language pair configurable. Text **detection, OCR, and removal run on the device** (ONNX Runtime); only the **translation** step calls a cloud LLM. The translation engine is a separate repo, [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine), pulled in here as a submodule.

<div align="center">
<img src="./.github/assets/showcase.png" alt="Box-fill vs Yakuyomi inpainting" width="100%"/>
<br>
<sub><b>Text over art — hair, faces, backgrounds — is where box-fill / overlay translators fall apart.</b> Yakuyomi erases the original and reconstructs the artwork (3), then typesets the translation back in (4).</sub>
</div>

## What makes Yakuyomi different

Everything below is on top of stock mihon — at a glance, what you get here that other forks don't:

**Translation**
- **Real inpainting, not overlays** — other translation forks paint a box over the text or stamp new text on top. Yakuyomi *erases* the original and **reconstructs the artwork** (LaMa) before typesetting the translation back into the bubble.
- **On-device pipeline** — detection, OCR, and text removal run locally (ONNX Runtime); only the LLM translation call leaves your device. No image ever leaves the phone.
- **Two workflows** — translate-on-download (whole chapters in the background) and live translation while you read. A page is overwritten only when its translation succeeds; nothing is ever replaced with something worse.
- **Your provider, your key** — any OpenAI-compatible LLM (DeepSeek by default; OpenAI, Gemini, Groq, Qwen, OpenRouter, self-hosted Sakura, custom), per-provider encrypted keys, live model list ([providers doc](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/PROVIDERS.md)).
- **Your models** — the three ONNX models download in one tap with sha256 verification, or you supply them manually ([models doc](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/MODELS.md)).
- **Quality knobs** — three text-removal modes (flat-fill / whole-image LaMa / per-region LaMa), vertical/horizontal typesetting, ~20 tunable parameters. No telemetry.

<div align="center">
<img src="./.github/assets/yakuyomi-live-translate.gif" alt="Live translation: speech bubbles turn from Japanese into your language as you read" width="220"/>
<img src="./.github/assets/yakuyomi-rerender.gif" alt="Re-render: switch a page's text-removal method in place" width="220"/>
<img src="./.github/assets/yakuyomi-queue-manage.gif" alt="Translation queue grouped by manga: expand chapters, jump a title to the front, pause it" width="220"/>
<br>
<sub><b>Live translation as you read</b>, <b>one-tap re-render</b> of the text-removal method, and a <b>per-manga translation queue</b> — expand chapters, jump a title to the front, pause it.</sub>
</div>

**Browse & catch-up** *(new in 0.3.0)*
- **Global browse filter** — filter any source's results by *in your library* / *started reading*, applied instantly to the loaded list without re-fetching.
- **Per-source anchor** — mark a title as your "last processed point"; a one-tap action keeps loading and auto-scrolls the Latest feed until it reaches the anchor, so you never lose your place.
- **Offline snapshot** — save a source's currently-loaded list and browse it later with **zero network** (lighter on the source, ban-safe).
- **Page-load throttle** — a configurable minimum interval between page loads to keep sources from rate-limiting you.
- **Auto-refresh on open** — optionally refresh a manga's chapters every time you open it.

<div align="center">
<img src="./.github/assets/yakuyomi-browse-filter.gif" alt="Global browse filter: filter a source's loaded list by in-library / started / fetched" width="230"/>
<img src="./.github/assets/yakuyomi-browse-snapshot.gif" alt="Offline snapshot: save a source's loaded list to browse offline" width="230"/>
<img src="./.github/assets/yakuyomi-browse-anchor.gif" alt="Per-source anchor: auto-load and scroll the Latest feed down to your last-processed point" width="230"/>
<br>
<sub><b>Catch up on any source</b> — filter the loaded list by <i>in library / started reading / details fetched</i>, auto-load down to your anchor, and save it as an offline snapshot.</sub>
</div>

**Large screens & foldables** *(new in 0.4.0)*

<div align="center">
<img src="./.github/assets/yakuyomi-foldable.gif" alt="Foldable: single page when folded, double-page spread when unfolded" width="330"/>
<img src="./.github/assets/yakuyomi-queue-tablet.gif" alt="Translation queue on a tablet: two-pane master-detail — manga list left, selected manga's chapters right" width="330"/>
<br>
<sub><b>Fold → single page; unfold → double-page spread.</b> And the <b>translation queue</b> turns into a two-pane master-detail layout on tablets / unfolded foldables. Both pages translated on-device.</sub>
</div>

- **Adaptive grid cover size** — library and browse columns auto-fit the actual screen width (phone, tablet, folded/unfolded foldable, split-screen). Pick a size by how many fit per row and it scales to every width.
- **Tablet reading mode** — a separate reading mode for when you're on a large screen / unfolded (e.g. continuous-vertical on the phone, right-to-left paged on the tablet), following the system Tablet UI setting.
- **Double-page spread** *(new in 0.5.0)* — read two pages side by side on tablets/large screens (right-to-left or left-to-right). Wide/spread pages get their own full-width page instead of being squeezed in; a manual shift button aligns spreads split across the pairing. It's a reading mode, so it pairs with the tablet reading-mode setting above. Fill mode (fit width / fit height) and alignment are configurable under Settings → Reader → Double-page *(0.6.0)*.
- **Collapsible description** — choose whether a manga's description opens expanded or collapsed.

Everything else is mihon — library, sources/extensions, downloads, trackers, backups, the reader.

## Download

Grab the latest **signed APK** from the [**Releases page**](https://github.com/joyeli/Yakuyomi/releases/latest). Take the `arm64-v8a` build for most phones, or `universal` if unsure. No build step needed — you only need to [build from source](#building) if you want to.

## How translation works

```
page → detect (ONNX) → OCR (ONNX) → group → translate (LLM) → remove text (ONNX) → typeset → translated page
```

Four of the five stages run on the device; only translation leaves it. The pipeline and its design live in [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine).

## Building

The engine is a git submodule, so clone recursively:

```sh
git clone --recurse-submodules https://github.com/joyeli/Yakuyomi.git
cd Yakuyomi
./gradlew :app:assembleDebug
```

The engine is wired in via a Gradle composite build (`includeBuild`). No model weights or API keys are needed to build — you download the models and enter your LLM key inside the app.

## Relation to mihon

Yakuyomi is a real mihon fork: it tracks mihon's reader and adds only the integration layer — a download/translate hook, translation settings, model management, and branding. The on-device ML is isolated in the engine submodule, so the reader stays mihon-shaped and the engine can be tested on its own.

## Disclaimer

The developers of this application do not have any affiliation with the content providers available, and this application hosts zero content.

## License

**GPL-3.0** — see [LICENSE-YAKUYOMI.md](LICENSE-YAKUYOMI.md). Yakuyomi combines mihon (Apache-2.0, see [LICENSE](LICENSE)) with the translation engine, which ports manga-image-translator's prompt/parameters/grouping and uses GPL-3.0 models; the combined app is therefore GPL-3.0. mihon's Apache-2.0 license and attribution are retained.

## Credits

- [mihon](https://github.com/mihonapp/mihon) — the reader this forks (Apache-2.0)
- [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) — the on-device translation engine
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — prompt and behaviour reference
- model authors — [comic-text-detector](https://github.com/dmMaze/comic-text-detector), [Koharu](https://github.com/mayocream/koharu), [LaMa](https://github.com/advimman/lama)
