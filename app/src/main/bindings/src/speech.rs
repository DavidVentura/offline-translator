use std::path::Path;
use std::sync::Mutex;
use std::sync::OnceLock;
use std::time::Instant;

use piper_rs::{BoundaryAfter, PhonemeChunk, Piper};

use crate::logging::{ANDROID_LOG_DEBUG, ANDROID_LOG_ERROR, android_log_with_level};

const TAG: &str = "SpeechNative";
const ESPEAK_DATA_ENV: &str = "PIPER_ESPEAKNG_DATA_DIRECTORY";

fn log_debug(message: impl AsRef<str>) {
    android_log_with_level(ANDROID_LOG_DEBUG, TAG, message.as_ref());
}

fn log_error(message: impl AsRef<str>) {
    android_log_with_level(ANDROID_LOG_ERROR, TAG, message.as_ref());
}

static ESPEAK_DATA_DIR: OnceLock<String> = OnceLock::new();
static PIPER_CACHE: OnceLock<Mutex<Option<CachedPiper>>> = OnceLock::new();

pub struct PcmAudio {
    pub sample_rate: i32,
    pub pcm_samples: Vec<i16>,
}

struct CachedPiper {
    model_path: String,
    config_path: String,
    piper: Piper,
}

fn log_timing(step: &str, started_at: Instant) {
    log_debug(format!(
        "{step} took {} ms",
        started_at.elapsed().as_millis()
    ));
}

fn summarize_phoneme_chunk_sizes(chunks: &[PhonemeChunk]) -> String {
    const MAX_CHUNKS_TO_LOG: usize = 6;

    let preview = chunks
        .iter()
        .take(MAX_CHUNKS_TO_LOG)
        .map(|chunk| chunk.phonemes.chars().count().to_string())
        .collect::<Vec<_>>()
        .join(", ");

    if chunks.len() > MAX_CHUNKS_TO_LOG {
        format!("{preview}, ...")
    } else {
        preview
    }
}

fn boundary_after_code(boundary_after: BoundaryAfter) -> i32 {
    match boundary_after {
        BoundaryAfter::None => 0,
        BoundaryAfter::Sentence => 1,
        BoundaryAfter::Paragraph => 2,
    }
}

fn piper_cache() -> &'static Mutex<Option<CachedPiper>> {
    PIPER_CACHE.get_or_init(|| Mutex::new(None))
}

fn with_cached_piper<T>(
    model_path: &str,
    config_path: &str,
    f: impl FnOnce(&mut Piper) -> Result<T, String>,
) -> Result<T, String> {
    let load_started_at = Instant::now();
    let mut cache = piper_cache()
        .lock()
        .map_err(|_| "Failed to lock Piper cache".to_owned())?;

    let cache_hit = cache
        .as_ref()
        .map(|cached| cached.model_path == model_path && cached.config_path == config_path)
        .unwrap_or(false);

    if !cache_hit {
        log_debug("piper_cache miss; loading model");
        let piper = Piper::new(Path::new(model_path), Path::new(config_path))
            .map_err(|err| format!("Failed to load Piper voice: {err}"))?;
        *cache = Some(CachedPiper {
            model_path: model_path.to_owned(),
            config_path: config_path.to_owned(),
            piper,
        });
    } else {
        log_debug("piper_cache hit; reusing last model");
    }
    log_timing("load_model", load_started_at);

    let cached = cache
        .as_mut()
        .ok_or_else(|| "Piper cache was unexpectedly empty".to_owned())?;
    f(&mut cached.piper)
}

