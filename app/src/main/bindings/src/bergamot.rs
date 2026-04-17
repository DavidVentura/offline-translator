use translator::language_detect::{detect_language, detect_language_robust_code};

#[uniffi::export]
pub fn detect_language_record(
    text: String,
    hint: Option<String>,
) -> Option<translator::DetectionResult> {
    let hint = hint.map(translator::LanguageCode::from);
    detect_language(&text, hint.as_ref())
}

#[uniffi::export]
pub fn detect_language_robust_code_record(
    text: String,
    hint: Option<String>,
    available_language_codes: Vec<String>,
) -> Option<String> {
    let hint = hint.map(translator::LanguageCode::from);
    let available = available_language_codes
        .into_iter()
        .map(translator::LanguageCode::from)
        .collect::<Vec<_>>();
    detect_language_robust_code(&text, hint.as_ref(), &available).map(|code| code.code)
}
