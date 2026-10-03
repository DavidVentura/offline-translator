import json
import re
import time
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from catalog_tts_samples import TTS_SAMPLES


def tts_file_role(filename: str) -> str:
    if filename.endswith(".onnx.json"):
        return "sidecar"
    # A two-stage voice ships two networks; the vocoder is matched first so it
    # does not fall through to the generic model rule below.
    if filename.endswith("hifigan.mnn"):
        return "vocoder"
    if filename.endswith("_lexicon.bin"):
        return "lexicon"
    if filename.endswith(".mnn.weight"):
        return "modelWeight"
    if filename.endswith(".onnx") or filename.endswith(".mnn"):
        return "model"
    if filename == "config.json":
        return "config"
    if filename.endswith("tokens.txt"):
        return "tokens"
    if filename == "language_ids.json":
        return "languageIds"
    if filename == "speaker_ids.json":
        return "speakerIds"
    if filename.endswith(".bin"):
        return "voices"
    if filename.endswith("lexicon.txt.zst") or filename.endswith("lexicon.txt"):
        return "lexicon"
    if filename.endswith("rule.fst"):
        return "ruleFst"
    raise ValueError(f"unknown TTS file role for {filename!r}")


# The auxiliary file role each engine's loader consumes alongside the model.
AUX_ROLE_BY_ENGINE = {
    "piper": "sidecar",
    "mimic3": "sidecar",
    "mms": "tokens",
    "coqui_vits": "config",
    "sherpa_vits": "config",
    "cotovia_vits": "lexicon",
    "glowtts_hifigan": "lexicon",
    "kokoro": "voices",
    "kokoro_mnn": "voices",
}


PIPER_BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
KOKORO_BASE_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"
MMS_BASE_URL = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main"
COQUI_VITS_BASE_URL = "https://offline-translator.davidv.dev/tts/1"
TTS_BASE_URL = "https://offline-translator.davidv.dev/tts"
TTS_VERSION = 1
KOKORO_SHARED_PACK_ID = "tts-kokoro-v1.0-core"
KOKORO_MNN_SHARED_PACK_ID = "tts-kokoro-mnn-v1.0-core"
KOKORO_VOICES_PACK_ID = "tts-kokoro-voices-v1.0"

COTOVIA_LEXICON_PACK_IDS = {"gl": "tts-cotovia-lexicon-gl"}
COTOVIA_LEXICON_FILES = {
    "gl": {
        "name": "gl_lexicon.txt.zst",
        "size_bytes": 995200,
        "install_path": "bin/cotovia_vits/gl/gl_lexicon.txt.zst",
        "url": f"{COQUI_VITS_BASE_URL}/cotovia/gl/gl_lexicon.txt.zst",
    },
}

CORE_ESPEAK_FILES = ("phondata", "phonindex", "phontab", "intonations")
QUALITY_PRIORITY = {
    "medium": 0,
    "low": 1,
    "x_low": 2,
    "high": 3,
}
ENGINE_PRIORITY = {
    "piper": 0,
    "mimic3": 0,
    "kokoro_mnn": 0,
    "glowtts_hifigan": 1,
    "mms": 1,
    "sherpa_vits": 2,
    "coqui_vits": 2,
    "kokoro": 3,
}
DEFAULT_REGION_OVERRIDES = {
    "en": "US",
    "es": "ES",
    "nl": "NL",
    "pt": "BR",
    "zh_hant": "TW",
}
APP_LANGUAGE_OVERRIDES = {
    "zh_CN": "zh",
    "zh_TW": "zh_hant",
    "zh_HK": "zh_hant",
    "yue_HK": "zh_hant",
}
# A spoken-language voice set serves several written-standard app languages.
# Bokmål and Nynorsk are written norms of spoken Norwegian; the `no` voice
# (and eSpeak's only Norwegian phonemizer, Bokmål) covers both. The aliased
# languages reference the source language's voice packs — nothing extra is
# downloaded. Aliases apply only to targets that have no voices of their own.
TTS_LANGUAGE_ALIASES = {
    "no": ["nb", "nn"],
}
ESPEAK_DICT_OVERRIDES = {
    "zh": "cmn",
}
# Support packs a language's voices need on top of their engine's own files.
# Japanese text has to be tokenized by mucab before anything can pronounce or
# romanize it, so every ja voice depends on the dictionary — the language-level
# support entry is not enough, a voice can be downloaded on its own.
VOICE_SUPPORT_PACKS = {
    "ja": ["support-ja-mucab"],
}
BANNED_TTS_VOICES = {
    # Broken Spanish Piper MLS voices. Keep them out of the generated TTS catalog.
    "es_ES-mls_10246-low",
    "es_ES-mls_9972-low",
    # de/nl mls voices completely break on short sentences
    "de_DE-mls-medium",
    "nl_NL-mls-medium",
    "nl_NL-mls_5809-low",
    "nl_NL-mls_7432-low",
    # Upstream Piper metadata still lists this voice, but the files 404 on Hugging Face.
    "no_NO-nvcc-medium",
    # Piper pinyin-frontend Mandarin voices are not supported by the runtime frontend.
    "zh_CN-chaowen-medium",
    "zh_CN-xiao_ya-medium",
    # Output sounds broken, in the app as well.
    "sv_SE-lisa-medium",
}

