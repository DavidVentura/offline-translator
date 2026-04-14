use cld2::{Format, Hints, Reliable, detect_language_ext};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DetectionResult {
    pub language: String,
    pub is_reliable: bool,
    pub confidence: i32,
}

pub fn detect_language(
    text: &str,
    hint: Option<&str>,
) -> Option<DetectionResult> {
    let hints = Hints {
        content_language: hint,
        ..Default::default()
    };
    let detected = detect_language_ext(text, Format::Text, &hints);
    let language = detected.language?.0.to_string();
    let is_reliable = detected.reliability == Reliable;
    let confidence = detected.scores.first().map(|score| score.percent as i32).unwrap_or(0);

    Some(DetectionResult {
        language,
        is_reliable,
        confidence,
    })
}
