use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use regex::Regex;

use crate::{BergamotEngine, CatalogSnapshot, detect_language};
use crate::translate::{execute_translation_plan, resolve_translation_plan_in_snapshot};

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum NothingReason {
    AlreadyTargetLanguage,
    CouldNotDetect,
    NoTranslatableText,
}

impl NothingReason {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::AlreadyTargetLanguage => "ALREADY_TARGET_LANGUAGE",
            Self::CouldNotDetect => "COULD_NOT_DETECT",
            Self::NoTranslatableText => "NO_TRANSLATABLE_TEXT",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceTextBatch {
    pub source_language_code: String,
    pub texts: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BatchTextRoutingPlan {
    pub passthrough_texts: Vec<String>,
    pub batches: Vec<SourceTextBatch>,
    pub nothing_reason: Option<NothingReason>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TextTranslation {
    pub source_text: String,
    pub translated_text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct MixedTextTranslationResult {
    pub translations: Vec<TextTranslation>,
    pub nothing_reason: Option<NothingReason>,
}

fn passthrough_pattern() -> &'static Regex {
    static PATTERN: OnceLock<Regex> = OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new(r"^[\p{Number}\p{White_Space}\p{Punctuation}·•–—―]+$")
            .expect("valid passthrough regex")
    })
}

fn is_passthrough_text(text: &str) -> bool {
    passthrough_pattern().is_match(text)
}

fn unique_texts(inputs: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for input in inputs {
        if seen.insert(input.clone()) {
            result.push(input.clone());
        }
    }
    result
}

pub fn detect_language_robust_code(
    text: &str,
    hint: Option<&str>,
    available_language_codes: &[String],
) -> Option<String> {
    if text.trim().is_empty() {
        return None;
    }

    if let Some(detected) = detect_language(text, hint) {
        if detected.is_reliable
            && available_language_codes
                .iter()
                .any(|code| code == &detected.language)
        {
            return Some(detected.language);
        }
    }

    for code in available_language_codes {
        if hint == Some(code.as_str()) {
            continue;
        }
        let Some(detected) = detect_language(text, Some(code)) else {
            continue;
        };
        if detected.is_reliable && detected.language == *code {
            return Some(code.clone());
        }
    }

    None
}

pub fn plan_batch_text_translation(
    inputs: &[String],
    forced_source_code: Option<&str>,
    target_code: &str,
    available_language_codes: &[String],
) -> BatchTextRoutingPlan {
    let unique_inputs = unique_texts(inputs);
    let mut passthrough_texts = Vec::new();
    let mut translatable = Vec::new();

    for text in unique_inputs {
        if text.trim().is_empty() {
            continue;
        }
        if is_passthrough_text(&text) {
            passthrough_texts.push(text);
        } else {
            translatable.push(text);
        }
    }

    let mut batches = Vec::new();
    let mut batch_index_by_source = HashMap::<String, usize>::new();
    let mut detected_same_as_target = 0;
    let mut undetected_texts = 0;

    if let Some(forced_source_code) = forced_source_code {
        if !translatable.is_empty() {
            batches.push(SourceTextBatch {
                source_language_code: forced_source_code.to_string(),
                texts: translatable,
            });
        }
    } else {
        for text in translatable {
            let source_code =
                detect_language_robust_code(&text, None, available_language_codes);
            match source_code {
                None => undetected_texts += 1,
                Some(source_code) if source_code == target_code => detected_same_as_target += 1,
                Some(source_code) => {
                    let batch_index = *batch_index_by_source
                        .entry(source_code.clone())
                        .or_insert_with(|| {
                            batches.push(SourceTextBatch {
                                source_language_code: source_code.clone(),
                                texts: Vec::new(),
                            });
                            batches.len() - 1
                        });
                    batches[batch_index].texts.push(text);
                }
            }
        }
    }

    let nothing_reason = if batches.is_empty() && passthrough_texts.is_empty() {
        Some(match (detected_same_as_target > 0, undetected_texts > 0) {
            (true, false) => NothingReason::AlreadyTargetLanguage,
            (false, true) => NothingReason::CouldNotDetect,
            _ => NothingReason::NoTranslatableText,
        })
    } else {
        None
    };

    BatchTextRoutingPlan {
        passthrough_texts,
        batches,
        nothing_reason,
    }
}

pub fn translate_mixed_texts_in_snapshot(
    engine: &mut BergamotEngine,
    snapshot: &CatalogSnapshot,
    inputs: &[String],
    forced_source_code: Option<&str>,
    target_code: &str,
    available_language_codes: &[String],
) -> Result<MixedTextTranslationResult, String> {
    let routing_plan = plan_batch_text_translation(
        inputs,
        forced_source_code,
        target_code,
        available_language_codes,
    );

    if routing_plan.batches.is_empty() && routing_plan.passthrough_texts.is_empty() {
        return Ok(MixedTextTranslationResult {
            translations: Vec::new(),
            nothing_reason: routing_plan.nothing_reason,
        });
    }

    let mut translations = routing_plan
        .passthrough_texts
        .into_iter()
        .map(|text| TextTranslation {
            source_text: text.clone(),
            translated_text: text,
        })
        .collect::<Vec<_>>();

    for batch in routing_plan.batches {
        let Some(plan) = resolve_translation_plan_in_snapshot(
            snapshot,
            &batch.source_language_code,
            target_code,
        ) else {
            continue;
        };
        let batch_results = execute_translation_plan(engine, &plan, &batch.texts)?;
        translations.extend(
            batch.texts
                .into_iter()
                .zip(batch_results)
                .map(|(source_text, translated_text)| TextTranslation {
                    source_text,
                    translated_text,
                }),
        );
    }

    Ok(MixedTextTranslationResult {
        translations,
        nothing_reason: None,
    })
}

#[cfg(test)]
mod tests {
    use super::{NothingReason, plan_batch_text_translation};

    #[test]
    fn keeps_passthrough_inputs() {
        let inputs = vec!["123".to_string(), " -- ".to_string()];
        let plan = plan_batch_text_translation(&inputs, None, "en", &["en".to_string()]);
        assert_eq!(plan.passthrough_texts, inputs);
        assert!(plan.batches.is_empty());
        assert!(plan.nothing_reason.is_none());
    }

    #[test]
    fn reports_no_translatable_text() {
        let inputs = vec!["".to_string()];
        let plan = plan_batch_text_translation(&inputs, None, "en", &["en".to_string()]);
        assert!(plan.passthrough_texts.is_empty());
        assert!(plan.batches.is_empty());
        assert_eq!(plan.nothing_reason, Some(NothingReason::NoTranslatableText));
    }
}
