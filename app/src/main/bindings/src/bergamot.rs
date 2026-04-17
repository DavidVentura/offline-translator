use translator::language_detect::{detect_language, detect_language_robust_code};

#[uniffi::export]
pub fn detect_language_record(
    text: String,
    hint: Option<translator::LanguageCode>,
) -> Option<translator::DetectionResult> {
    detect_language(&text, hint.as_ref())
}

#[uniffi::export]
pub fn detect_language_robust_code_record(
    text: String,
    hint: Option<translator::LanguageCode>,
    available_language_codes: Vec<translator::LanguageCode>,
) -> Option<translator::LanguageCode> {
    detect_language_robust_code(&text, hint.as_ref(), &available_language_codes)
}
