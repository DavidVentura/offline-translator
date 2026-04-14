use std::sync::{Mutex, OnceLock};

use jni::JNIEnv;
use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::sys::jobjectArray;
use translator::{
    BatchTextRoutingPlan as RustBatchTextRoutingPlan, BergamotEngine,
    TranslationWithAlignment, detect_language, detect_language_robust_code,
    plan_batch_text_translation,
};

use crate::logging::{ANDROID_LOG_DEBUG, ANDROID_LOG_ERROR, android_log_with_level};

const TAG: &str = "BergamotNative";

static ENGINE: OnceLock<Mutex<Option<BergamotEngine>>> = OnceLock::new();

fn log_debug(message: impl AsRef<str>) {
    android_log_with_level(ANDROID_LOG_DEBUG, TAG, message.as_ref());
}

fn log_error(message: impl AsRef<str>) {
    android_log_with_level(ANDROID_LOG_ERROR, TAG, message.as_ref());
}

fn engine_slot() -> &'static Mutex<Option<BergamotEngine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

fn ensure_engine() -> Result<(), String> {
    let mut guard = engine_slot()
        .lock()
        .map_err(|_| "Bergamot engine mutex poisoned".to_string())?;
    if guard.is_none() {
        *guard = Some(BergamotEngine::new());
        log_debug("Initialized Rust bergamot engine");
    }
    Ok(())
}

fn with_engine<T, F>(f: F) -> Result<T, String>
where
    F: FnOnce(&mut BergamotEngine) -> Result<T, String>,
{
    ensure_engine()?;
    let mut guard = engine_slot()
        .lock()
        .map_err(|_| "Bergamot engine mutex poisoned".to_string())?;
    let engine = guard
        .as_mut()
        .ok_or_else(|| "Bergamot engine unavailable".to_string())?;
    f(engine)
}

