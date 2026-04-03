#[cfg(feature = "android")]
mod android {
    use std::ffi::CString;

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
    }

    pub fn log_with_level(level: i32, tag: &str, msg: &str) {
        unsafe {
            let tag_cstring = CString::new(tag).unwrap();
            let message_cstring = CString::new(msg).unwrap();
            __android_log_write(
                level,
                tag_cstring.as_ptr() as *const i8,
                message_cstring.as_ptr() as *const i8,
            );
        }
    }
}

pub const ANDROID_LOG_DEBUG: i32 = 3;
pub const ANDROID_LOG_INFO: i32 = 4;
pub const ANDROID_LOG_ERROR: i32 = 6;

#[cfg(feature = "android")]
pub fn android_log_with_level(level: i32, tag: &str, msg: &str) {
    android::log_with_level(level, tag, msg);
}

#[cfg(not(feature = "android"))]
pub fn android_log_with_level(_level: i32, tag: &str, msg: &str) {
    eprintln!("[{}] {}", tag, msg);
}

macro_rules! android_log_debug {
    ($msg:expr) => {
        $crate::logging::android_log_with_level(
            $crate::logging::ANDROID_LOG_DEBUG,
            "TesseractNative",
            &$msg,
        );
    };
}

macro_rules! android_log_info {
    ($msg:expr) => {
        $crate::logging::android_log_with_level(
            $crate::logging::ANDROID_LOG_INFO,
            "TesseractNative",
            &$msg,
        );
    };
}

macro_rules! android_log_error {
    ($msg:expr) => {
        $crate::logging::android_log_with_level(
            $crate::logging::ANDROID_LOG_ERROR,
            "TesseractNative",
            &$msg,
        );
    };
}

pub(crate) use android_log_debug;
pub(crate) use android_log_error;
pub(crate) use android_log_info;
