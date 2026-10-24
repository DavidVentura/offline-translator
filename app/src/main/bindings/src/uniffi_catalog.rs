use std::num::NonZeroU32;
use std::sync::Arc;

/// Master toggle for per-frame outer timing logs emitted to logcat
/// target `planar_timing` from the JNI per-frame fast path. Pair with
/// `translator::planar_tracker::PER_FRAME_TIMING_LOG` (separate
/// crate) to silence the corresponding `guided: ...` / `brute: ...`
/// lines.
#[allow(dead_code)]
#[cfg(feature = "planar-tracker")]
pub(crate) const PER_FRAME_TIMING_LOG: bool = false;

use thiserror::Error;
use translator::{
    CatalogSnapshot, Feature, FsPackInstallChecker, TranslatorError, TranslatorErrorKind,
    TranslatorSession, language_rows_in_snapshot,
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
    /// User-initiated cancel. The Kotlin side should treat this as a
    /// silent stop, not as a translation failure.
    #[error("cancelled")]
    Cancelled,
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
    /// PDF-only: emitted once after inventory, before any pass starts.
    /// Lets the UI render three labelled progress lines (text / images /
    /// raster pages) with their image/raster totals up-front, instead of
    /// one bar that resets when each phase ends. The text bar is driven by
    /// `TranslatingText`, the image bar by `TranslatingImages`, the raster
    /// bar by `TranslatingRasterPages`.
    /// `raster_pages` is an upper bound; the raster pass refines it once the
    /// image pass has narrowed the actual set, by reporting the smaller
    /// `total` in its ticks.
    PdfPlan {
        text_pages: u32,
        image_xobjects: u32,
        raster_pages: u32,
    },
    /// Text-translation progress as a smooth completion fraction in
    /// `[0.0, 1.0]` (source-length weighted). Used for every text path
    /// (txt/odt/epub and the PDF text pass) — the per-paragraph/page counts it
    /// replaced were interpolated estimates, not real positions, so a fraction
    /// is the honest representation.
    TranslatingText { fraction: f32 },
    /// Image-XObject OCR+translation pass: real, in-order item counts.
    TranslatingImages { current: u32, total: u32 },
    /// Page-raster overlay OCR+translation pass: real, in-order item counts.
    TranslatingRasterPages { current: u32, total: u32 },
    Writing,
}

/// Caller-chosen layout for `.txt` translation. `wrap` of `0` (or absent)
/// means "do not re-wrap"; any positive value is the output column width.
/// Ignored for non-txt documents.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum TxtLayout {
    Preserve,
    Reflow { wrap: Option<u32> },
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum PdfImages {
    Skip,
    Translate { max_image_size: u32 },
}

impl From<PdfImages> for translator::document::PdfImages {
    fn from(value: PdfImages) -> Self {
        match value {
            PdfImages::Skip => Self::Skip,
            PdfImages::Translate { max_image_size } => Self::Translate { max_image_size },
        }
    }
}

impl From<TxtLayout> for translator::txt::TxtLayout {
    fn from(value: TxtLayout) -> Self {
        match value {
            TxtLayout::Preserve => Self::Preserve,
            TxtLayout::Reflow { wrap } => Self::Reflow {
                wrap: wrap.and_then(NonZeroU32::new),
            },
        }
    }
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

/// One on-device ONNX→MNN migration step. Paths are relative to the catalog's
/// `base_dir` (the same one passed to `CatalogHandle.open`). When `needs_convert`
/// is false the `.mnn` already exists and only the stray `.onnx` needs dropping.
/// The actual conversion runs in the separate converter native lib; deletion +
/// snapshot refresh go through `discard_migration`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MigrationJobRecord {
    pub onnx_path: String,
    pub mnn_path: String,
    pub quant_bits: i32,
    pub onnx_bytes: u64,
    pub mnn_bytes: u64,
    pub feature: String,
    pub needs_convert: bool,
}

