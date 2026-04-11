use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::OnceLock;
use std::time::Instant;

use piper_rs::{BoundaryAfter, KokoroModel, PhonemeChunk, PiperModel};

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
static MODEL_CACHE: OnceLock<Mutex<Option<CachedSpeechModel>>> = OnceLock::new();

pub struct PcmAudio {
    pub sample_rate: i32,
    pub pcm_samples: Vec<i16>,
}

fn audio_duration_ms(sample_count: usize, sample_rate: u32) -> u64 {
    if sample_rate == 0 {
        return 0;
    }
    ((sample_count as u64) * 1000) / u64::from(sample_rate)
}

enum SpeechModel {
    Piper(PiperModel),
    Kokoro(KokoroModel),
}

struct CachedSpeechModel {
    engine: String,
    model_path: String,
    aux_path: String,
    language_code: String,
    support_data_root: String,
    model: SpeechModel,
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

fn model_cache() -> &'static Mutex<Option<CachedSpeechModel>> {
    MODEL_CACHE.get_or_init(|| Mutex::new(None))
}

fn derive_japanese_dict_path(support_data_root: &str, language_code: &str) -> Option<PathBuf> {
    if language_code != "ja" || support_data_root.is_empty() {
        return None;
    }

    let candidate = Path::new(support_data_root).join("mucab.bin");
    candidate.exists().then_some(candidate)
}

fn load_speech_model(
    engine: &str,
    model_path: &str,
    aux_path: &str,
    language_code: &str,
    support_data_root: &str,
) -> Result<SpeechModel, String> {
    if aux_path.is_empty() {
        return Err(format!("Missing auxiliary path for TTS engine `{engine}`"));
    }

    match engine {
        "kokoro" => {
            let mut model = KokoroModel::new(
                Path::new(model_path),
                Path::new(aux_path),
                language_code,
            )
            .map_err(|err| format!("Failed to load Kokoro voice: {err}"))?;
            if let Some(dict_path) = derive_japanese_dict_path(support_data_root, language_code) {
                model
                    .load_japanese_dict(dict_path.to_string_lossy().as_ref())
                    .map_err(|err| format!("Failed to load Japanese dictionary: {err}"))?;
            }
            Ok(SpeechModel::Kokoro(model))
        }
        "piper" => PiperModel::new(Path::new(model_path), Path::new(aux_path))
            .map(SpeechModel::Piper)
            .map_err(|err| format!("Failed to load Piper voice: {err}")),
        other => Err(format!("Unsupported TTS engine `{other}`")),
    }
}

fn with_cached_model<T>(
    engine: &str,
    model_path: &str,
    aux_path: &str,
    language_code: &str,
    support_data_root: &str,
    f: impl FnOnce(&mut SpeechModel) -> Result<T, String>,
) -> Result<T, String> {
    let load_started_at = Instant::now();
    let mut cache = model_cache()
        .lock()
        .map_err(|_| "Failed to lock TTS model cache".to_owned())?;

    let cache_hit = cache
        .as_ref()
        .map(|cached| {
            cached.engine == engine
                && cached.model_path == model_path
                && cached.aux_path == aux_path
                && cached.language_code == language_code
                && cached.support_data_root == support_data_root
        })
        .unwrap_or(false);

    if !cache_hit {
        log_debug(format!("tts_cache miss; loading {engine} model"));
        let model = load_speech_model(
            engine,
            model_path,
            aux_path,
            language_code,
            support_data_root,
        )?;
        *cache = Some(CachedSpeechModel {
            engine: engine.to_owned(),
            model_path: model_path.to_owned(),
            aux_path: aux_path.to_owned(),
            language_code: language_code.to_owned(),
            support_data_root: support_data_root.to_owned(),
            model,
        });
    } else {
        log_debug("tts_cache hit; reusing last model");
    }
    log_timing("load_model", load_started_at);

    let cached = cache
        .as_mut()
        .ok_or_else(|| "TTS model cache was unexpectedly empty".to_owned())?;
    f(&mut cached.model)
}

fn configure_support_data_root(support_data_root: Option<&str>) {
    let Some(support_data_root) = support_data_root.filter(|path| !path.is_empty()) else {
        return;
    };

    let data_root = Path::new(support_data_root);
    let required = ["phondata", "phonindex", "phontab", "intonations"];
    let direct_layout_ok = required.iter().all(|name| data_root.join(name).exists());
    let nested_layout_ok = required
        .iter()
        .all(|name| data_root.join("espeak-ng-data").join(name).exists());

    log_debug(format!(
        "eSpeak data probe root={support_data_root} direct_layout_ok={direct_layout_ok} nested_layout_ok={nested_layout_ok}"
    ));

    match ESPEAK_DATA_DIR.set(support_data_root.to_owned()) {
        Ok(()) => {
            unsafe {
                std::env::set_var(ESPEAK_DATA_ENV, support_data_root);
            }
            log_debug(format!(
                "Configured eSpeak data directory at {support_data_root}"
            ));
        }
        Err(existing) if existing == support_data_root => {}
        Err(existing) => {
            log_error(format!(
                "Ignoring alternate eSpeak data directory {support_data_root}; already using {existing}"
            ));
        }
    }
}

fn phonemize(model: &mut SpeechModel, text: &str) -> Result<String, String> {
    match model {
        SpeechModel::Piper(model) => model
            .phonemize(text)
            .map_err(|err| format!("Speech synthesis failed: {err}")),
        SpeechModel::Kokoro(model) => model
            .phonemize(text)
            .map_err(|err| format!("Speech synthesis failed: {err}")),
    }
}

