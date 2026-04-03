<h1><center>Translator</center></h1>

An Android translator app that performs text and image translation completely offline using on-device models.

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
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_transliteration.jpg" width="360px">](fastlane/metadata/android/en-US/images/phoneScreenshots/03_transliteration.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/07_dictionary.png" width="200px">](fastlane/metadata/android/en-US/images/phoneScreenshots/07_dictionary.png)

## Tech

- Translation models are [firefox-translations-models](https://github.com/mozilla/firefox-translations-models/tree/main)
  - The translation models run on [bergamot-translator](https://github.com/browsermt/bergamot-translator)
- OCR models are [Tesseract](https://github.com/tesseract-ocr/tesseract)
- Automatic language detection is done via [cld2](https://github.com/CLD2Owners/cld2)
- Dictionary is based on data from Wiktionary, exported by [Kaikki](https://kaikki.org/)
  - For Japanese specifically, there's a second "word dictionary" (Mecab) for transliterating Kanji

This app also offers an API to other apps (check `ITranslationService.aidl`), so that they can request translations to be performed for
them.

## Manual offline setup

If you want to use this app on a device with no internet access, you can put the language files on `Documents/dev.davidv.translator`. Check
`OFFLINE_SETUP.md` for details.

## Running on x86-64 emulator

This app works fine on aarch64, and it "works" on x86-64 -- in quotes because it currently requires `AVX2`, which is not available on the standard emulator, nor in the ABI.

You can be cheeky and run a VM with a good CPU configuration like this

```bash
cd $ANDROID_SDK/emulator
export LD_LIBRARY_PATH=$PWD/lib64:$PWD/lib64/qt/lib
$ANDROID_SDK/emulator/qemu/linux-x86_64/qemu-system-x86_64 -netdelay none -netspeed full -avd Medium_Phone_API_35 -qt-hide-window -grpc-use-token -idle-grpc-timeout 300 -qemu -cpu max
# The important bit is
# `-qemu -cpu max`
```

If you don't do this, you will just get a `SIGILL` when trying to load the library.

## Building

```sh
bash build.sh
```

will trigger a build in a docker container, matching the CI environment.

## Releasing

- Bump `app/build.gradle.kts` versionName and versionCode
- Create a changelog in `fastlane/metadata/android/en-US/changelogs` as `${versionCode}.txt`
- Build: `bash build.sh`
- Sign: `bash sign-apk.sh keystore.jks keystorepass pass alias`
- Create a tag that is `v${versionName}` (eg: `v0.1.0`)
- Create a Github release named `v${versionName}` (eg: `v0.1.0`)
  - Upload both signed APKs to the release
  - `gh release create v0.2.7 -F fastlane/metadata/android/en-US/changelogs/12.txt signed/translator-arm64-0.2.7.apk signed/translator-armv7-0.2.7.apk`

Each ABI gets a unique versionCode: `versionCode * 10 + abiOffset` (armv7=1, arm64=2, x86=3, x86_64=4).

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
