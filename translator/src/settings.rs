pub const DEFAULT_CATALOG_INDEX_URL: &str = "https://offline-translator.davidv.dev/index.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum BackgroundMode {
    WhiteOnBlack,
    BlackOnWhite,
    #[default]
    AutoDetect,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AppSettings {
    pub default_target_language_code: String,
    pub default_source_language_code: Option<String>,
    pub catalog_index_url: String,
    pub background_mode: BackgroundMode,
    pub min_confidence: i32,
    pub max_image_size: i32,
    pub enable_output_transliteration: bool,
    pub add_spaces_for_japanese_transliteration: bool,
    pub tts_playback_speed: f32,
    pub tts_voice_overrides: Vec<(String, String)>,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            default_target_language_code: "en".to_string(),
            default_source_language_code: None,
            catalog_index_url: DEFAULT_CATALOG_INDEX_URL.to_string(),
            background_mode: BackgroundMode::AutoDetect,
            min_confidence: 75,
            max_image_size: 1500,
            enable_output_transliteration: true,
            add_spaces_for_japanese_transliteration: true,
            tts_playback_speed: 1.0,
            tts_voice_overrides: Vec::new(),
        }
    }
}
