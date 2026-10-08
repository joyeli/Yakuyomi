Looking to report an issue/bug or make a feature request? Please open an issue at [github.com/joyeli/Yakuyomi/issues](https://github.com/joyeli/Yakuyomi/issues).

---

Thanks for your interest in contributing to Yakuyomi!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/joyeli/Yakuyomi/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.
- JDK 17+, NDK `28.2.13676358` and CMake `3.22.1` — the engine is a git submodule with native code; see [Building](README.md#building) for the recursive clone and SDK setup.

## Where changes go

- On-device translation engine (detection, OCR, text removal, typesetting, LLM calls) and the Android glue for night reading: [joyeli/yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine).
- Night-reading rendering rules: [joyeli/yakuyomi-nightread](https://github.com/joyeli/yakuyomi-nightread).
- Everything else — the app, settings, queue, UI strings: this repo.

## Getting help

- Open a [GitHub issue](https://github.com/joyeli/Yakuyomi/issues), or email joye@joye.li.

# Translations

UI strings go through moko-resources `MR.strings`: the base is English, with a `zh-rTW` (Traditional Chinese) translation. Add any new string to both, and don't hardcode user-facing text.
