#[uniffi::export]
pub fn transliterate_with_policy_record(
    text: String,
    language_code: String,
    source_script: String,
    target_script: String,
    japanese_dict_path: Option<String>,
    japanese_spaced: bool,
) -> Option<String> {
    translator::transliterate::transliterate_with_policy_for_language(
        &text,
        &translator::LanguageCode::from(language_code),
        &translator::ScriptCode::from(source_script),
        &translator::ScriptCode::from(target_script),
        japanese_dict_path.as_deref(),
        japanese_spaced,
    )
}
