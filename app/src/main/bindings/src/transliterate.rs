#[uniffi::export]
pub fn transliterate_with_policy_record(
    text: String,
    language_code: String,
    source_script: String,
    target_script: String,
    japanese_dict_path: Option<String>,
    japanese_spaced: bool,
) -> Option<String> {
    translator::transliterate_with_policy_for_language(
        &text,
        &language_code,
        &source_script,
        &target_script,
        japanese_dict_path.as_deref(),
        japanese_spaced,
    )
}