fn configure_espeak_data_directory(espeak_data_path: Option<&str>) {
    let Some(espeak_data_path) = espeak_data_path.filter(|path| !path.is_empty()) else {
        return;
    };

    let data_root = Path::new(espeak_data_path);
    let required = ["phondata", "phonindex", "phontab", "intonations"];
    let direct_layout_ok = required.iter().all(|name| data_root.join(name).exists());
    let nested_layout_ok = required
        .iter()
        .all(|name| data_root.join("espeak-ng-data").join(name).exists());

    log_debug(format!(
        "eSpeak data probe root={espeak_data_path} direct_layout_ok={direct_layout_ok} nested_layout_ok={nested_layout_ok}"
    ));

    match ESPEAK_DATA_DIR.set(espeak_data_path.to_owned()) {
        Ok(()) => {
            // piper-rs discovers eSpeak data through process-global lookup.
            // Set the path once before the first synthesis attempt.
            unsafe {
                std::env::set_var(ESPEAK_DATA_ENV, espeak_data_path);
            }
            log_debug(format!(
                "Configured eSpeak data directory at {espeak_data_path}"
            ));
        }
        Err(existing) if existing == espeak_data_path => {}
        Err(existing) => {
            log_error(format!(
                "Ignoring alternate eSpeak data directory {espeak_data_path}; already using {existing}"
            ));
        }
    }
}

