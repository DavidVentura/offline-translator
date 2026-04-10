use mucab::{Dictionary, transliterate};

extern crate jni;
use self::jni::JNIEnv;
use self::jni::objects::{JClass, JString};
use self::jni::sys::{jlong, jstring};

use crate::logging::android_log_with_level;

macro_rules! android_log {
    ($msg:expr) => {
        android_log_with_level(crate::logging::ANDROID_LOG_DEBUG, "MucabNative", &$msg);
    };
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_MucabBinding_nativeOpen(
    mut env: JNIEnv,
    _: JClass,
    java_path: JString,
) -> jlong {
    let path: String = match env.get_string(&java_path) {
        Ok(path) => path.into(),
        Err(_) => return 0,
    };

    match Dictionary::load(&path) {
        Ok(dict) => {
            android_log!(format!("Dictionary loaded from {}", path));
            let boxed_dict = Box::new(dict);
            Box::into_raw(boxed_dict) as jlong
        }
        Err(e) => {
            android_log!(format!("Failed to load dictionary: {:?}", e));
            0
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_MucabBinding_nativeTransliterateJP(
    mut env: JNIEnv,
    _: JClass,
    dict_ptr: jlong,
    java_text: JString,
    spaced: bool,
) -> jstring {
    android_log!("nativeTransliterateJP: Started");

    if dict_ptr == 0 {
        android_log!("nativeTransliterateJP: dict_ptr is 0, returning null");
        return std::ptr::null_mut();
    }

    let text: String = match env.get_string(&java_text) {
        Ok(text) => {
            let t: String = text.into();
            android_log!(format!(
                "nativeTransliterateJP: Input text length: {}",
                t.len()
            ));
            t
        }
        Err(_) => {
            android_log!("nativeTransliterateJP: Failed to get string from java_text");
            return std::ptr::null_mut();
        }
    };

    let dict = unsafe { &mut *(dict_ptr as *mut Dictionary) };
    let result = transliterate(&text, dict, spaced);

    android_log!(format!(
        "nativeTransliterateJP: Result length: {}",
        result.len()
    ));

    match env.new_string(&result) {
        Ok(jstring) => jstring.into_raw(),
        Err(_) => {
            android_log!("nativeTransliterateJP: Failed to create Java string");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_MucabBinding_nativeClose(
    _env: JNIEnv,
    _: JClass,
    dict_ptr: jlong,
) {
    if dict_ptr != 0 {
        let _ = unsafe { Box::from_raw(dict_ptr as *mut Dictionary) };
    }
}
