<h1><center>Translator</center></h1>

An Android translator app that performs text, document and image translation completely offline using on-device models.

Supports automatic language detection and transliteration for non-latin scripts. There's also a built-in word dictionary.

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/dev.davidv.translator)


## How It Works

**Complete offline translation** - download language packs once, translate forever without internet.

Language packs contain the full translation models, translation happens _on your device_, no requests are sent to external servers.

## Screenshots

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_main_interface.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/01_main_interface.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02_image_translation.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/02_image_translation.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04_image_translation_big.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/04_image_translation_big.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05_language_packs.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/05_language_packs.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_transliteration.png" width="360px">](fastlane/metadata/android/en-US/images/phoneScreenshots/03_transliteration.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/07_dictionary.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/07_dictionary.png)

## Tech

- Translation models are Firefox' [translations models](https://github.com/mozilla/translations)
  - The translation models run on a modified [slimt](https://github.com/jerinphilip/slimt)
- OCR models are [Tesseract](https://github.com/tesseract-ocr/tesseract)
- Automatic language detection is done via [cld2](https://github.com/CLD2Owners/cld2)
- Dictionary is based on data from Wiktionary, exported by [Kaikki](https://kaikki.org/)
  - For Japanese specifically, there's a second "word dictionary" (Mecab) for transliterating Kanji
- TTS uses [Piper](https://github.com/OHF-Voice/piper1-gpl), [Coqui](https://github.com/coqui-ai/tts), [Kokoro](https://github.com/hexgrad/kokoro), [MMS](https://huggingface.co/facebook/mms-tts), [Sherpa ONNX](https://github.com/k2-fsa/sherpa-onnx), [Mimic3](https://github.com/MycroftAI/mimic3) voices
- PDF surgery uses [mupdf](https://github.com/ArtifexSoftware/mupdf) and [lopdf](https://github.com/J-F-Liu/lopdf).

### Translating other apps

There are two ways to translate content from other apps.

#### Digital assistant

Press a button or use a gesture to translate whatever's on your screen. To enable it, go through the app settings, or on your phone:

**Settings > Apps > Default apps > Digital assistant app** and select 'Translator'.

Note: voice integration is not supported yet, and Android only allows one assistant at a time.

#### Accessibility service

Places a floating bubble on screen that you can tap to translate the current screen at any time. To enable it:

**Settings > Accessibility** and enable 'Translator'.

Apps tend to provide better data through accessibility than through the assistant interface, but your mileage may vary.

#### For developers

This app exposes an API (see `ITranslationService.aidl`) that other apps can use to request translations.


## Manual offline setup

If you want to use this app on a device with no internet access, you can put the language files on `Documents/dev.davidv.translator`. Check
`OFFLINE_SETUP.md` for details.

## Building

```sh
bash build.sh
```

will trigger a build in a docker container, matching the CI environment.

## Releasing

- Bump `app/build.gradle.kts` versionName and versionCode
- Create a changelog in `fastlane/metadata/android/en-US/changelogs` as `${versionCode*10+1}.txt` (and `+2`)
- Build: `bash build.sh`
- Sign: `bash sign-apk.sh keystore.jks keystorepass pass alias`
- Create a tag that is `v${versionName}` (eg: `v0.1.0`)
- Create a Github release named `v${versionName}` (eg: `v0.1.0`)
  - Upload both signed APKs to the release
  - `gh release create v0.4.0 -F fastlane/metadata/android/en-US/changelogs/141.txt -F fastlane/metadata/android/en-US/changelogs/142.txt signed/translator-arm64-0.4.0.apk signed/translator-armv7-0.4.0.apk`

Each ABI gets a unique versionCode: `versionCode * 10 + abiOffset` (armv7=1, arm64=2, x86=3, x86\_64=4).

## Signing APK
```sh
bash sign-apk.sh keystore.jks keystorepass pass alias
```

will sign the APKs built by `build.sh` and place the signed copies in `signed/translator-{arm64,armv7}-${version}.apk`

### Verification info

SHA-256 hash of signing certificate: `2B:38:06:E7:45:D8:09:01:8A:51:BE:58:D0:63:5F:FC:74:CC:97:33:43:94:07:AB:1E:D0:42:4A:4D:B3:E1:FB`

## Funding

<img src="https://nlnet.nl/logo/banner.svg" width="200px">

This project was funded through the [NGI Mobifree Fund](https://nlnet.nl/mobifree), a fund established by [NLnet](https://nlnet.nl).