pub fn synthesize_pcm(
    model_path: &str,
    config_path: &str,
    espeak_data_path: Option<&str>,
    text: &str,
    speaker_id: Option<i64>,
    is_phonemes: bool,
) -> Result<PcmAudio, String> {
    if text.trim().is_empty() {
        return Err("Text is empty".to_owned());
    }

    let total_started_at = Instant::now();
    configure_espeak_data_directory(espeak_data_path);

    log_debug(format!("Synthesizing speech with model {model_path}"));
    let (samples, sample_rate) = with_cached_piper(model_path, config_path, |piper| {
        if is_phonemes {
            log_debug(format!(
                "synthesizing direct phoneme chunk with {} phoneme char(s)",
                text.chars().count()
            ));
        } else {
            let phonemize_started_at = Instant::now();
            let phoneme_chunks = piper
                .phonemize_chunks(text)
                .map_err(|err| format!("Speech synthesis failed: {err}"))?;
            let total_phoneme_chars: usize = phoneme_chunks
                .iter()
                .map(|chunk| chunk.phonemes.chars().count())
                .sum();
            log_debug(format!(
                "phonemize produced {} chunk(s), {} phoneme char(s), chunk sizes [{}]",
                phoneme_chunks.len(),
                total_phoneme_chars,
                summarize_phoneme_chunk_sizes(&phoneme_chunks),
            ));
            log_timing("phonemize", phonemize_started_at);
        }

        let synth_started_at = Instant::now();
        let result = piper
            .create(text, is_phonemes, speaker_id, None, None, None)
            .map_err(|err| format!("Speech synthesis failed: {err}"))?;
        log_timing("infer", synth_started_at);
        Ok(result)
    })?;

    let convert_started_at = Instant::now();
    let pcm_samples: Vec<i16> = samples
        .into_iter()
        .map(|sample| (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16)
        .collect();
    log_debug(format!(
        "convert_to_pcm produced {} sample(s) at {} Hz",
        pcm_samples.len(),
        sample_rate
    ));
    log_timing("convert_to_pcm", convert_started_at);
    log_timing("synthesize_total", total_started_at);

    Ok(PcmAudio {
        sample_rate: sample_rate as i32,
        pcm_samples,
    })
}

pub fn phonemize_chunks(
    model_path: &str,
    config_path: &str,
    espeak_data_path: Option<&str>,
    text: &str,
) -> Result<Vec<PhonemeChunk>, String> {
    if text.trim().is_empty() {
        return Err("Text is empty".to_owned());
    }

    configure_espeak_data_directory(espeak_data_path);
    log_debug(format!("Phonemizing text with model {model_path}"));

    with_cached_piper(model_path, config_path, |piper| {
        let phonemize_started_at = Instant::now();
        let phoneme_chunks = piper
            .phonemize_chunks(text)
            .map_err(|err| format!("Speech synthesis failed: {err}"))?;
        let total_phoneme_chars: usize = phoneme_chunks
            .iter()
            .map(|chunk| chunk.phonemes.chars().count())
            .sum();
        log_debug(format!(
            "phonemize produced {} chunk(s), {} phoneme char(s), chunk sizes [{}]",
            phoneme_chunks.len(),
            total_phoneme_chars,
            summarize_phoneme_chunk_sizes(&phoneme_chunks),
        ));
        log_timing("phonemize", phonemize_started_at);
        Ok(phoneme_chunks)
    })
}

#[cfg(feature = "android")]
mod jni_bridge {
    use super::*;
    use jni::JNIEnv;
    use jni::objects::{JClass, JObject, JString, JValue};
    use jni::sys::{jboolean, jint, jobject, jobjectArray};

    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn Java_dev_davidv_translator_SpeechBinding_nativeSynthesizePcm(
        mut env: JNIEnv,
        _: JClass,
        java_model_path: JString,
        java_config_path: JString,
        java_espeak_data_path: JString,
        java_text: JString,
        speaker_id: jint,
        is_phonemes: jboolean,
    ) -> jobject {
        let model_path: String = match env.get_string(&java_model_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let config_path: String = match env.get_string(&java_config_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let espeak_data_path: String = match env.get_string(&java_espeak_data_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let text: String = match env.get_string(&java_text) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let speaker_id = if speaker_id < 0 {
            None
        } else {
            Some(i64::from(speaker_id))
        };

        match synthesize_pcm(
            &model_path,
            &config_path,
            Some(espeak_data_path.as_str()),
            &text,
            speaker_id,
            is_phonemes != 0,
        ) {
            Ok(audio) => {
                let pcm_array = match env.new_short_array(audio.pcm_samples.len() as i32) {
                    Ok(array) => array,
                    Err(_) => return std::ptr::null_mut(),
                };
                if env
                    .set_short_array_region(&pcm_array, 0, &audio.pcm_samples)
                    .is_err()
                {
                    return std::ptr::null_mut();
                }
                let pcm_object = JObject::from(pcm_array);
                match env.new_object(
                    "dev/davidv/translator/PcmAudio",
                    "(I[S)V",
                    &[JValue::Int(audio.sample_rate), JValue::Object(&pcm_object)],
                ) {
                    Ok(object) => object.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                }
            }
            Err(error) => {
                log_error(error);
                std::ptr::null_mut()
            }
        }
    }

    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn Java_dev_davidv_translator_SpeechBinding_nativePhonemizeChunks(
        mut env: JNIEnv,
        _: JClass,
        java_model_path: JString,
        java_config_path: JString,
        java_espeak_data_path: JString,
        java_text: JString,
    ) -> jobjectArray {
        let model_path: String = match env.get_string(&java_model_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let config_path: String = match env.get_string(&java_config_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let espeak_data_path: String = match env.get_string(&java_espeak_data_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let text: String = match env.get_string(&java_text) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let phoneme_chunks = match phonemize_chunks(
            &model_path,
            &config_path,
            Some(espeak_data_path.as_str()),
            &text,
        ) {
            Ok(chunks) => chunks,
            Err(error) => {
                log_error(error);
                return std::ptr::null_mut();
            }
        };

        let chunk_class = match env.find_class("dev/davidv/translator/NativePhonemeChunk") {
            Ok(class) => class,
            Err(_) => return std::ptr::null_mut(),
        };

        let array =
            match env.new_object_array(phoneme_chunks.len() as i32, chunk_class, JObject::null()) {
                Ok(array) => array,
                Err(_) => return std::ptr::null_mut(),
            };

        for (index, chunk) in phoneme_chunks.iter().enumerate() {
            let java_text = match env.new_string(&chunk.phonemes) {
                Ok(value) => value,
                Err(_) => return std::ptr::null_mut(),
            };
            let java_text = JObject::from(java_text);
            let java_chunk = match env.new_object(
                "dev/davidv/translator/NativePhonemeChunk",
                "(Ljava/lang/String;I)V",
                &[
                    JValue::Object(&java_text),
                    JValue::Int(boundary_after_code(chunk.boundary_after)),
                ],
            ) {
                Ok(value) => value,
                Err(_) => return std::ptr::null_mut(),
            };
            if env
                .set_object_array_element(&array, index as i32, java_chunk)
                .is_err()
            {
                return std::ptr::null_mut();
            }
        }

        array.into_raw()
    }
}
