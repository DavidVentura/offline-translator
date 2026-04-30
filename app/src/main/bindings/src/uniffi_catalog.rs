use std::sync::Arc;
use std::{fs, path::Path};

use thiserror::Error;
use translator::{
    CatalogSnapshot, Feature, FsPackInstallChecker, TranslatorError, TranslatorErrorKind,
    TranslatorSession, language_rows_in_snapshot, sample_overlay_colors,
};

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogOpenError {
    #[error("failed to parse any catalog")]
    ParseFailed,
}

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogError {
    #[error("{reason}")]
    MissingAsset { reason: String },
    #[error("{reason}")]
    Other { reason: String },
}

impl From<TranslatorError> for CatalogError {
    fn from(err: TranslatorError) -> Self {
        match err.kind {
            TranslatorErrorKind::MissingAsset => Self::MissingAsset {
                reason: err.message,
            },
            _ => Self::Other {
                reason: err.message,
            },
        }
    }
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum DocumentProgressEvent {
    Preparing,
    Translating {
        current: u32,
        total: u32,
        unit: String,
    },
    Writing,
}

#[uniffi::export(with_foreign)]
pub trait DocumentProgressSink: Send + Sync {
    fn on_progress(&self, event: DocumentProgressEvent);
    fn is_cancelled(&self) -> bool;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryGlossRecord {
    pub gloss_lines: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionarySenseRecord {
    pub pos: String,
    pub glosses: Vec<DictionaryGlossRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordEntryRecord {
    pub senses: Vec<DictionarySenseRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordRecord {
    pub word: String,
    pub tag: i32,
    pub entries: Vec<DictionaryWordEntryRecord>,
    pub sounds: Option<String>,
    pub hyphenations: Vec<String>,
    pub redirects: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogFileRecord {
    pub name: String,
    pub size_bytes: u64,
    pub install_path: String,
    pub url: String,
}

impl From<translator::catalog::AssetFileV2> for CatalogFileRecord {
    fn from(file: translator::catalog::AssetFileV2) -> Self {
        Self {
            name: file.name,
            size_bytes: file.size_bytes,
            install_path: file.install_path,
            url: file.url,
        }
    }
}

#[cfg(feature = "dictionary")]
fn map_dictionary_word(word: translator::tarkka::WordWithTaggedEntries) -> DictionaryWordRecord {
    DictionaryWordRecord {
        word: word.word,
        tag: word.tag as i32,
        entries: word
            .entries
            .into_iter()
            .map(|entry| DictionaryWordEntryRecord {
                senses: entry
                    .senses
                    .into_iter()
                    .map(|sense| DictionarySenseRecord {
                        pos: sense.pos.to_string(),
                        glosses: sense
                            .glosses
                            .into_iter()
                            .map(|gloss| DictionaryGlossRecord {
                                gloss_lines: gloss.gloss_lines,
                            })
                            .collect(),
                    })
                    .collect(),
            })
            .collect(),
        sounds: word.sounds,
        hyphenations: word.hyphenations,
        redirects: word.redirects,
    }
}

#[uniffi::export]
fn sample_overlay_colors_rgba(
    rgba_bytes: Vec<u8>,
    width: u32,
    height: u32,
    bounds: translator::Rect,
    background_mode: translator::BackgroundMode,
    word_rects: Option<Vec<translator::Rect>>,
) -> Option<translator::OverlayColors> {
    sample_overlay_colors(
        &rgba_bytes,
        width,
        height,
        bounds,
        background_mode,
        word_rects.as_deref(),
    )
    .ok()
}

fn document_extension(path: &str) -> String {
    Path::new(path)
        .extension()
        .and_then(|ext| ext.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn map_available_language_codes(codes: Vec<String>) -> Vec<translator::LanguageCode> {
    codes
        .into_iter()
        .map(translator::LanguageCode::from)
        .collect()
}

fn translate_document_path_impl(
    session: &TranslatorSession,
    input_path: String,
    output_path: String,
    forced_source_code: Option<String>,
    target_code: String,
    available_language_codes: Vec<String>,
    mut on_progress: impl FnMut(DocumentProgressEvent),
    is_cancelled: impl Fn() -> bool,
) -> Result<String, CatalogError> {
    let check_cancelled = || {
        if is_cancelled() {
            Err(CatalogError::Other {
                reason: "document translation cancelled".to_string(),
            })
        } else {
            Ok(())
        }
    };
    check_cancelled()?;
    on_progress(DocumentProgressEvent::Preparing);
    check_cancelled()?;
    let extension = document_extension(&input_path);
    let available = map_available_language_codes(available_language_codes);
    let input_bytes = fs::read(&input_path).map_err(|error| CatalogError::Other {
        reason: format!("failed to read document: {error}"),
    })?;
    check_cancelled()?;

    let output_bytes = match extension.as_str() {
        "txt" => {
            let source_code = forced_source_code
                .as_deref()
                .ok_or_else(|| CatalogError::Other {
                    reason: "source language is required for text documents".to_string(),
                })?;
            let text = String::from_utf8(input_bytes).map_err(|error| CatalogError::Other {
                reason: format!("text document is not UTF-8: {error}"),
            })?;
            on_progress(DocumentProgressEvent::Translating {
                current: 0,
                total: 1,
                unit: "block".to_string(),
            });
            check_cancelled()?;
            let translated = session
                .translate_text(source_code, &target_code, &text)
                .map_err(CatalogError::from)?;
            check_cancelled()?;
            on_progress(DocumentProgressEvent::Translating {
                current: 1,
                total: 1,
                unit: "block".to_string(),
            });
            check_cancelled()?;
            translated.into_bytes()
        }
        "odt" => {
            #[cfg(feature = "odt")]
            {
                translator::odt::translate_odt_with_progress(
                    session,
                    &input_bytes,
                    forced_source_code.as_deref(),
                    &target_code,
                    &available,
                    |progress| {
                        if is_cancelled() {
                            return Err(translator::odt::OdtTranslateError::Cancelled);
                        }
                        let translator::odt::OdtTranslateProgress::TranslatingBlock {
                            current,
                            total,
                        } = progress;
                        on_progress(DocumentProgressEvent::Translating {
                            current: current as u32,
                            total: total as u32,
                            unit: "block".to_string(),
                        });
                        if is_cancelled() {
                            Err(translator::odt::OdtTranslateError::Cancelled)
                        } else {
                            Ok(())
                        }
                    },
                )
                .map_err(|error| CatalogError::Other {
                    reason: format!("failed to translate ODT: {error}"),
                })?
            }
            #[cfg(not(feature = "odt"))]
            {
                let _ = (forced_source_code, target_code, available);
                return Err(CatalogError::Other {
                    reason: "odt feature disabled".to_string(),
                });
            }
        }
        "pdf" => {
            #[cfg(feature = "pdf")]
            {
                let translations = translator::pdf_translate::translate_pdf_with_progress(
                    session,
                    &input_bytes,
                    forced_source_code.as_deref(),
                    &target_code,
                    &available,
                    |progress| {
                        if is_cancelled() {
                            return Err(translator::pdf_translate::PdfTranslateError::Cancelled);
                        }
                        let translator::pdf_translate::PdfTranslateProgress::TranslatingPage {
                            current,
                            total,
                        } = progress;
                        on_progress(DocumentProgressEvent::Translating {
                            current: current as u32,
                            total: total as u32,
                            unit: "page".to_string(),
                        });
                        if is_cancelled() {
                            Err(translator::pdf_translate::PdfTranslateError::Cancelled)
                        } else {
                            Ok(())
                        }
                    },
                )
                .map_err(|error| CatalogError::Other {
                    reason: format!("failed to translate PDF: {error}"),
                })?;
                on_progress(DocumentProgressEvent::Writing);
                translator::pdf_write::write_translated_pdf(
                    &input_bytes,
                    &translations,
                    &translator::font_provider::NoFontProvider,
                )
                .map_err(|error| CatalogError::Other {
                    reason: format!("failed to write PDF: {error}"),
                })?
            }
            #[cfg(not(feature = "pdf"))]
            {
                let _ = (forced_source_code, target_code, available);
                return Err(CatalogError::Other {
                    reason: "pdf feature disabled".to_string(),
                });
            }
        }
        _ => {
            return Err(CatalogError::Other {
                reason: format!("unsupported document type: {extension}"),
            });
        }
    };

    check_cancelled()?;
    on_progress(DocumentProgressEvent::Writing);
    check_cancelled()?;
    fs::write(&output_path, output_bytes).map_err(|error| CatalogError::Other {
        reason: format!("failed to write translated document: {error}"),
    })?;
    Ok(output_path)
}

#[derive(uniffi::Object)]
pub struct CatalogHandle {
    session: TranslatorSession,
}

impl CatalogHandle {
    fn snapshot(&self) -> Arc<CatalogSnapshot> {
        self.session.snapshot()
    }
}

#[uniffi::export]
impl CatalogHandle {
    #[uniffi::constructor]
    fn open(
        bundled_json: String,
        disk_json: Option<String>,
        base_dir: String,
    ) -> Result<Arc<Self>, CatalogOpenError> {
        let checker = FsPackInstallChecker::new(&base_dir);
        let session =
            TranslatorSession::open(&bundled_json, disk_json.as_deref(), base_dir, &checker)
                .map_err(|_| CatalogOpenError::ParseFailed)?;
        Ok(Arc::new(CatalogHandle { session }))
    }

    fn format_version(&self) -> i32 {
        self.snapshot().catalog.format_version
    }

    fn generated_at(&self) -> i64 {
        self.snapshot().catalog.generated_at
    }

    fn dictionary_version(&self) -> i32 {
        self.snapshot().catalog.dictionary_version
    }

    fn language_rows(&self) -> Vec<translator::LanguageAvailabilityRow> {
        language_rows_in_snapshot(&self.snapshot())
    }

    fn dictionary_info(&self, dictionary_code: String) -> Option<translator::DictionaryInfo> {
        self.snapshot()
            .catalog
            .dictionary_info(&translator::DictionaryCode::from(dictionary_code))
    }

    fn support_files_by_kind(&self, support_kind: String) -> Vec<CatalogFileRecord> {
        self.snapshot()
            .catalog
            .support_files_by_kind(&support_kind)
            .into_iter()
            .map(Into::into)
            .collect()
    }

    fn lookup_dictionary(
        &self,
        language_code: String,
        word: String,
    ) -> Result<Option<DictionaryWordRecord>, CatalogError> {
        #[cfg(not(feature = "dictionary"))]
        {
            let _ = (language_code, word);
            return Ok(None);
        }

        #[cfg(feature = "dictionary")]
        {
            self.session
                .lookup_dictionary(&language_code, &word)
                .map(|opt| opt.map(map_dictionary_word))
                .map_err(CatalogError::from)
        }
    }

    fn has_tts_voices(&self, language_code: String) -> bool {
        self.snapshot()
            .catalog
            .has_tts_voices(&translator::LanguageCode::from(language_code))
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        self.snapshot()
            .catalog
            .tts_voice_picker_regions(&translator::LanguageCode::from(language_code))
    }

    fn can_swap_languages(&self, from_code: String, to_code: String) -> bool {
        self.snapshot().catalog.can_swap_languages(
            &translator::LanguageCode::from(from_code),
            &translator::LanguageCode::from(to_code),
        )
    }

    fn can_translate(&self, from_code: String, to_code: String) -> bool {
        self.snapshot().can_translate(
            &translator::LanguageCode::from(from_code),
            &translator::LanguageCode::from(to_code),
        )
    }

    fn warm_translation_models(&self, from_code: String, to_code: String) -> bool {
        self.session.warm(&from_code, &to_code).is_ok()
    }

    fn translate_text(
        &self,
        from_code: String,
        to_code: String,
        text: String,
    ) -> Result<String, CatalogError> {
        self.session
            .translate_text(&from_code, &to_code, &text)
            .map_err(CatalogError::from)
    }

    fn translate_html_fragments(
        &self,
        from_code: String,
        to_code: String,
        fragments: Vec<String>,
    ) -> Result<Vec<String>, CatalogError> {
        self.session
            .translate_html_fragments(&from_code, &to_code, &fragments)
            .map_err(CatalogError::from)
    }

    fn translate_mixed_texts(
        &self,
        inputs: Vec<String>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
    ) -> translator::MixedTextTranslationResult {
        let available = available_language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        self.session
            .translate_mixed_texts(
                &inputs,
                forced_source_code.as_deref(),
                &target_code,
                &available,
            )
            .unwrap_or_else(|_| translator::MixedTextTranslationResult::default())
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<translator::StructuredStyledFragment>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        screenshot: Option<translator::OverlayScreenshot>,
        background_mode: translator::BackgroundMode,
    ) -> translator::StructuredTranslationResult {
        let available = available_language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        self.session
            .translate_structured_fragments(
                &fragments,
                forced_source_code.as_deref(),
                &target_code,
                &available,
                screenshot.as_ref(),
                background_mode,
            )
            .unwrap_or_else(|error| translator::StructuredTranslationResult {
                blocks: Vec::new(),
                nothing_reason: None,
                error_message: Some(error.message),
            })
    }

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        source_code: String,
        target_code: String,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        #[cfg(feature = "tesseract")]
        {
            return self
                .session
                .translate_image_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    &source_code,
                    &target_code,
                    min_confidence,
                    reading_order,
                    background_mode,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "tesseract"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                source_code,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
            );
            Err(CatalogError::Other {
                reason: "tesseract feature disabled".to_string(),
            })
        }
    }

    fn translate_document_path(
        &self,
        input_path: String,
        output_path: String,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
    ) -> Result<String, CatalogError> {
        translate_document_path_impl(
            &self.session,
            input_path,
            output_path,
            forced_source_code,
            target_code,
            available_language_codes,
            |_| {},
            || false,
        )
    }

    fn translate_document_path_with_progress(
        &self,
        input_path: String,
        output_path: String,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        progress: Arc<dyn DocumentProgressSink>,
    ) -> Result<String, CatalogError> {
        translate_document_path_impl(
            &self.session,
            input_path,
            output_path,
            forced_source_code,
            target_code,
            available_language_codes,
            |event| progress.on_progress(event),
            || progress.is_cancelled(),
        )
    }

    fn plan_download(
        &self,
        language_code: String,
        feature: Feature,
        selected_tts_pack_id: Option<String>,
    ) -> Option<translator::DownloadPlan> {
        self.session
            .plan_download(&language_code, feature, selected_tts_pack_id.as_deref())
    }

    fn plan_support_download_by_kind(
        &self,
        support_kind: String,
    ) -> Option<translator::DownloadPlan> {
        self.session.plan_support_download_by_kind(&support_kind)
    }

    fn prepare_delete(&self, language_code: String, feature: Feature) -> translator::DeletePlan {
        self.session.prepare_delete(&language_code, feature)
    }

    fn prepare_delete_support_by_kind(&self, support_kind: String) -> translator::DeletePlan {
        self.session.prepare_delete_support_by_kind(&support_kind)
    }

    fn prepare_delete_superseded_tts(
        &self,
        language_code: String,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        self.session
            .prepare_delete_superseded_tts(&language_code, &selected_pack_id)
    }

    fn size_bytes(&self, language_code: String, feature: Feature) -> u64 {
        self.session.size_bytes(&language_code, feature)
    }

    fn support_size_bytes_by_kind(&self, support_kind: String) -> u64 {
        self.session.support_size_bytes_by_kind(&support_kind)
    }

    fn default_tts_pack_id(&self, language_code: String) -> Option<String> {
        self.snapshot()
            .catalog
            .default_tts_pack_id_for_language(&translator::LanguageCode::from(language_code))
    }

    fn available_tts_voices(&self, language_code: String) -> Vec<translator::TtsVoiceOption> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .available_tts_voices(&language_code)
                .unwrap_or_default();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn plan_speech_chunks(
        &self,
        language_code: String,
        text: String,
    ) -> Vec<translator::SpeechChunk> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .plan_speech_chunks(&language_code, &text)
                .unwrap_or_default();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text);
            Vec::new()
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: String,
        text: String,
        speech_speed: f32,
        voice_name: Option<String>,
        is_phonemes: bool,
    ) -> Result<translator::PcmAudio, CatalogError> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .synthesize_pcm(
                    &language_code,
                    &text,
                    speech_speed,
                    voice_name.as_deref(),
                    is_phonemes,
                )
                .map_err(CatalogError::from);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, speech_speed, voice_name, is_phonemes);
            Err(CatalogError::Other {
                reason: "tts feature disabled".to_string(),
            })
        }
    }
}