impl From<translator::catalog::MigrationJob> for MigrationJobRecord {
    fn from(job: translator::catalog::MigrationJob) -> Self {
        Self {
            onnx_path: job.entry.onnx,
            mnn_path: job.entry.mnn,
            quant_bits: job.entry.quant_bits,
            onnx_bytes: job.entry.onnx_bytes,
            mnn_bytes: job.entry.mnn_bytes,
            feature: job.entry.feature,
            needs_convert: matches!(job.action, translator::catalog::MigrationAction::Convert),
        }
    }
}

impl From<MigrationJobRecord> for translator::catalog::MigrationJob {
    fn from(record: MigrationJobRecord) -> Self {
        translator::catalog::MigrationJob {
            entry: translator::catalog::MigrationEntry {
                onnx: record.onnx_path,
                mnn: record.mnn_path,
                quant_bits: record.quant_bits,
                onnx_bytes: record.onnx_bytes,
                mnn_bytes: record.mnn_bytes,
                feature: record.feature,
            },
            action: if record.needs_convert {
                translator::catalog::MigrationAction::Convert
            } else {
                translator::catalog::MigrationAction::CleanupOnly
            },
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


/// Rasterize the translated overlay onto `prepared`. Returns the new RGBA
/// buffer (same dimensions as `prepared.width` x `prepared.height`).
///
/// `language` is a BCP-47 hint for font selection (CJK regional variants);
/// `min_font_size_px` floors the fit-loop's shrink (caller should pass the
/// equivalent of the old Kotlin `minTextSize`, typically 8.0).
#[cfg(feature = "image-render")]
#[uniffi::export]
fn plan_zip_extraction(
    extract_to: String,
    marker: Option<translator::InstallMarker>,
    entry_names: Vec<String>,
) -> Result<translator::ZipExtractionPlan, CatalogError> {
    translator::plan_zip_extraction(&extract_to, marker.as_ref(), &entry_names)
        .map_err(|reason| CatalogError::Other { reason })
}

#[uniffi::export]
fn render_translated_overlay(
    prepared: translator::PreparedImageOverlay,
    language: String,
    min_font_size_px: f32,
) -> Result<translator::image_render::RenderedOverlay, CatalogError> {
    use translator::image_render::{RenderOptions, render_overlay};
    let opts = RenderOptions {
        language,
        min_font_size_px,
    };
    let provider = crate::android_font_provider::AndroidFontProvider;
    render_overlay(&prepared, &provider, &opts).map_err(|e| CatalogError::Other {
        reason: e.to_string(),
    })
}

#[derive(uniffi::Record)]
pub struct RetranslateResult {
    pub plan: translator::PreparedImageOverlay,
    pub words: Vec<translator::ocr::PositionedWord>,
}

/// Owns a copy of a still image's pixels rust-side so detection, OCR, and overlay rendering
/// run as separate FFI calls without re-marshalling the (multi-MB) image between them. The
/// source pixels are read-only after construction; `render_into` composes the translated
/// overlay into a *caller-provided* buffer, leaving the source intact (re-renderable).
#[derive(uniffi::Object)]
pub struct OcrImage {
    source: Vec<u8>,
    width: u32,
    height: u32,
    // Display-orient RGB + detector downscale, built once and shared by `detect` + `ocr` so the
    // staged pass doesn't rebuild it (`None` until the first detect/ocr).
    #[cfg(feature = "ppocr")]
    still: std::sync::Mutex<Option<Arc<translator::live_frame::StillImage>>>,
    // Erased background produced by `ocr`, kept rust-side so `render_into` draws onto it
    // without the erased image crossing the FFI boundary.
    erased: std::sync::Mutex<Option<Vec<u8>>>,
}

impl OcrImage {
    #[cfg(feature = "ppocr")]
    fn ensure_still(
        &self,
        catalog: &CatalogHandle,
        max_image_size: u32,
    ) -> Result<Arc<translator::live_frame::StillImage>, CatalogError> {
        let mut guard = self.still.lock().expect("ocr image still lock");
        if let Some(still) = guard.as_ref() {
            return Ok(still.clone());
        }
        let still = Arc::new(
            catalog
                .session
                .build_still_image(&self.source, self.width, self.height, max_image_size)
                .map_err(CatalogError::from)?,
        );
        *guard = Some(still.clone());
        Ok(still)
    }
}

#[uniffi::export]
impl OcrImage {
    /// Copy the pixels at `pixels_addr` (a locked `ARGB_8888` bitmap, `width*height*4` bytes,
    /// from [`NativeBitmap.lockPixels`]) into an owned buffer. The caller may unlock immediately.
    #[uniffi::constructor]
    fn from_pixels(pixels_addr: u64, width: u32, height: u32) -> Arc<Self> {
        let len = (width as usize) * (height as usize) * 4;
        let source = unsafe { std::slice::from_raw_parts(pixels_addr as *const u8, len) }.to_vec();
        Arc::new(OcrImage {
            source,
            width,
            height,
            #[cfg(feature = "ppocr")]
            still: std::sync::Mutex::new(None),
            erased: std::sync::Mutex::new(None),
        })
    }

    #[cfg(feature = "ppocr")]
    fn detect(
        &self,
        catalog: Arc<CatalogHandle>,
        max_image_size: u32,
    ) -> Result<Vec<translator::DetectedTextBox>, CatalogError> {
        let still = self.ensure_still(&catalog, max_image_size)?;
        catalog
            .session
            .detect_boxes_from_still(&still, self.width, self.height)
            .map_err(CatalogError::from)
    }

    #[cfg(feature = "ppocr")]
    #[allow(clippy::too_many_arguments)]
    fn ocr(
        &self,
        catalog: Arc<CatalogHandle>,
        max_image_size: u32,
        source_selection: translator::OcrSourceSelection,
        target_code: String,
        min_confidence: u32,
        reading_order: Option<translator::ReadingOrder>,
        background_mode: translator::BackgroundMode,
        detection: Option<Vec<translator::DetectedTextBox>>,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        let still = self.ensure_still(&catalog, max_image_size)?;
        let mut overlay = catalog
            .session
            .translate_from_still(
                &still,
                &self.source,
                self.width,
                self.height,
                source_selection,
                &target_code,
                min_confidence,
                reading_order,
                background_mode,
                detection,
            )
            .map_err(CatalogError::from)?;
        // Stash the erased background (move, no clone) and hand back metadata only.
        *self.erased.lock().expect("ocr image erased lock") =
            Some(std::mem::take(&mut overlay.rgba_bytes));
        Ok(overlay)
    }

    #[cfg(feature = "image-render")]
    fn render_into(
        &self,
        plan: translator::PreparedImageOverlay,
        language: String,
        min_font_size_px: f32,
        dst_addr: u64,
    ) -> Result<Vec<translator::ocr::PositionedWord>, CatalogError> {
        use translator::image_render::{RenderOptions, render_overlay};
        let erased = self
            .erased
            .lock()
            .expect("ocr image erased lock")
            .clone()
            .ok_or_else(|| CatalogError::Other {
                reason: "render_into called before ocr".to_string(),
            })?;
        let mut prepared = plan;
        prepared.rgba_bytes = erased;
        let opts = RenderOptions {
            language,
            min_font_size_px,
        };
        let provider = crate::android_font_provider::AndroidFontProvider;
        let rendered = render_overlay(&prepared, &provider, &opts).map_err(|e| {
            CatalogError::Other {
                reason: e.to_string(),
            }
        })?;
        let len = (self.width as usize) * (self.height as usize) * 4;
        let dst = unsafe { std::slice::from_raw_parts_mut(dst_addr as *mut u8, len) };
        dst.copy_from_slice(&rendered.rgba_bytes);
        Ok(rendered.translated_words)
    }

    /// Re-translate the cached OCR result to a new language and compose it into `dst`, reusing
    /// the cached erased background — no re-OCR, and the erased image never crosses the FFI.
    #[cfg(feature = "image-render")]
    fn retranslate_into(
        &self,
        catalog: Arc<CatalogHandle>,
        plan: translator::PreparedImageOverlay,
        source_code: String,
        target_code: String,
        min_font_size_px: f32,
        dst_addr: u64,
    ) -> Result<RetranslateResult, CatalogError> {
        use translator::image_render::{RenderOptions, render_overlay};
        let erased = self
            .erased
            .lock()
            .expect("ocr image erased lock")
            .clone()
            .ok_or_else(|| CatalogError::Other {
                reason: "retranslate_into called before ocr".to_string(),
            })?;
        let mut prepared = plan;
        prepared.rgba_bytes = erased;
        let mut retranslated = catalog
            .session
            .retranslate_prepared_overlay(prepared, &source_code, &target_code)
            .map_err(CatalogError::from)?;
        let opts = RenderOptions {
            language: target_code,
            min_font_size_px,
        };
        let provider = crate::android_font_provider::AndroidFontProvider;
        let rendered = render_overlay(&retranslated, &provider, &opts).map_err(|e| {
            CatalogError::Other {
                reason: e.to_string(),
            }
        })?;
        let len = (self.width as usize) * (self.height as usize) * 4;
        let dst = unsafe { std::slice::from_raw_parts_mut(dst_addr as *mut u8, len) };
        dst.copy_from_slice(&rendered.rgba_bytes);
        retranslated.rgba_bytes = Vec::new();
        Ok(RetranslateResult {
            plan: retranslated,
            words: rendered.translated_words,
        })
    }
}

impl From<translator::document::DocumentProgress> for DocumentProgressEvent {
    fn from(progress: translator::document::DocumentProgress) -> Self {
        use translator::document::DocumentProgress as P;
        match progress {
            P::Preparing => Self::Preparing,
            P::PdfPlan {
                text_pages,
                image_xobjects,
                raster_pages,
            } => Self::PdfPlan {
                text_pages,
                image_xobjects,
                raster_pages,
            },
            P::TranslatingText { fraction } => Self::TranslatingText { fraction },
            P::TranslatingImages { current, total } => Self::TranslatingImages { current, total },
            P::TranslatingRasterPages { current, total } => {
                Self::TranslatingRasterPages { current, total }
            }
            P::Writing => Self::Writing,
        }
    }
}

impl From<translator::document::DocumentError> for CatalogError {
    fn from(error: translator::document::DocumentError) -> Self {
        match error {
            translator::document::DocumentError::Cancelled => Self::Cancelled,
            translator::document::DocumentError::Other(reason) => Self::Other { reason },
        }
    }
}

#[derive(uniffi::Object)]
pub struct CatalogHandle {
    pub(crate) session: Arc<TranslatorSession>,
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
        crate::init_logging();
        let checker = FsPackInstallChecker::new(&base_dir);
        let session =
            TranslatorSession::open(&bundled_json, disk_json.as_deref(), base_dir, &checker)
                .map_err(|_| CatalogOpenError::ParseFailed)?;
        Ok(Arc::new(CatalogHandle {
            session: Arc::new(session),
        }))
    }

    fn replace_catalog(
        &self,
        bundled_json: String,
        disk_json: Option<String>,
        base_dir: String,
    ) -> Result<(), CatalogOpenError> {
        self.session
            .replace_catalog(&bundled_json, disk_json.as_deref(), base_dir)
            .map_err(|_| CatalogOpenError::ParseFailed)
    }

    fn refresh(&self) {
        self.session.refresh_snapshot();
    }

    fn apply_delete_plan(&self, plan: translator::DeletePlan) {
        self.session.apply_delete_plan(&plan);
    }

    fn base_dir(&self) -> String {
        self.snapshot().base_dir.clone()
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

    /// ONNX→MNN conversions needed to migrate this install to the MNN-only
    /// runtime (only entries whose `.onnx` is present on disk). The caller runs
    /// the `needs_convert` jobs through the converter native lib, then passes the
    /// finished jobs (plus any cleanup-only / discarded ones) to
    /// `discard_migration` to drop the `.onnx` and refresh.
    fn plan_migration(&self) -> Vec<MigrationJobRecord> {
        self.session
            .plan_migration()
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Delete the source `.onnx` of each job and refresh the catalog snapshot.
    /// Use after a successful conversion, for cleanup-only jobs, or when the user
    /// opts to drop models instead of migrating them.
    fn discard_migration(&self, jobs: Vec<MigrationJobRecord>) {
        let jobs: Vec<translator::catalog::MigrationJob> =
            jobs.into_iter().map(Into::into).collect();
        self.session.discard_migration(&jobs);
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

    fn installed_ocr_engines(&self, language_code: String) -> Vec<String> {
        translator::installed_ocr_engines_for_language(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn available_ocr_engines(&self, language_code: String) -> Vec<String> {
        translator::available_ocr_engines_for_language(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn plan_ocr_engine_download(
        &self,
        language_code: String,
        engine: String,
    ) -> Option<translator::DownloadPlan> {
        translator::plan_ocr_engine_download(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
            &engine,
        )
    }

    fn plan_ocr_engine_downloads(
        &self,
        language_codes: Vec<String>,
        engine: String,
    ) -> translator::DownloadPlan {
        let language_codes = language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        translator::plan_ocr_engine_downloads(&self.snapshot(), &language_codes, &engine)
    }

    fn plan_ocr_engine_upgrades(
        &self,
        language_codes: Vec<String>,
        engine: String,
    ) -> translator::DownloadPlan {
        let language_codes = language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        translator::plan_ocr_engine_upgrades(&self.snapshot(), &language_codes, &engine)
    }

    fn plan_translation_upgrades(&self, language_codes: Vec<String>) -> translator::DownloadPlan {
        let language_codes = language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        translator::plan_translation_upgrades(&self.snapshot(), &language_codes)
    }

    fn translation_upgrade_language_codes(&self) -> Vec<String> {
        translator::translation_upgrade_language_codes(&self.snapshot())
            .into_iter()
            .map(|language_code| language_code.code)
            .collect()
    }

    fn plan_tts_upgrades(&self, language_codes: Vec<String>) -> translator::DownloadPlan {
        let language_codes = language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        translator::plan_tts_upgrades(&self.snapshot(), &language_codes)
    }

    fn tts_upgrade_language_codes(&self) -> Vec<String> {
        translator::tts_upgrade_language_codes(&self.snapshot())
            .into_iter()
            .map(|language_code| language_code.code)
            .collect()
    }

    fn plan_repair(&self) -> translator::DownloadPlan {
        translator::plan_repair(&self.snapshot())
    }

    fn ocr_engine_ready(&self) -> bool {
        translator::ocr_engine_ready(&self.snapshot())
    }

    fn tts_sample_text(&self, language_code: String) -> Option<String> {
        self.snapshot()
            .catalog
            .tts_sample_text(&translator::LanguageCode::from(language_code))
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        translator::tts_voice_picker_regions(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn installed_tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        translator::installed_tts_voice_picker_regions(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
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

    fn translate_text_with_alternatives(
        &self,
        from_code: String,
        to_code: String,
        text: String,
    ) -> Result<translator::TranslationWithAlternatives, CatalogError> {
        self.session
            .translate_text_with_alternatives(&from_code, &to_code, &text)
            .map_err(CatalogError::from)
    }

    fn steer(
        &self,
        from_code: String,
        to_code: String,
        source: String,
        forced_prefix: String,
    ) -> Result<translator::TranslationWithAlternatives, CatalogError> {
        self.session
            .steer(&from_code, &to_code, &source, &forced_prefix)
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

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        max_image_size: u32,
        source_selection: translator::OcrSourceSelection,
        target_code: String,
        min_confidence: u32,
        reading_order: Option<translator::ReadingOrder>,
        background_mode: translator::BackgroundMode,
        detection: Option<Vec<translator::DetectedTextBox>>,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        #[cfg(feature = "ppocr")]
        {
            return self
                .session
                .translate_image_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    max_image_size,
                    source_selection,
                    &target_code,
                    min_confidence,
                    reading_order,
                    background_mode,
                    detection,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "ppocr"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                max_image_size,
                source_selection,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
                detection,
            );
            Err(CatalogError::Other {
                reason: "OCR feature disabled".to_string(),
            })
        }
    }

    fn detect_image_boxes(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        max_image_size: u32,
    ) -> Result<Vec<translator::DetectedTextBox>, CatalogError> {
        #[cfg(feature = "ppocr")]
        {
            return self
                .session
                .detect_image_boxes(&rgba_bytes, width, height, max_image_size)
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "ppocr"))]
        {
            let _ = (rgba_bytes, width, height, max_image_size);
            Err(CatalogError::Other {
                reason: "OCR feature disabled".to_string(),
            })
        }
    }

    fn retranslate_image_plan(
        &self,
        prepared: translator::PreparedImageOverlay,
        source_code: String,
        target_code: String,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        self.session
            .retranslate_prepared_overlay(prepared, &source_code, &target_code)
            .map_err(CatalogError::from)
    }

    /// Request cancellation of an in-flight `translate_document_path*` call.
    /// Safe to call from another thread (e.g. a UI "cancel" tap) while a
    /// document translation is running — slimt's worker pool observes it and
    /// stops within ~one batch, and the call returns `CatalogError::Cancelled`.
    /// A no-op if nothing is translating; the next document translation clears
    /// the flag at its start.
    fn cancel_ongoing_work(&self) {
        self.session.cancel_ongoing_work();
    }

    fn translate_document_path_with_progress(
        &self,
        input_path: String,
        output_path: String,
        forced_source_code: Option<String>,
        target_code: String,
        pdf_images: PdfImages,
        txt_layout: TxtLayout,
        progress: Arc<dyn DocumentProgressSink>,
    ) -> Result<String, CatalogError> {
        let options = translator::document::DocumentOptions {
            forced_source_code: forced_source_code.as_deref(),
            target_code: &target_code,
            pdf_images: pdf_images.into(),
            txt_layout: txt_layout.into(),
            fonts: &crate::android_font_provider::AndroidFontProvider,
        };
        translator::document::translate_document_path(
            &self.session,
            &input_path,
            &output_path,
            &options,
            &|event| progress.on_progress(event.into()),
            &|| progress.is_cancelled(),
        )?;
        Ok(output_path)
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

    fn download_task_installed(&self, task: translator::DownloadTask) -> bool {
        self.session.download_task_installed(&task)
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

    fn prepare_delete_tts_pack(&self, pack_id: String) -> translator::DeletePlan {
        self.session.prepare_delete_tts_pack(&pack_id)
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
                .inspect_err(|err| {
                    log::error!("listing TTS voices for `{language_code}` failed: {err}")
                })
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
        pack_id: Option<String>,
        urls_and_hashtags: translator::UrlsAndHashtags,
    ) -> Result<Vec<translator::SpeechChunk>, CatalogError> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .plan_speech_chunks(&language_code, &text, pack_id.as_deref(), urls_and_hashtags)
                .map_err(CatalogError::from);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, pack_id, urls_and_hashtags);
            Err(CatalogError::Other {
                reason: "tts feature disabled".to_string(),
            })
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: String,
        text: String,
        speech_speed: f32,
        voice_name: Option<String>,
        is_phonemes: bool,
        pack_id: Option<String>,
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
                    pack_id.as_deref(),
                )
                .map_err(CatalogError::from);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (
                language_code,
                text,
                speech_speed,
                voice_name,
                is_phonemes,
                pack_id,
            );
            Err(CatalogError::Other {
                reason: "tts feature disabled".to_string(),
            })
        }
    }

    fn installed_tts_voices(&self, language_code: String) -> Vec<translator::InstalledTtsPack> {
        #[cfg(feature = "tts")]
        {
            return self.session.installed_tts_voices(&language_code);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn detect_document_quad(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
    ) -> Result<Option<translator::doc_align::DocumentDetection>, CatalogError> {
        #[cfg(feature = "doc-align")]
        {
            return self
                .session
                .detect_document_quad(&rgba_bytes, width, height)
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "doc-align"))]
        {
            let _ = (rgba_bytes, width, height);
            Err(CatalogError::Other {
                reason: "doc-align feature disabled".to_string(),
            })
        }
    }

    fn warp_document_rgba(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        quad: translator::doc_align::DocumentQuad,
        out_width: Option<u32>,
        out_height: Option<u32>,
        postprocess: bool,
    ) -> Result<translator::doc_align::WarpedImageRgba, CatalogError> {
        #[cfg(feature = "doc-align")]
        {
            return self
                .session
                .warp_document_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    &quad,
                    out_width,
                    out_height,
                    postprocess,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "doc-align"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                quad,
                out_width,
                out_height,
                postprocess,
            );
            Err(CatalogError::Other {
                reason: "doc-align feature disabled".to_string(),
            })
        }
    }
}

// =========================================================================
// Planar-surface OCR pipeline (uniffi wrapper).
//
// All the orchestration logic — tracker step, smoothed-H, composite,
// async acquire/refresh dispatch — lives in
// `translator::live_tracker_pipeline::LiveTrackerPipeline`. This
// uniffi `LivePlanarTracker` is a thin shim that holds an `Arc` to it
// + forwards a handful of uniffi-facing methods (set config, reset,
// telemetry getter). The hot per-frame entry is the JNI extern fn
// `Java_..._LivePipelineJni_processFrameGl`, which dereferences the
// raw address from `raw_address_for_jni`, GPU-renders canonical luma
// from the camera's external-OES texture into a fresh `LiveFrame`,
// runs `LiveTrackerPipeline::process_frame` with an
// `ExternalPresentTarget`, and on acquire reads back full-res RGBA
// from the same texture.
// =========================================================================

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlanarTrackerState {
    Idle,
    Acquiring,
    Locked,
    Lost,
}

#[cfg(feature = "planar-tracker")]
impl From<translator::live_tracker_pipeline::PlanarTrackerState> for PlanarTrackerState {
    fn from(s: translator::live_tracker_pipeline::PlanarTrackerState) -> Self {
        use translator::live_tracker_pipeline::PlanarTrackerState as S;
        match s {
            S::Idle => Self::Idle,
            S::Acquiring => Self::Acquiring,
            S::Locked => Self::Locked,
            S::Lost => Self::Lost,
        }
    }
}

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PipelineTargetMode {
    /// Normal operation: tracker step + composite + async acquire/refresh.
    Active,
    /// Suppress: every frame bumps generation + clears engine + smoothed H.
    /// Used during camera AF scans so any in-flight acquire bails at its
    /// next gen-check and the stable-window restarts on every frame.
    Suppressed,
}

#[cfg(feature = "planar-tracker")]
impl From<PipelineTargetMode> for translator::live_tracker_pipeline::TargetMode {
    fn from(m: PipelineTargetMode) -> Self {
        match m {
            PipelineTargetMode::Active => Self::Active,
            PipelineTargetMode::Suppressed => Self::Suppressed,
        }
    }
}

/// Surfaced to Kotlin for the on-screen debug tracker pill. Drained
/// from the pipeline (take-and-clear) on each poll, so a subsequent
/// poll without a new acquire returns `None`.
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record, Clone, Debug)]
pub struct AcquireTelemetryRecord {
    pub anchor_id: u64,
    pub detected_count: u32,
    pub rec_ok_count: u32,
    pub rec_empty_count: u32,
    pub cache_hits: u32,
    pub rec_called_count: u32,
    pub total_ms: f64,
    pub canceled: bool,
    pub error: Option<String>,
    pub is_refresh: bool,
}

#[cfg(feature = "planar-tracker")]
impl From<translator::live_tracker_pipeline::AcquireTelemetry> for AcquireTelemetryRecord {
    fn from(t: translator::live_tracker_pipeline::AcquireTelemetry) -> Self {
        AcquireTelemetryRecord {
            anchor_id: t.anchor_id,
            detected_count: t.detected_count,
            rec_ok_count: t.rec_ok_count,
            rec_empty_count: t.rec_empty_count,
            cache_hits: t.cache_hits,
            rec_called_count: t.rec_called_count,
            total_ms: t.total_ms,
            canceled: t.canceled,
            error: t.error,
            is_refresh: t.is_refresh,
        }
    }
}

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Object)]
pub struct LivePlanarTracker {
    pub(crate) pipeline: Arc<translator::live_tracker_pipeline::LiveTrackerPipeline>,
}

#[cfg(feature = "planar-tracker")]
#[uniffi::export]
impl LivePlanarTracker {
    #[uniffi::constructor]
    fn new(catalog: Arc<CatalogHandle>) -> Arc<Self> {
        let session = catalog.session_arc();
        let provider: Arc<dyn translator::font_provider::FontProvider + Send + Sync> =
            Arc::new(crate::android_font_provider::AndroidFontProvider);
        Arc::new(Self {
            pipeline: translator::live_tracker_pipeline::LiveTrackerPipeline::new(
                session, provider,
            ),
        })
    }

    /// Address of the underlying pipeline on the Rust heap. Used by
    /// the JNI per-frame fast path to cast back to
    /// `&LiveTrackerPipeline` without a uniffi marshalling hop.
    fn raw_address_for_jni(&self) -> u64 {
        Arc::as_ptr(&self.pipeline) as u64
    }

    fn set_languages(&self, from_code: String, to_code: String, is_auto_source: bool) {
        self.pipeline
            .set_languages(&from_code, &to_code, is_auto_source);
    }

    fn set_target_mode(&self, mode: PipelineTargetMode) {
        self.pipeline.set_target_mode(mode.into());
    }

    /// Bump generation, clear engine + smoothed H + session state.
    /// Tap-to-focus / language change / session teardown call this.
    fn reset(&self) {
        self.pipeline.reset();
    }

    /// Drop all resident overlay items without resetting the tracker
    /// engine. The compositor will draw camera-only frames after this
    /// until a fresh acquire completes.
    fn clear_overlay(&self) {
        self.pipeline.clear_overlay();
    }

    /// Pull (and clear) the most recent async-job telemetry. Returns
    /// `None` when no new acquire/refresh has finished since the last
    /// poll. Used by Kotlin to update the debug status pill.
    fn last_acquire_telemetry(&self) -> Option<AcquireTelemetryRecord> {
        self.pipeline.last_acquire_telemetry().map(Into::into)
    }
}

/// No-tracker screen-translate pipeline (flat fronto-parallel capture →
/// identity placement). Thin uniffi shim over
/// `translator::live_screen::LiveScreenPipeline`, mirroring
/// [`LivePlanarTracker`]: the per-frame fast path grabs the raw address from
/// `raw_address_for_jni` and calls `processScreenFrameGl`.
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Object)]
pub struct LiveScreenTracker {
    pub(crate) pipeline: Arc<translator::live_screen::LiveScreenPipeline>,
}