# Human-curated per-voice rank nudges. Applied as the highest-priority component
# of voice_sort_key, so they win over quality/engine/size heuristics within a
# region. Negative values uprank (earlier in the list -> becomes the default);
# positive values downrank (may fall out of the top 4). Keys are voice keys
# (locale_code-name-quality); add one line per voice you want to move.
VOICE_RANK_PREFERENCES: dict[str, int] = {
    # amy sounds pretty good, fine to default
    "en_US-amy-medium": -1,
    # keep thorsten as the German default; glados is a novelty voice
    "de_DE-thorsten-medium": -1,
    # sam sounds like a robot
    "en_US-sam-medium": 1,
    # sabela is HiTZ's flagship Galician voice; keep it over the alphabetical tie
    "gl_ES-sabela-cotovia_vits": -1,
}

# Voices kept per region (the rest are dropped from the served index). Galician
# ships the full HiTZ set (minus the broken celtia), so it overrides the default.
DEFAULT_VOICES_PER_REGION = 4
PER_LANGUAGE_VOICES_PER_REGION = {"gl": 5}
EXTRA_TTS_VOICES = {
    # External Polish Piper voice metadata source:
    # https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx.json
    "pl_PL-jarvis_wg_glos-medium": {
        "engine": "piper",
        "key": "pl_PL-jarvis_wg_glos-medium",
        "name": "jarvis_wg_glos",
        "language": {
            "code": "pl_PL",
            "family": "pl",
            "region": "PL",
            "name_native": "Polski",
            "name_english": "Polish",
            "country_english": "Poland",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "pl/pl_PL/jarvis_wg_glos/medium/pl_PL-jarvis_wg_glos-medium.onnx": {
                "size_bytes": 63516050,
                "url": "https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx",
            },
            "pl/pl_PL/jarvis_wg_glos/medium/pl_PL-jarvis_wg_glos-medium.onnx.json": {
                "size_bytes": 7104,
                "url": "https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx.json",
            },
        },
        "aliases": [],
    },
    # External German Piper voice (GLaDOS), finetuned from the Portal voice.
    # https://huggingface.co/systemofapwne/piper-de-glados/tree/main/de/de_DE
    "de_DE-glados-medium": {
        "engine": "piper",
        "key": "de_DE-glados-medium",
        "name": "glados",
        "language": {
            "code": "de_DE",
            "family": "de",
            "region": "DE",
            "name_native": "Deutsch",
            "name_english": "German",
            "country_english": "Germany",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "de/de_DE/glados/medium/de_DE-glados-medium.onnx": {
                "size_bytes": 63153773,
                "url": f"{COQUI_VITS_BASE_URL}/de/de_DE/glados/medium/de_DE-glados-medium.onnx",
            },
            "de/de_DE/glados/medium/de_DE-glados-medium.onnx.json": {
                "size_bytes": 7100,
                "url": f"{COQUI_VITS_BASE_URL}/de/de_DE/glados/medium/de_DE-glados-medium.onnx.json",
            },
        },
        "aliases": [],
    },
    # Removed external Hebrew Piper community voices from the generated catalog.
    # Needs IPA input, probably using phonikud:
    # https://github.com/thewh1teagle/phonikud
    # They use `phoneme_type = text` with a non-Hebrew token inventory, so normal
    # Hebrew script input collapses to no tokens in the current runtime.
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/heb
    "he_IL-standard-mms": {
        "engine": "mms",
        "key": "he_IL-standard-mms",
        "name": "standard",
        "language": {
            "code": "he_IL",
            "family": "he",
            "region": "IL",
            "name_native": "עברית",
            "name_english": "Hebrew",
            "country_english": "Israel",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "heb/model.onnx": {
                "size_bytes": 114012344,
                "url": f"{MMS_BASE_URL}/heb/model.onnx",
            },
            "heb/tokens.txt": {
                "size_bytes": 179,
                "url": f"{MMS_BASE_URL}/heb/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/mar
    "mr_IN-standard-mms": {
        "engine": "mms",
        "key": "mr_IN-standard-mms",
        "name": "standard",
        "language": {
            "code": "mr_IN",
            "family": "mr",
            "region": "IN",
            "name_native": "मराठी",
            "name_english": "Marathi",
            "country_english": "India",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "mar/model.onnx": {
                "size_bytes": 114043832,
                "url": f"{MMS_BASE_URL}/mar/model.onnx",
            },
            "mar/tokens.txt": {
                "size_bytes": 490,
                "url": f"{MMS_BASE_URL}/mar/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/tgl
    "tl_PH-standard-mms": {
        "engine": "mms",
        "key": "tl_PH-standard-mms",
        "name": "standard",
        "language": {
            "code": "tl_PH",
            "family": "tl",
            "region": "PH",
            "name_native": "Tagalog",
            "name_english": "Tagalog",
            "country_english": "Philippines",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "tgl/model.onnx": {
                "size_bytes": 114020792,
                "url": f"{MMS_BASE_URL}/tgl/model.onnx",
            },
            "tgl/tokens.txt": {
                "size_bytes": 337,
                "url": f"{MMS_BASE_URL}/tgl/tokens.txt",
            },
        },
        "aliases": [],
    },
    # alex73's Belarusian GlowTTS acoustic model and HiFiGAN vocoder, trained on
    # Common Voice via the bel-alex73 recipe and released through Coqui
    # (CC-BY-SA 4.0). It reads fanetyka IPA rather than Cyrillic, so the voice is
    # only usable together with the BNKorpus grapheme-to-phoneme table shipped
    # alongside it.
    "be_BY-alex73-glowtts_hifigan": {
        "engine": "glowtts_hifigan",
        "key": "be_BY-alex73-glowtts_hifigan",
        "name": "alex73",
        "language": {
            "code": "be_BY",
            "family": "be",
            "region": "BY",
            "name_native": "Беларуская",
            "name_english": "Belarusian",
            "country_english": "Belarus",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "glowtts.mnn": {
                "size_bytes": 29431392,
                "url": f"{TTS_BASE_URL}/{TTS_VERSION}/be/be_BY/alex73/medium/glowtts.mnn",
            },
            "hifigan.mnn": {
                "size_bytes": 14083764,
                "url": f"{TTS_BASE_URL}/{TTS_VERSION}/be/be_BY/alex73/medium/hifigan.mnn",
            },
            "be_lexicon.bin": {
                "size_bytes": 3586841,
                "url": f"{TTS_BASE_URL}/{TTS_VERSION}/be/be_BY/alex73/medium/be_lexicon.bin",
            },
        },
        "aliases": [],
    },
    # HiTZ sabela Galician VITS voice. Its cotovia g2p is baked offline into a
    # shared per-language word->id lexicon (see scripts/gl_sabela_lexicon in
    # translator-rs); the voice depends on the tts-cotovia-lexicon-gl pack.
    "gl_ES-sabela-cotovia_vits": {
        "engine": "cotovia_vits",
        "key": "gl_ES-sabela-cotovia_vits",
        "name": "sabela",
        "language": {
            "code": "gl_ES",
            "family": "gl",
            "region": "ES",
            "name_native": "Galego",
            "name_english": "Galician",
            "country_english": "Spain",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "shared_pack": COTOVIA_LEXICON_PACK_IDS["gl"],
        "files": {
            "vits.onnx": {
                "size_bytes": 60333923,
                "url": "https://huggingface.co/HiTZ/TTS-gl_sabela/resolve/main/vits.onnx",
            },
        },
        "aliases": [],
    },
    # External Coqui VITS model metadata source:
    # https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    "et_EE-cv-coqui_vits": {
        "engine": "coqui_vits",
        "key": "et_EE-cv-coqui_vits",
        "name": "cv",
        "language": {
            "code": "et_EE",
            "family": "et",
            "region": "EE",
            "name_native": "Eesti",
            "name_english": "Estonian",
            "country_english": "Estonia",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "vits-coqui-et-cv/model.onnx": {
                "size_bytes": 71030265,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-et-cv/model.onnx",
            },
            "vits-coqui-et-cv/config.json": {
                "size_bytes": 8281,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-et-cv/config.json",
            },
            "vits-coqui-et-cv/language_ids.json": {
                "size_bytes": 15,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-et-cv/language_ids.json",
            },
            "vits-coqui-et-cv/speaker_ids.json": {
                "size_bytes": 19,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-et-cv/speaker_ids.json",
            },
            "vits-coqui-et-cv/tokens.txt": {
                "size_bytes": 1522,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-et-cv/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External Coqui VITS model metadata source:
    # https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    "hr_HR-cv-coqui_vits": {
        "engine": "coqui_vits",
        "key": "hr_HR-cv-coqui_vits",
        "name": "cv",
        "language": {
            "code": "hr_HR",
            "family": "hr",
            "region": "HR",
            "name_native": "Hrvatski",
            "name_english": "Croatian",
            "country_english": "Croatia",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "vits-coqui-hr-cv/model.onnx": {
                "size_bytes": 71043321,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/model.onnx",
            },
            "vits-coqui-hr-cv/config.json": {
                "size_bytes": 8452,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/config.json",
            },
            "vits-coqui-hr-cv/language_ids.json": {
                "size_bytes": 15,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/language_ids.json",
            },
            "vits-coqui-hr-cv/speaker_ids.json": {
                "size_bytes": 19,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/speaker_ids.json",
            },
            "vits-coqui-hr-cv/tokens.txt": {
                "size_bytes": 1760,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External Coqui VITS model metadata source:
    # https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    # Bosnian fallback backed by the Croatian Common Voice model files.
    "bs_BA-cv-coqui_vits": {
        "engine": "coqui_vits",
        "key": "bs_BA-cv-coqui_vits",
        "name": "cv",
        "language": {
            "code": "bs_BA",
            "family": "bs",
            "region": "BA",
            "name_native": "Bosanski",
            "name_english": "Bosnian",
            "country_english": "Bosnia and Herzegovina",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "vits-coqui-hr-cv/model.onnx": {
                "size_bytes": 71043321,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/model.onnx",
            },
            "vits-coqui-hr-cv/config.json": {
                "size_bytes": 8452,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/config.json",
            },
            "vits-coqui-hr-cv/language_ids.json": {
                "size_bytes": 15,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/language_ids.json",
            },
            "vits-coqui-hr-cv/speaker_ids.json": {
                "size_bytes": 19,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/speaker_ids.json",
            },
            "vits-coqui-hr-cv/tokens.txt": {
                "size_bytes": 1760,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-hr-cv/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External Coqui VITS model metadata source:
    # https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    "lt_LT-cv-coqui_vits": {
        "engine": "coqui_vits",
        "key": "lt_LT-cv-coqui_vits",
        "name": "cv",
        "language": {
            "code": "lt_LT",
            "family": "lt",
            "region": "LT",
            "name_native": "Lietuvių",
            "name_english": "Lithuanian",
            "country_english": "Lithuania",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "vits-coqui-lt-cv/model.onnx": {
                "size_bytes": 71032571,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-lt-cv/model.onnx",
            },
            "vits-coqui-lt-cv/config.json": {
                "size_bytes": 8276,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-lt-cv/config.json",
            },
            "vits-coqui-lt-cv/language_ids.json": {
                "size_bytes": 15,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-lt-cv/language_ids.json",
            },
            "vits-coqui-lt-cv/speaker_ids.json": {
                "size_bytes": 19,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-lt-cv/speaker_ids.json",
            },
            "vits-coqui-lt-cv/tokens.txt": {
                "size_bytes": 1564,
                "url": f"{COQUI_VITS_BASE_URL}/vits-coqui-lt-cv/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External Kokoro model metadata source:
    # https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
    "ja_JP-jf_alpha-kokoro-v1.0": {
        "engine": "kokoro",
        "shared_pack": KOKORO_SHARED_PACK_ID,
        "key": "ja_JP-jf_alpha-kokoro-v1.0",
        "name": "jf_alpha (deprecated)",
        "language": {
            "code": "ja_JP",
            "family": "ja",
            "region": "JP",
            "name_native": "日本語",
            "name_english": "Japanese",
            "country_english": "Japan",
        },
        "quality": "high",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
    "ja_JP-jf_alpha-kokoro-mnn-v1.0": {
        "engine": "kokoro_mnn",
        "shared_pack": KOKORO_MNN_SHARED_PACK_ID,
        "key": "ja_JP-jf_alpha-kokoro-mnn-v1.0",
        "name": "jf_alpha",
        "language": {
            "code": "ja_JP",
            "family": "ja",
            "region": "JP",
            "name_native": "日本語",
            "name_english": "Japanese",
            "country_english": "Japan",
        },
        "quality": "high",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
    # External Kokoro model metadata source:
    # https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
    "ko_KR-jf_alpha-kokoro-v1.0": {
        "engine": "kokoro",
        "shared_pack": KOKORO_SHARED_PACK_ID,
        "key": "ko_KR-jf_alpha-kokoro-v1.0",
        "name": "jf_alpha (deprecated)",
        "language": {
            "code": "ko_KR",
            "family": "ko",
            "region": "KR",
            "name_native": "한국어",
            "name_english": "Korean",
            "country_english": "South Korea",
        },
        "quality": "high",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
    "ko_KR-jf_alpha-kokoro-mnn-v1.0": {
        "engine": "kokoro_mnn",
        "shared_pack": KOKORO_MNN_SHARED_PACK_ID,
        "key": "ko_KR-jf_alpha-kokoro-mnn-v1.0",
        "name": "jf_alpha",
        "language": {
            "code": "ko_KR",
            "family": "ko",
            "region": "KR",
            "name_native": "한국어",
            "name_english": "Korean",
            "country_english": "South Korea",
        },
        "quality": "high",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
    # External Mimic3 model metadata source:
    # https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    "ko_KO-kss_low": {
        "engine": "mimic3",
        "install_root": "piper",
        "key": "ko_KO-kss_low",
        "name": "kss",
        "language": {
            "code": "ko_KO",
            "family": "ko",
            "region": "KR",
            "name_native": "한국어",
            "name_english": "Korean",
            "country_english": "South Korea",
        },
        "quality": "low",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "ko/ko_KO/kss/low/ko_KO-kss_low.onnx": {
                "size_bytes": 62793110,
                "url": f"{COQUI_VITS_BASE_URL}/vits-mimic3-ko_KO-kss_low/ko_KO-kss_low.onnx",
            },
            "ko/ko_KO/kss/low/ko_KO-kss_low.onnx.json": {
                "size_bytes": 3411,
                "url": f"{COQUI_VITS_BASE_URL}/vits-mimic3-ko_KO-kss_low/ko_KO-kss_low.onnx.json",
            },
            "ko/ko_KO/kss/low/tokens.txt": {
                "size_bytes": 260,
                "url": f"{COQUI_VITS_BASE_URL}/vits-mimic3-ko_KO-kss_low/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External Traditional Chinese Piper voice metadata source:
    # https://huggingface.co/colafly/piper_zh_tw/resolve/main/yt-chinese_female.onnx.json
    "zh_TW-mandarin_traditional-medium": {
        "engine": "piper",
        "key": "zh_TW-mandarin_traditional-medium",
        "name": "Mandarin (Traditional)",
        "language": {
            "code": "zh_TW",
            "family": "zh",
            "region": "TW",
            "name_native": "繁體中文",
            "name_english": "Chinese",
            "country_english": "Taiwan",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "zh_hant/zh_TW/mandarin_traditional/medium/yt-chinese_female.onnx": {
                "size_bytes": 63531476,
                "url": "https://huggingface.co/colafly/piper_zh_tw/resolve/main/yt-chinese_female.onnx",
            },
            "zh_hant/zh_TW/mandarin_traditional/medium/yt-chinese_female.onnx.json": {
                "size_bytes": 7352,
                "url": "https://huggingface.co/colafly/piper_zh_tw/resolve/main/yt-chinese_female.onnx.json",
            },
        },
        "aliases": [],
    },
    # External Hong Kong Cantonese Sherpa VITS voice metadata source:
    # https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/tree/main
    "zh_HK-cantonese-medium": {
        "engine": "sherpa_vits",
        "key": "zh_HK-cantonese-medium",
        "name": "Cantonese",
        "language": {
            "code": "zh_HK",
            "family": "zh",
            "region": "HK",
            "name_native": "廣東話",
            "name_english": "Chinese",
            "country_english": "Hong Kong",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "zh_hant/zh_HK/cantonese/medium/vits-cantonese-hf-xiaomaiiwn.onnx": {
                "size_bytes": 114059955,
                "url": "https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/vits-cantonese-hf-xiaomaiiwn.onnx",
            },
            "zh_hant/zh_HK/cantonese/medium/config.json": {
                "size_bytes": 2746,
                "url": "https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/config.json",
            },
            "zh_hant/zh_HK/cantonese/medium/lexicon.txt": {
                "size_bytes": 294061,
                "url": "https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/lexicon.txt",
            },
            "zh_hant/zh_HK/cantonese/medium/tokens.txt": {
                "size_bytes": 529,
                "url": "https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/tokens.txt",
            },
            "zh_hant/zh_HK/cantonese/medium/rule.fst": {
                "size_bytes": 64482,
                "url": "https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/rule.fst",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/azj-script_latin
    "az_AZ-north_latin-mms": {
        "engine": "mms",
        "key": "az_AZ-north_latin-mms",
        "name": "north_latin",
        "language": {
            "code": "az_AZ",
            "family": "az",
            "region": "AZ",
            "name_native": "Azərbaycan dili",
            "name_english": "Azerbaijani",
            "country_english": "Azerbaijan",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "azj-script_latin/model.onnx": {
                "size_bytes": 114020024,
                "url": f"{MMS_BASE_URL}/azj-script_latin/model.onnx",
            },
            "azj-script_latin/tokens.txt": {
                "size_bytes": 361,
                "url": f"{MMS_BASE_URL}/azj-script_latin/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/ben
    "bn_IN-standard-mms": {
        "engine": "mms",
        "key": "bn_IN-standard-mms",
        "name": "standard",
        "language": {
            "code": "bn_IN",
            "family": "bn",
            "region": "IN",
            "name_native": "বাংলা",
            "name_english": "Bengali",
            "country_english": "India",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "ben/model.onnx": {
                "size_bytes": 114044600,
                "url": f"{MMS_BASE_URL}/ben/model.onnx",
            },
            "ben/tokens.txt": {
                "size_bytes": 480,
                "url": f"{MMS_BASE_URL}/ben/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/guj
    "gu_IN-standard-mms": {
        "engine": "mms",
        "key": "gu_IN-standard-mms",
        "name": "standard",
        "language": {
            "code": "gu_IN",
            "family": "gu",
            "region": "IN",
            "name_native": "ગુજરાતી",
            "name_english": "Gujarati",
            "country_english": "India",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "guj/model.onnx": {
                "size_bytes": 114033848,
                "url": f"{MMS_BASE_URL}/guj/model.onnx",
            },
            "guj/tokens.txt": {
                "size_bytes": 402,
                "url": f"{MMS_BASE_URL}/guj/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/kan
    "kn_IN-standard-mms": {
        "engine": "mms",
        "key": "kn_IN-standard-mms",
        "name": "standard",
        "language": {
            "code": "kn_IN",
            "family": "kn",
            "region": "IN",
            "name_native": "ಕನ್ನಡ",
            "name_english": "Kannada",
            "country_english": "India",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "kan/model.onnx": {
                "size_bytes": 114045368,
                "url": f"{MMS_BASE_URL}/kan/model.onnx",
            },
            "kan/tokens.txt": {
                "size_bytes": 487,
                "url": f"{MMS_BASE_URL}/kan/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/zlm
    "ms_MY-standard-mms": {
        "engine": "mms",
        "key": "ms_MY-standard-mms",
        "name": "standard",
        "language": {
            "code": "ms_MY",
            "family": "ms",
            "region": "MY",
            "name_native": "Bahasa Melayu",
            "name_english": "Malay",
            "country_english": "Malaysia",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "zlm/model.onnx": {
                "size_bytes": 114013880,
                "url": f"{MMS_BASE_URL}/zlm/model.onnx",
            },
            "zlm/tokens.txt": {
                "size_bytes": 275,
                "url": f"{MMS_BASE_URL}/zlm/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/tam
    "ta_IN-standard-mms": {
        "engine": "mms",
        "key": "ta_IN-standard-mms",
        "name": "standard",
        "language": {
            "code": "ta_IN",
            "family": "ta",
            "region": "IN",
            "name_native": "தமிழ்",
            "name_english": "Tamil",
            "country_english": "India",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "tam/model.onnx": {
                "size_bytes": 114032312,
                "url": f"{MMS_BASE_URL}/tam/model.onnx",
            },
            "tam/tokens.txt": {
                "size_bytes": 375,
                "url": f"{MMS_BASE_URL}/tam/tokens.txt",
            },
        },
        "aliases": [],
    },
    # External MMS model metadata source:
    # https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/tree/main/tha
    "th_TH-standard-mms": {
        "engine": "mms",
        "key": "th_TH-standard-mms",
        "name": "standard",
        "language": {
            "code": "th_TH",
            "family": "th",
            "region": "TH",
            "name_native": "ไทย",
            "name_english": "Thai",
            "country_english": "Thailand",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "tha/model.onnx": {
                "size_bytes": 114042296,
                "url": f"{MMS_BASE_URL}/tha/model.onnx",
            },
            "tha/tokens.txt": {
                "size_bytes": 473,
                "url": f"{MMS_BASE_URL}/tha/tokens.txt",
            },
        },
        "aliases": [],
    },
    # Self-hosted: willwade has no uig export, so this is exported from the
    # facebook/mms-tts-uig-script_arabic torch checkpoint by
    # translator-rs/scripts/export_mms_onnx.py and converted to int8 MNN. The
    # runtime is MNN-only, so no .onnx is published and no migration applies.
    "ug_CN-standard-mms": {
        "engine": "mms",
        "key": "ug_CN-standard-mms",
        "name": "standard",
        "language": {
            "code": "ug_CN",
            "family": "ug",
            "region": "CN",
            "name_native": "ئۇيغۇرچە",
            "name_english": "Uyghur",
            "country_english": "China",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "ug/model.mnn": {
                "size_bytes": 29736804,
                "url": f"{TTS_BASE_URL}/1/ug/ug_CN/standard/medium/model.mnn",
            },
            "ug/tokens.txt": {
                "size_bytes": 227,
                "url": f"{TTS_BASE_URL}/1/ug/ug_CN/standard/medium/tokens.txt",
            },
        },
        "aliases": [],
    },
}

# The other HiTZ Galician voices share sabela's cotovia VITS_U2W frontend
# (137-symbol vocab), so they reuse the one tts-cotovia-lexicon-gl pack — only
# the per-speaker onnx differs (verified identical onnx signatures, so the shared
# lexicon is correct for all). celtia is excluded: its model has unnatural
# pauses/diction (a model issue, not the frontend). gl ships 5 voices.
for _gl_voice in ["brais", "iago", "icia", "paulo"]:
    EXTRA_TTS_VOICES[f"gl_ES-{_gl_voice}-cotovia_vits"] = {
        "engine": "cotovia_vits",
        "key": f"gl_ES-{_gl_voice}-cotovia_vits",
        "name": _gl_voice,
        "language": EXTRA_TTS_VOICES["gl_ES-sabela-cotovia_vits"]["language"],
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "shared_pack": COTOVIA_LEXICON_PACK_IDS["gl"],
        "files": {
            "vits.onnx": {
                "size_bytes": 60333923,
                "url": f"https://huggingface.co/HiTZ/TTS-gl_{_gl_voice}/resolve/main/vits.onnx",
            },
        },
        "aliases": [],
    }

KOKORO_SHARED_FILES = {
    "kokoro-v1.0.int8.onnx": {
        "size_bytes": 92361271,
        "url": f"{KOKORO_BASE_URL}/kokoro-v1.0.int8.onnx",
    },
}

KOKORO_MNN_SHARED_FILES = {
    "kokoro.mnn": {
        "size_bytes": 1028464,
        "url": f"{TTS_BASE_URL}/{TTS_VERSION}/kokoro_mnn/kokoro.mnn",
    },
    "kokoro.mnn.weight": {
        "size_bytes": 86949555,
        "url": f"{TTS_BASE_URL}/{TTS_VERSION}/kokoro_mnn/kokoro.mnn.weight",
    },
}

KOKORO_VOICES_FILES = {
    "voices-v1.0.bin": {
        "size_bytes": 28214398,
        "url": f"{KOKORO_BASE_URL}/voices-v1.0.bin",
    },
}
def load_json(path: str) -> dict:
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def merge_voice_catalogs(voices: dict) -> dict:
    merged = deepcopy(voices)
    merged.update(EXTRA_TTS_VOICES)
    for key in BANNED_TTS_VOICES:
        merged.pop(key, None)
    return merged


def app_language_code(voice: dict, supported_languages: set[str]) -> str | None:
    locale_code = voice["language"]["code"]
    if locale_code in APP_LANGUAGE_OVERRIDES:
        code = APP_LANGUAGE_OVERRIDES[locale_code]
        return code if code in supported_languages else None

    family = voice["language"]["family"]
    return family if family in supported_languages else None


def espeak_dict_code(app_language: str, locale_code: str) -> str:
    if locale_code.startswith("zh_TW"):
        return "cmn"
    if locale_code.startswith("zh_HK") or locale_code.startswith("yue_"):
        return "yue"
    return ESPEAK_DICT_OVERRIDES.get(app_language, app_language)


def voice_sort_key(item: tuple[str, dict]) -> tuple[int, int, int, int, int, str]:
    key, voice = item
    preference = VOICE_RANK_PREFERENCES.get(key, 0)
    quality_rank = QUALITY_PRIORITY.get(voice.get("quality"), 99)
    engine_rank = ENGINE_PRIORITY.get(voice.get("engine", "piper"), 99)
    speaker_rank = 0 if voice.get("num_speakers", 1) == 1 else 1
    model_size = min(
        (
            file_info.get("size_bytes", 0)
            for path, file_info in voice.get("files", {}).items()
            if path.endswith(".onnx") and not path.endswith(".onnx.json")
        ),
        default=0,
    )
    return preference, quality_rank, engine_rank, speaker_rank, model_size, key


def region_display_name(voice: dict) -> str:
    region = voice["language"].get("country_english")
    if region:
        return region
    return voice["language"].get("region", voice["language"]["code"])


def slug_path_segment(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip()).strip("-")
    return slug.lower() or "default"


def tts_install_path(
    *,
    install_root: str,
    app_language: str,
    locale_code: str,
    voice_name: str,
    quality: str | None,
    filename: str,
) -> str:
    quality_segment = slug_path_segment(quality or "default")
    voice_segment = slug_path_segment(voice_name)
    return f"bin/{install_root}/{app_language}/{locale_code}/{voice_segment}/{quality_segment}/{filename}"


def resolve_espeak_data_dir(configured: str | None) -> Path | None:
    if configured:
        path = Path(configured)
        return path if path.exists() else None

    repo_checkout = Path("/home/david/git/espeak-ng-rs/espeak-ng-data")
    if repo_checkout.exists():
        return repo_checkout

    candidates = sorted(
        Path("app/src/main/bindings").glob(
            "target/aarch64-linux-android/release/build/espeak-rs-sys-*/out/espeak-ng/espeak-ng-data"
        )
    )
    return candidates[-1] if candidates else None


def build_espeak_core_zip(espeak_data_dir: Path, output_path: Path) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output_path, "w", compression=ZIP_DEFLATED) as archive:
        for filename in CORE_ESPEAK_FILES:
            archive.write(
                espeak_data_dir / filename,
                arcname=f"espeak-ng-data/{filename}",
            )
        for directory_name in ("lang", "voices"):
            directory = espeak_data_dir / directory_name
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    archive.write(path, arcname=f"espeak-ng-data/{path.relative_to(espeak_data_dir)}")
    return output_path.stat().st_size


def build_espeak_support_packs(
    catalog: dict,
    dict_codes: set[str],
    tts_base_url: str,
    tts_version: int,
    espeak_core_zip_size: int,
) -> None:
    core_pack_id = f"tts-espeak-core-v{tts_version}"
    catalog["packs"][core_pack_id] = {
        "feature": "support",
        "kind": "tts-espeak-core",
        "files": [
            {
                "name": "espeak-ng-data.zip",
                "sizeBytes": espeak_core_zip_size,
                "installPath": "bin/espeak-ng-data.zip",
                "url": f"{tts_base_url.rstrip('/')}/{tts_version}/espeak-ng-data.zip",
                "archiveFormat": "zip",
                "extractTo": "bin",
                "deleteAfterExtract": True,
                "installMarkerPath": "bin/espeak-ng-data/.install-info.json",
                "installMarkerVersion": tts_version,
                "role": "espeakCore",
                "priority": 0,
            }
        ],
        "dependsOn": [],
    }

    for dict_code in sorted(dict_codes):
        catalog["packs"][f"tts-espeak-dict-{dict_code}"] = {
            "feature": "support",
            "kind": "tts-espeak-dict",
            "files": [
                {
                    "name": f"{dict_code}_dict",
                    "sizeBytes": 0,
                    "installPath": f"bin/espeak-ng-data/{dict_code}_dict",
                    "url": f"{tts_base_url.rstrip('/')}/{tts_version}/espeak-ng-data/{dict_code}_dict",
                    "role": "espeakDict",
                    "priority": 0,
                }
            ],
            "dependsOn": [core_pack_id],
        }


def build_shared_tts_support_packs(catalog: dict, voices: dict) -> None:
    needs_kokoro = any(voice.get("shared_pack") == KOKORO_SHARED_PACK_ID for voice in voices.values())
    needs_kokoro_mnn = any(voice.get("shared_pack") == KOKORO_MNN_SHARED_PACK_ID for voice in voices.values())

    if needs_kokoro or needs_kokoro_mnn:
        catalog["packs"][KOKORO_VOICES_PACK_ID] = {
            "feature": "support",
            "kind": "tts-kokoro-voices",
            "files": [
                {
                    "name": filename,
                    "sizeBytes": file_info["size_bytes"],
                    "installPath": f"bin/kokoro/{filename}",
                    "url": file_info["url"],
                    "role": tts_file_role(filename),
                    "priority": 0,
                }
                for filename, file_info in KOKORO_VOICES_FILES.items()
            ],
            "dependsOn": [],
        }

    if needs_kokoro:
        catalog["packs"][KOKORO_SHARED_PACK_ID] = {
            "feature": "support",
            "kind": "tts-kokoro-core",
            "files": [
                {
                    "name": filename,
                    "sizeBytes": file_info["size_bytes"],
                    "installPath": f"bin/kokoro/{filename}",
                    "url": file_info["url"],
                    "role": tts_file_role(filename),
                    "priority": 0,
                }
                for filename, file_info in KOKORO_SHARED_FILES.items()
            ],
            "dependsOn": [KOKORO_VOICES_PACK_ID],
        }

    if needs_kokoro_mnn:
        catalog["packs"][KOKORO_MNN_SHARED_PACK_ID] = {
            "feature": "support",
            "kind": "tts-kokoro-mnn-core",
            "files": [
                {
                    "name": filename,
                    "sizeBytes": file_info["size_bytes"],
                    "installPath": f"bin/kokoro_mnn/{filename}",
                    "url": file_info["url"],
                    "role": tts_file_role(filename),
                    "priority": 0,
                }
                for filename, file_info in KOKORO_MNN_SHARED_FILES.items()
            ],
            "dependsOn": [KOKORO_VOICES_PACK_ID],
        }


def build_cotovia_lexicon_packs(catalog: dict, voices: dict) -> None:
    for language_code, pack_id in COTOVIA_LEXICON_PACK_IDS.items():
        if not any(voice.get("shared_pack") == pack_id for voice in voices.values()):
            continue
        lexicon = COTOVIA_LEXICON_FILES[language_code]
        catalog["packs"][pack_id] = {
            "feature": "support",
            "kind": "tts-cotovia-lexicon",
            "files": [
                {
                    "name": lexicon["name"],
                    "sizeBytes": lexicon["size_bytes"],
                    "installPath": lexicon["install_path"],
                    "url": lexicon["url"],
                    "role": tts_file_role(lexicon["name"]),
                    "priority": 0,
                }
            ],
            "dependsOn": [],
        }


def engine_supports_espeak(engine: str) -> bool:
    return engine in {"piper", "mimic3", "kokoro", "kokoro_mnn"}


def merge_tts(
    base_catalog: dict,
    voices: dict,
    piper_base_url: str,
    tts_base_url: str,
    tts_version: int,
    espeak_core_zip_size: int,
) -> dict:
    catalog = deepcopy(base_catalog)
    voices = merge_voice_catalogs(voices)
    catalog["generatedAt"] = int(time.time())
    for entry in catalog["languages"].values():
        entry.pop("tts", None)
    catalog["packs"] = {
        pack_id: pack
        for pack_id, pack in catalog["packs"].items()
        if pack.get("feature") != "tts" and pack.get("kind") not in {"tts-espeak-core", "tts-espeak-dict"}
    }
    supported_languages = set(catalog["languages"].keys())
    grouped: dict[tuple[str, str], list[tuple[str, dict]]] = defaultdict(list)
    required_dict_codes: set[str] = set()

    for key, voice in voices.items():
        app_language = app_language_code(voice, supported_languages)
        if app_language is None:
            continue
        region = voice["language"].get("region")
        if not region:
            continue
        grouped[(app_language, region)].append((key, voice))
        if engine_supports_espeak(voice.get("engine", "piper")):
            required_dict_codes.add(espeak_dict_code(app_language, voice["language"]["code"]))

    if required_dict_codes:
        build_espeak_support_packs(catalog, required_dict_codes, tts_base_url, tts_version, espeak_core_zip_size)
    build_shared_tts_support_packs(catalog, voices)
    build_cotovia_lexicon_packs(catalog, voices)

    regions_by_language: dict[str, dict[str, dict]] = defaultdict(dict)
    for (app_language, region), region_voices in sorted(grouped.items()):
        limit = PER_LANGUAGE_VOICES_PER_REGION.get(app_language, DEFAULT_VOICES_PER_REGION)
        ranked = sorted(region_voices, key=voice_sort_key)[:limit]
        voice_ids: list[str] = []

        for key, voice in ranked:
            engine = voice.get("engine", "piper")
            install_root = voice.get("install_root", engine)
            pack_id = f"tts-{engine}-{key.replace('_', '-').lower()}"
            locale_code = voice["language"]["code"]
            quality = voice.get("quality")
            dict_code = espeak_dict_code(app_language, locale_code)
            files = []
            for source_path, file_info in voice.get("files", {}).items():
                if source_path.endswith("MODEL_CARD"):
                    continue
                filename = source_path.rsplit("/", 1)[-1]
                files.append(
                    {
                        "name": filename,
                        "sizeBytes": file_info.get("size_bytes", 0),
                        "installPath": tts_install_path(
                            install_root=install_root,
                            app_language=app_language,
                            locale_code=locale_code,
                            voice_name=voice["name"],
                            quality=quality,
                            filename=filename,
                        ),
                        "url": file_info.get("url") or f"{piper_base_url.rstrip('/')}/{source_path}",
                        "role": tts_file_role(filename),
                        "priority": 0,
                    }
                )

            default_speaker_id = None
            speaker_id_map = voice.get("speaker_id_map") or {}
            if speaker_id_map:
                default_speaker_id = sorted(speaker_id_map.values())[0]

            depends_on = []
            if engine_supports_espeak(engine):
                depends_on.append(f"tts-espeak-dict-{dict_code}")
            shared_pack = voice.get("shared_pack")
            if shared_pack:
                depends_on.append(shared_pack)
            depends_on.extend(VOICE_SUPPORT_PACKS.get(app_language, []))
            depends_on.extend(voice.get("depends_on", []))

            pack = {
                "feature": "tts",
                "engine": engine,
                "auxRole": AUX_ROLE_BY_ENGINE[engine],
                "language": app_language,
                "locale": locale_code,
                "region": region,
                "voice": voice["name"],
                "quality": quality,
                "numSpeakers": voice.get("num_speakers", 1),
                "defaultSpeakerId": default_speaker_id,
                "aliases": voice.get("aliases", []),
                "files": files,
                "dependsOn": depends_on,
            }
            if default_speaker_id is None:
                pack.pop("defaultSpeakerId")
            catalog["packs"][pack_id] = pack
            voice_ids.append(pack_id)

        if voice_ids:
            regions_by_language[app_language][region] = {
                "displayName": region_display_name(ranked[0][1]),
                "voices": voice_ids,
            }

    for source, targets in TTS_LANGUAGE_ALIASES.items():
        if source not in regions_by_language:
            continue
        for target in targets:
            if target not in supported_languages or target in regions_by_language:
                continue
            regions_by_language[target] = dict(regions_by_language[source])

    missing_samples = sorted(set(regions_by_language) - set(TTS_SAMPLES))
    if missing_samples:
        raise ValueError(
            f"TTS_SAMPLES missing entries for languages with TTS voices: {missing_samples}"
        )

    for language_code, regions in regions_by_language.items():
        default_region = DEFAULT_REGION_OVERRIDES.get(language_code)
        if default_region not in regions:
            default_region = next(iter(regions.keys()))
        catalog["languages"][language_code]["tts"] = {
            "defaultRegion": default_region,
            "regions": regions,
            "sampleText": TTS_SAMPLES[language_code],
        }

    return catalog