fn synthesize(
    model: &mut SpeechModel,
    text: &str,
    speaker_id: Option<i64>,
    is_phonemes: bool,
) -> Result<(Vec<f32>, u32), String> {
    match model {
        SpeechModel::Piper(model) => {
            if is_phonemes {
                model
                    .synthesize_phonemes(text, speaker_id)
                    .map_err(|err| format!("Speech synthesis failed: {err}"))
            } else {
                model
                    .synthesize(text, speaker_id)
                    .map_err(|err| format!("Speech synthesis failed: {err}"))
            }
        }
        SpeechModel::Kokoro(model) => {
            if is_phonemes {
                model
                    .synthesize_phonemes(text, speaker_id, None)
                    .map_err(|err| format!("Speech synthesis failed: {err}"))
            } else {
                model
                    .synthesize(text, speaker_id, None)
                    .map_err(|err| format!("Speech synthesis failed: {err}"))
            }
        }
    }
}

pub fn synthesize_pcm(
    engine: &str,
    model_path: &str,
    aux_path: &str,
    support_data_root: Option<&str>,
    language_code: &str,
    text: &str,
    speaker_id: Option<i64>,
    is_phonemes: bool,
) -> Result<PcmAudio, String> {
    if text.trim().is_empty() {
        return Err("Text is empty".to_owned());
    }

    let total_started_at = Instant::now();
    configure_support_data_root(support_data_root);
    let support_data_root = support_data_root.unwrap_or_default();

    log_debug(format!(
        "Synthesizing speech with engine={engine} model={model_path}"
    ));
    let (samples, sample_rate) = with_cached_model(
        engine,
        model_path,
        aux_path,
        language_code,
        support_data_root,
        |model| {
            if is_phonemes {
                log_debug(format!(
                    "synthesizing direct phoneme chunk with {} phoneme char(s)",
                    text.chars().count()
                ));
            } else {
                let phonemize_started_at = Instant::now();
                let phonemes = phonemize(model, text)?;
                log_debug(format!(
                    "phonemize produced 1 chunk, {} phoneme char(s)",
                    phonemes.chars().count(),
                ));
                log_timing("phonemize", phonemize_started_at);
            }

            let synth_started_at = Instant::now();
            let result = synthesize(model, text, speaker_id, is_phonemes)?;
            log_timing("infer", synth_started_at);
            Ok(result)
        },
    )?;

    let convert_started_at = Instant::now();
    let pcm_samples: Vec<i16> = samples
        .into_iter()
        .map(|sample| (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16)
        .collect();
    let duration_ms = audio_duration_ms(pcm_samples.len(), sample_rate);
    log_debug(format!(
        "convert_to_pcm produced {} sample(s) at {} Hz (~{} ms audio)",
        pcm_samples.len(),
        sample_rate,
        duration_ms,
    ));
    log_timing("convert_to_pcm", convert_started_at);
    log_timing("synthesize_total", total_started_at);

    Ok(PcmAudio {
        sample_rate: sample_rate as i32,
        pcm_samples,
    })
}

pub fn phonemize_chunks(
    engine: &str,
    model_path: &str,
    aux_path: &str,
    support_data_root: Option<&str>,
    language_code: &str,
    text: &str,
) -> Result<Vec<PhonemeChunk>, String> {
    if text.trim().is_empty() {
        return Err("Text is empty".to_owned());
    }

    configure_support_data_root(support_data_root);
    let support_data_root = support_data_root.unwrap_or_default();

    log_debug(format!(
        "Phonemizing text with engine={engine} model={model_path}"
    ));

    with_cached_model(
        engine,
        model_path,
        aux_path,
        language_code,
        support_data_root,
        |model| {
            let phonemize_started_at = Instant::now();
            let phonemes = phonemize(model, text)?;
            let phoneme_chunks = vec![PhonemeChunk {
                phonemes,
                boundary_after: BoundaryAfter::Paragraph,
            }];
            log_debug(format!(
                "phonemize produced {} chunk(s), {} phoneme char(s), chunk sizes [{}]",
                phoneme_chunks.len(),
                phoneme_chunks[0].phonemes.chars().count(),
                summarize_phoneme_chunk_sizes(&phoneme_chunks),
            ));
            log_timing("phonemize", phonemize_started_at);
            Ok(phoneme_chunks)
        },
    )
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
        java_engine: JString,
        java_model_path: JString,
        java_aux_path: JString,
        java_support_data_root: JString,
        java_language_code: JString,
        java_text: JString,
        speaker_id: jint,
        is_phonemes: jboolean,
    ) -> jobject {
        let engine: String = match env.get_string(&java_engine) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let model_path: String = match env.get_string(&java_model_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let aux_path: String = match env.get_string(&java_aux_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let support_data_root: String = match env.get_string(&java_support_data_root) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let language_code: String = match env.get_string(&java_language_code) {
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
            &engine,
            &model_path,
            &aux_path,
            Some(support_data_root.as_str()),
            &language_code,
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
        java_engine: JString,
        java_model_path: JString,
        java_aux_path: JString,
        java_support_data_root: JString,
        java_language_code: JString,
        java_text: JString,
    ) -> jobjectArray {
        let engine: String = match env.get_string(&java_engine) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let model_path: String = match env.get_string(&java_model_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let aux_path: String = match env.get_string(&java_aux_path) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let support_data_root: String = match env.get_string(&java_support_data_root) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let language_code: String = match env.get_string(&java_language_code) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let text: String = match env.get_string(&java_text) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let phoneme_chunks = match phonemize_chunks(
            &engine,
            &model_path,
            &aux_path,
            Some(support_data_root.as_str()),
            &language_code,
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