#[cfg(feature = "planar-tracker")]
#[uniffi::export]
impl LiveScreenTracker {
    #[uniffi::constructor]
    fn new(catalog: Arc<CatalogHandle>) -> Arc<Self> {
        let session = catalog.session_arc();
        let provider: Arc<dyn translator::font_provider::FontProvider + Send + Sync> =
            Arc::new(crate::android_font_provider::AndroidFontProvider);
        Arc::new(Self {
            pipeline: translator::live_screen::LiveScreenPipeline::new(session, provider),
        })
    }

    fn raw_address_for_jni(&self) -> u64 {
        Arc::as_ptr(&self.pipeline) as u64
    }

    fn set_languages(&self, from_code: String, to_code: String, is_auto_source: bool) {
        self.pipeline
            .set_languages(&from_code, &to_code, is_auto_source);
    }

    fn reset(&self) {
        self.pipeline.reset();
    }

    fn clear_overlay(&self) {
        self.pipeline.clear_overlay();
    }

    /// Restrict OCR + the motion monitor to a normalized `[0,1]` region, or pass
    /// `None` to translate the whole screen.
    fn set_region(&self, region: Option<ScreenRegion>) {
        self.pipeline.set_region(region.map(Into::into));
    }
}

/// A capture region in normalized `[0,1]` coordinates (origin top-left).
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct ScreenRegion {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

#[cfg(feature = "planar-tracker")]
impl From<ScreenRegion> for translator::live_screen::NormRect {
    fn from(r: ScreenRegion) -> Self {
        translator::live_screen::NormRect {
            left: r.left,
            top: r.top,
            right: r.right,
            bottom: r.bottom,
        }
    }
}

#[cfg(feature = "ppocr")]
impl CatalogHandle {
    pub(crate) fn session_arc(&self) -> Arc<translator::TranslatorSession> {
        Arc::clone(&self.session)
    }
}
