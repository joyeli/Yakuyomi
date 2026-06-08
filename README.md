<div align="center">

<img src="./.github/assets/yakuyomi-logo.png" alt="Yakuyomi" width="80"/>

# Yakuyomi

**A manga reader with on-device AI translation.**

English ｜ [中文](README_zh.md)

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-0877d2?labelColor=27303D)](LICENSE-YAKUYOMI.md)

</div>

Yakuyomi is a fork of [mihon](https://github.com/mihonapp/mihon) that translates manga as you download or read it — Japanese to Traditional Chinese by default, any language pair configurable. Text **detection, OCR, and removal run on the device** (ONNX Runtime); only the **translation** step calls a cloud LLM. The translation engine is a separate repo, [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine), pulled in here as a submodule.

## Translation features

- **Translate on download** — a chapter is translated right after it downloads. A page is overwritten only when its translation succeeds; nothing is ever replaced with something worse.
- **Live translation** — open an untranslated chapter and it translates page by page as you read, swapping each page in when it's ready.
- **Bring your own provider & key** — DeepSeek by default; any OpenAI-compatible provider works (OpenAI, Gemini, Groq, Qwen, OpenRouter, self-hosted Sakura, custom). Keys are per-provider and encrypted; the model list is fetched live. See the engine's [providers doc](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/PROVIDERS.md).
- **Auto-download or bring your own models** — the three ONNX models download in one tap with sha256 verification, or you supply them manually. See the [models doc](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/MODELS.md).
- **Quality knobs** — three text-removal modes (flat-fill / whole-image LaMa / per-region LaMa), vertical or horizontal typesetting, ~20 tunable parameters.
- Everything else is mihon — library, sources/extensions, downloads, trackers, backups, the reader.

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
