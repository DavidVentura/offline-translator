use jni::JNIEnv;
use jni::objects::{JObject, JObjectArray, JString, JValue};
use translator::{detect_language, detect_language_robust_code};

use crate::logging::{ANDROID_LOG_ERROR, android_log_with_level};

const TAG: &str = "BergamotNative";

fn log_error(message: impl AsRef<str>) {
    android_log_with_level(ANDROID_LOG_ERROR, TAG, message.as_ref());
}

fn throw_runtime_exception(env: &mut JNIEnv, message: &str) {
    log_error(message);
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

fn get_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

fn get_string_array(env: &mut JNIEnv, inputs: JObjectArray) -> Result<Vec<String>, String> {
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

fn string_vec_from_array(env: &mut JNIEnv, values: JObjectArray) -> Result<Vec<String>, String> {
    get_string_array(env, values)
}

fn detection_result_object(
    env: &mut JNIEnv,
    result: &translator::DetectionResult,
) -> Result<jni::sys::jobject, String> {
    let language = env
        .new_string(&result.language)
        .map_err(|e| e.to_string())?;
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

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_davidv_translator_NativeLanguageRuntime_detectLanguage(
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
pub unsafe extern "system" fn Java_dev_davidv_translator_NativeLanguageRuntime_detectLanguageRobustCode(
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
        let available_language_codes = string_vec_from_array(&mut env, available_language_codes)?;
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
