use tesseract::plumbing::{BoundingRect, PageIteratorLevel};
pub use tesseract::PageSegMode;
use tesseract::Tesseract as TesseractEngine;

fn log_debug(message: impl AsRef<str>) {
    let _ = message.as_ref();
}

fn log_info(message: impl AsRef<str>) {
    let _ = message.as_ref();
}

fn log_error(message: impl AsRef<str>) {
    eprintln!("{}", message.as_ref());
}

#[derive(Debug, Clone)]
pub struct DetectedWord {
    pub text: String,
    pub bounding_rect: BoundingRect,
    pub confidence: f32,
    pub is_at_beginning_of_para: bool,
    pub end_para: bool,
    pub end_line: bool,
}

pub struct TesseractWrapper {
    engine: Option<TesseractEngine>,
}

impl TesseractWrapper {
    pub fn new(
        datapath: Option<&str>,
        language: Option<&str>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        log_info(format!(
            "TesseractWrapper::new called with datapath: {:?}, language: {:?}",
            datapath, language
        ));

        if let Some(tessdata_path) = datapath {
            log_info(format!("Checking tessdata directory: {}", tessdata_path));

            match std::fs::read_dir(tessdata_path) {
                Ok(entries) => {
                    log_info("tessdata directory contents:");
                    for entry in entries.flatten() {
                        log_info(format!("  - {}", entry.file_name().to_string_lossy()));
                    }
                }
                Err(e) => {
                    log_error(format!(
                        "Failed to read tessdata directory {}: {:?}",
                        tessdata_path, e
                    ));
                }
            }

            if let Some(lang) = language {
                for language_code in lang.split('+') {
                    let traineddata_file = format!("{}/{}.traineddata", tessdata_path, language_code);
                    match std::fs::metadata(&traineddata_file) {
                        Ok(metadata) => {
                            log_info(format!(
                                "Found {}.traineddata, size: {} bytes",
                                language_code,
                                metadata.len()
                            ));
                        }
                        Err(e) => {
                            log_error(format!(
                                "Missing or inaccessible {}.traineddata: {:?}",
                                language_code, e
                            ));
                        }
                    }
                }
            }
        }

        match TesseractEngine::new(datapath, language) {
            Ok(engine) => {
                log_info("TesseractEngine created successfully");
                Ok(TesseractWrapper {
                    engine: Some(engine),
                })
            }
            Err(e) => {
                log_error(format!("TesseractEngine::new failed: {:?}", e));
                Err(Box::new(e))
            }
        }
    }

    pub fn set_frame(
        &mut self,
        frame_data: &[u8],
        width: i32,
        height: i32,
        bytes_per_pixel: i32,
        bytes_per_line: i32,
    ) -> Result<(), Box<dyn std::error::Error>> {
        log_debug(format!(
            "set_frame called: {}x{}, bpp={}, bpl={}, data_len={}",
            width,
            height,
            bytes_per_pixel,
            bytes_per_line,
            frame_data.len()
        ));

        if let Some(engine) = self.engine.take() {
            match engine.set_frame(frame_data, width, height, bytes_per_pixel, bytes_per_line) {
                Ok(new_engine) => {
                    log_debug("set_frame completed successfully");
                    self.engine = Some(new_engine);
                }
                Err(e) => {
                    log_error(format!("set_frame failed: {:?}", e));
                    return Err(Box::new(e));
                }
            }
        } else {
            log_error("set_frame called but engine is None");
        }
        Ok(())
    }

    pub fn set_page_seg_mode(&mut self, mode: PageSegMode) {
        if let Some(ref mut engine) = self.engine {
            engine.set_page_seg_mode(mode);
        }
    }

    pub fn get_word_boxes(&mut self) -> Result<Vec<DetectedWord>, Box<dyn std::error::Error>> {
        log_debug("get_word_boxes called");
        let mut words = Vec::new();

        if let Some(engine) = self.engine.take() {
            log_debug("Starting OCR recognition...");
            let mut recognized_engine = match engine.recognize() {
                Ok(engine) => {
                    log_debug("OCR recognition completed successfully");
                    engine
                }
                Err(e) => {
                    log_error(format!("OCR recognition failed: {:?}", e));
                    return Err(Box::new(e));
                }
            };

            if let Some(mut result_iter) = recognized_engine.get_iterator() {
                log_debug("Got result iterator, processing words...");
                let mut word_iter = result_iter.words();
                let mut word_count = 0;

                while let Some(word) = word_iter.next() {
                    if let (Some(text), Some(bounding_rect)) = (word.text, word.bounding_rect) {
                        words.push(DetectedWord {
                            text: text.as_ref().to_string_lossy().into_owned(),
                            bounding_rect,
                            confidence: word.confidence,
                            is_at_beginning_of_para: word_iter
                                .is_at_beginning_of(PageIteratorLevel::RIL_PARA),
                            end_line: word_iter.is_at_final_element(
                                PageIteratorLevel::RIL_TEXTLINE,
                                PageIteratorLevel::RIL_WORD,
                            ),
                            end_para: word_iter.is_at_final_element(
                                PageIteratorLevel::RIL_PARA,
                                PageIteratorLevel::RIL_WORD,
                            ),
                        });
                        word_count += 1;
                    }
                }
                log_debug(format!("Processed {} words", word_count));
            } else {
                log_error("Failed to get result iterator from recognized engine");
            }

            self.engine = Some(recognized_engine);
        } else {
            log_error("get_word_boxes called but engine is None");
        }

        log_debug(format!("get_word_boxes returning {} words", words.len()));
        Ok(words)
    }
}