fn throw_runtime_exception(env: &mut JNIEnv, message: &str) {
    log_error(message);
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

fn get_string(
    env: &mut JNIEnv,
    value: &JString,
) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

fn get_string_array(
    env: &mut JNIEnv,
    inputs: JObjectArray,
) -> Result<Vec<String>, String> {
    let len = env.get_array_length(&inputs).map_err(|e| e.to_string())?;
    let mut result = Vec::with_capacity(len as usize);
    for idx in 0..len {
        let value = env
            .get_object_array_element(&inputs, idx)
            .map_err(|e| e.to_string())?;
        let value = JString::from(value);
        result.push(get_string(env, &value)?);
    }
    Ok(result)
}

fn string_array(
    env: &mut JNIEnv,
    values: &[String],
) -> Result<jobjectArray, String> {
    let string_class = env.find_class("java/lang/String").map_err(|e| e.to_string())?;
    let array = env
        .new_object_array(values.len() as i32, string_class, JObject::null())
        .map_err(|e| e.to_string())?;
    for (idx, value) in values.iter().enumerate() {
        let jstr = env.new_string(value).map_err(|e| e.to_string())?;
        env.set_object_array_element(&array, idx as i32, jstr)
            .map_err(|e| e.to_string())?;
    }
    Ok(array.into_raw())
}

fn string_vec_from_array(
    env: &mut JNIEnv,
    values: JObjectArray,
) -> Result<Vec<String>, String> {
    get_string_array(env, values)
}

fn alignment_array(
    env: &mut JNIEnv,
    values: &[translator::TokenAlignment],
) -> Result<jobjectArray, String> {
    let class = env
        .find_class("dev/davidv/translator/TokenAlignment")
        .map_err(|e| e.to_string())?;
    let array = env
        .new_object_array(values.len() as i32, class, JObject::null())
        .map_err(|e| e.to_string())?;
    for (idx, value) in values.iter().enumerate() {
        let obj = env
            .new_object(
                "dev/davidv/translator/TokenAlignment",
                "(IIII)V",
                &[
                    JValue::Int(value.src_begin as i32),
                    JValue::Int(value.src_end as i32),
                    JValue::Int(value.tgt_begin as i32),
                    JValue::Int(value.tgt_end as i32),
                ],
            )
            .map_err(|e| e.to_string())?;
        env.set_object_array_element(&array, idx as i32, obj)
            .map_err(|e| e.to_string())?;
    }
    Ok(array.into_raw())
}

fn translation_with_alignment_array(
    env: &mut JNIEnv,
    values: &[TranslationWithAlignment],
) -> Result<jobjectArray, String> {
    let class = env
        .find_class("dev/davidv/translator/TranslationWithAlignment")
        .map_err(|e| e.to_string())?;
    let array = env
        .new_object_array(values.len() as i32, class, JObject::null())
        .map_err(|e| e.to_string())?;
    for (idx, value) in values.iter().enumerate() {
        let source = env
            .new_string(&value.source_text)
            .map_err(|e| e.to_string())?;
        let target = env
            .new_string(&value.translated_text)
            .map_err(|e| e.to_string())?;
        let alignments = alignment_array(env, &value.alignments)?;
        let source_obj = JObject::from(source);
        let target_obj = JObject::from(target);
        let alignments_obj = unsafe { JObject::from_raw(alignments) };
        let obj = env
            .new_object(
                "dev/davidv/translator/TranslationWithAlignment",
                "(Ljava/lang/String;Ljava/lang/String;[Ldev/davidv/translator/TokenAlignment;)V",
                &[
                    JValue::Object(&source_obj),
                    JValue::Object(&target_obj),
                    JValue::Object(&alignments_obj),
                ],
            )
            .map_err(|e| e.to_string())?;
        env.set_object_array_element(&array, idx as i32, obj)
            .map_err(|e| e.to_string())?;
    }
    Ok(array.into_raw())
}

fn detection_result_object(
    env: &mut JNIEnv,
    result: &translator::DetectionResult,
) -> Result<jni::sys::jobject, String> {
    let language = env.new_string(&result.language).map_err(|e| e.to_string())?;
    let language_obj = JObject::from(language);
    env.new_object(
        "dev/davidv/translator/DetectionResult",
        "(Ljava/lang/String;ZI)V",
        &[
            JValue::Object(&language_obj),
            JValue::Bool(if result.is_reliable { 1 } else { 0 }),
            JValue::Int(result.confidence),
        ],
    )
    .map(|object| object.into_raw())
    .map_err(|e| e.to_string())
}

fn source_text_batch_array(
    env: &mut JNIEnv,
    values: &[translator::SourceTextBatch],
) -> Result<jobjectArray, String> {
    let class = env
        .find_class("dev/davidv/translator/SourceTextBatch")
        .map_err(|e| e.to_string())?;
    let array = env
        .new_object_array(values.len() as i32, class, JObject::null())
        .map_err(|e| e.to_string())?;
    for (idx, value) in values.iter().enumerate() {
        let code = env
            .new_string(&value.source_language_code)
            .map_err(|e| e.to_string())?;
        let texts = string_array(env, &value.texts)?;
        let code_obj = JObject::from(code);
        let texts_obj = unsafe { JObject::from_raw(texts) };
        let obj = env
            .new_object(
                "dev/davidv/translator/SourceTextBatch",
                "(Ljava/lang/String;[Ljava/lang/String;)V",
                &[JValue::Object(&code_obj), JValue::Object(&texts_obj)],
            )
            .map_err(|e| e.to_string())?;
        env.set_object_array_element(&array, idx as i32, obj)
            .map_err(|e| e.to_string())?;
    }
    Ok(array.into_raw())
}

fn batch_text_routing_plan_object(
    env: &mut JNIEnv,
    plan: RustBatchTextRoutingPlan,
) -> Result<jni::sys::jobject, String> {
    let passthrough_texts = string_array(env, &plan.passthrough_texts)?;
    let batches = source_text_batch_array(env, &plan.batches)?;

    let passthrough_obj = unsafe { JObject::from_raw(passthrough_texts) };
    let batches_obj = unsafe { JObject::from_raw(batches) };
    match plan.nothing_reason {
        Some(reason) => {
            let reason = env.new_string(reason.as_str()).map_err(|e| e.to_string())?;
            let reason_obj = JObject::from(reason);
            env.new_object(
                "dev/davidv/translator/BatchTextRoutingPlan",
                "([Ljava/lang/String;[Ldev/davidv/translator/SourceTextBatch;Ljava/lang/String;)V",
                &[
                    JValue::Object(&passthrough_obj),
                    JValue::Object(&batches_obj),
                    JValue::Object(&reason_obj),
                ],
            )
            .map(|object| object.into_raw())
            .map_err(|e| e.to_string())
        }
        None => {
            let reason_obj = JObject::null();
            env.new_object(
                "dev/davidv/translator/BatchTextRoutingPlan",
                "([Ljava/lang/String;[Ldev/davidv/translator/SourceTextBatch;Ljava/lang/String;)V",
                &[
                    JValue::Object(&passthrough_obj),
                    JValue::Object(&batches_obj),
                    JValue::Object(&reason_obj),
                ],
            )
            .map(|object| object.into_raw())
            .map_err(|e| e.to_string())
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_initializeService(
    mut env: JNIEnv,
    _: JObject,
) {
    if let Err(err) = ensure_engine() {
        throw_runtime_exception(&mut env, &err);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_loadModelIntoCache(
    mut env: JNIEnv,
    _: JObject,
    cfg: JString,
    key: JString,
) {
    let result = (|| {
        let cfg = get_string(&mut env, &cfg)?;
        let key = get_string(&mut env, &key)?;
        with_engine(|engine| engine.load_model_into_cache(&cfg, &key))
    })();

    if let Err(err) = result {
        throw_runtime_exception(&mut env, &err);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_translateMultiple(
    mut env: JNIEnv,
    _: JObject,
    inputs: JObjectArray,
    key: JString,
) -> jobjectArray {
    match (|| {
        let inputs = get_string_array(&mut env, inputs)?;
        let key = get_string(&mut env, &key)?;
        let values = with_engine(|engine| engine.translate_multiple(&inputs, &key))?;
        string_array(&mut env, &values)
    })() {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_pivotMultiple(
    mut env: JNIEnv,
    _: JObject,
    first_key: JString,
    second_key: JString,
    inputs: JObjectArray,
) -> jobjectArray {
    match (|| {
        let first_key = get_string(&mut env, &first_key)?;
        let second_key = get_string(&mut env, &second_key)?;
        let inputs = get_string_array(&mut env, inputs)?;
        let values = with_engine(|engine| {
            engine.pivot_multiple(&first_key, &second_key, &inputs)
        })?;
        string_array(&mut env, &values)
    })() {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_translateMultipleWithAlignment(
    mut env: JNIEnv,
    _: JObject,
    inputs: JObjectArray,
    key: JString,
) -> jobjectArray {
    match (|| {
        let inputs = get_string_array(&mut env, inputs)?;
        let key = get_string(&mut env, &key)?;
        let values = with_engine(|engine| {
            engine.translate_multiple_with_alignment(&inputs, &key)
        })?;
        translation_with_alignment_array(&mut env, &values)
    })() {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_pivotMultipleWithAlignment(
    mut env: JNIEnv,
    _: JObject,
    first_key: JString,
    second_key: JString,
    inputs: JObjectArray,
) -> jobjectArray {
    match (|| {
        let first_key = get_string(&mut env, &first_key)?;
        let second_key = get_string(&mut env, &second_key)?;
        let inputs = get_string_array(&mut env, inputs)?;
        let values = with_engine(|engine| {
            engine.pivot_multiple_with_alignment(&first_key, &second_key, &inputs)
        })?;
        translation_with_alignment_array(&mut env, &values)
    })() {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_TranslationRuntime_cleanup(
    mut env: JNIEnv,
    _: JObject,
) {
    let result: Result<(), String> = (|| {
        let mut guard = engine_slot()
            .lock()
            .map_err(|_| "Bergamot engine mutex poisoned".to_string())?;
        if let Some(engine) = guard.as_mut() {
            engine.clear();
        }
        *guard = None;
        Ok(())
    })();

    if let Err(err) = result {
        throw_runtime_exception(&mut env, &err);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_NativeLanguageDetector_detectLanguage(
    mut env: JNIEnv,
    _: JObject,
    text: JString,
    hint: JString,
) -> jni::sys::jobject {
    let result: Result<jni::sys::jobject, String> = (|| {
        let text = get_string(&mut env, &text)?;
        let hint = if hint.is_null() {
            None
        } else {
            Some(get_string(&mut env, &hint)?)
        };
        let result = detect_language(&text, hint.as_deref());
        match result {
            Some(result) => detection_result_object(&mut env, &result),
            None => Ok(std::ptr::null_mut()),
        }
    })();
    match result {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_LanguageRoutingRuntime_detectLanguageRobustCode(
    mut env: JNIEnv,
    _: JObject,
    text: JString,
    hint: JString,
    available_language_codes: JObjectArray,
) -> jni::sys::jstring {
    let result: Result<jni::sys::jstring, String> = (|| {
        let text = get_string(&mut env, &text)?;
        let hint = if hint.is_null() {
            None
        } else {
            Some(get_string(&mut env, &hint)?)
        };
        let available_language_codes =
            string_vec_from_array(&mut env, available_language_codes)?;
        match detect_language_robust_code(&text, hint.as_deref(), &available_language_codes) {
            Some(code) => env
                .new_string(code)
                .map(|value| value.into_raw())
                .map_err(|e| e.to_string()),
            None => Ok(std::ptr::null_mut()),
        }
    })();
    match result {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_LanguageRoutingRuntime_planBatchTextTranslation(
    mut env: JNIEnv,
    _: JObject,
    inputs: JObjectArray,
    forced_source_code: JString,
    target_code: JString,
    available_language_codes: JObjectArray,
) -> jni::sys::jobject {
    let result: Result<jni::sys::jobject, String> = (|| {
        let inputs = string_vec_from_array(&mut env, inputs)?;
        let forced_source_code = if forced_source_code.is_null() {
            None
        } else {
            Some(get_string(&mut env, &forced_source_code)?)
        };
        let target_code = get_string(&mut env, &target_code)?;
        let available_language_codes =
            string_vec_from_array(&mut env, available_language_codes)?;
        let plan = plan_batch_text_translation(
            &inputs,
            forced_source_code.as_deref(),
            &target_code,
            &available_language_codes,
        );
        batch_text_routing_plan_object(&mut env, plan)
    })();
    match result {
        Ok(result) => result,
        Err(err) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
    }
}
