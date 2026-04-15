use tesseract::plumbing::{BoundingRect, PageIteratorLevel};
use tesseract::{PageSegMode, Tesseract as TesseractEngine};

#[cfg(feature = "android")]
extern crate jni;
#[cfg(feature = "android")]
use self::jni::JNIEnv;
#[cfg(feature = "android")]
use self::jni::objects::{JByteArray, JClass, JObject, JString};
#[cfg(feature = "android")]
use self::jni::sys::{jbyteArray, jint, jlong, jobject};

#[cfg(feature = "android")]
use crate::logging::{android_log_debug, android_log_error, android_log_info};

#[cfg(not(feature = "android"))]
macro_rules! android_log_debug {
    ($value:expr) => {
        let _ = &$value;
    };
    ($fmt:literal $(, $arg:expr)*) => {
        let _ = format!($fmt $(, $arg)*);
    };
}

#[cfg(not(feature = "android"))]
macro_rules! android_log_error {
    ($value:expr) => {
        let _ = &$value;
    };
    ($fmt:literal $(, $arg:expr)*) => {
        let _ = format!($fmt $(, $arg)*);
    };
}

#[cfg(not(feature = "android"))]
macro_rules! android_log_info {
    ($value:expr) => {
        let _ = &$value;
    };
    ($fmt:literal $(, $arg:expr)*) => {
        let _ = format!($fmt $(, $arg)*);
    };
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
        android_log_info!(format!(
            "TesseractWrapper::new called with datapath: {:?}, language: {:?}",
            datapath, language
        ));

        // Check tessdata directory if datapath is provided
        if let Some(tessdata_path) = datapath {
            android_log_info!(format!("Checking tessdata directory: {}", tessdata_path));

            match std::fs::read_dir(&tessdata_path) {
                Ok(entries) => {
                    android_log_info!("tessdata directory contents:");
                    for entry in entries {
                        if let Ok(entry) = entry {
                            let file_name = entry.file_name();
                            android_log_info!(format!("  - {}", file_name.to_string_lossy()));
                        }
                    }
                }
                Err(e) => {
                    android_log_error!(format!(
                        "Failed to read tessdata directory {}: {:?}",
                        tessdata_path, e
                    ));
                }
            }

            // Check for specific language files if language is provided
            if let Some(lang) = language {
                let languages: Vec<&str> = lang.split('+').collect();
                for language_code in languages {
                    let traineddata_file =
                        format!("{}/{}.traineddata", tessdata_path, language_code);
                    match std::fs::metadata(&traineddata_file) {
                        Ok(metadata) => {
                            android_log_info!(format!(
                                "Found {}.traineddata, size: {} bytes",
                                language_code,
                                metadata.len()
                            ));
                        }
                        Err(e) => {
                            android_log_error!(format!(
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
                android_log_info!("TesseractEngine created successfully");
                Ok(TesseractWrapper {
                    engine: Some(engine),
                })
            }
            Err(e) => {
                android_log_error!(format!("TesseractEngine::new failed: {:?}", e));
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
        android_log_debug!(format!(
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
                    android_log_debug!("set_frame completed successfully");
                    self.engine = Some(new_engine);
                }
                Err(e) => {
                    android_log_error!(format!("set_frame failed: {:?}", e));
                    return Err(Box::new(e));
                }
            }
        } else {
            android_log_error!("set_frame called but engine is None");
        }
        Ok(())
    }

    pub fn set_page_seg_mode(&mut self, mode: PageSegMode) {
        if let Some(ref mut engine) = self.engine {
            engine.set_page_seg_mode(mode);
        }
    }

    pub fn get_word_boxes(&mut self) -> Result<Vec<DetectedWord>, Box<dyn std::error::Error>> {
        android_log_debug!("get_word_boxes called");
        let mut words = Vec::new();

        if let Some(engine) = self.engine.take() {
            android_log_debug!("Starting OCR recognition...");
            let mut recognized_engine = match engine.recognize() {
                Ok(engine) => {
                    android_log_debug!("OCR recognition completed successfully");
                    engine
                }
                Err(e) => {
                    android_log_error!(format!("OCR recognition failed: {:?}", e));
                    return Err(Box::new(e));
                }
            };

            if let Some(mut result_iter) = recognized_engine.get_iterator() {
                android_log_debug!("Got result iterator, processing words...");
                let mut word_iter = result_iter.words();
                let mut word_count = 0;

                while let Some(word) = word_iter.next() {
                    if let (Some(text), Some(bounding_rect)) = (word.text, word.bounding_rect) {
                        let text_str = text.as_ref().to_string_lossy().into_owned();
                        let confidence = word.confidence;
                        let is_at_beginning_of_para =
                            word_iter.is_at_beginning_of(PageIteratorLevel::RIL_PARA);
                        let end_line = word_iter.is_at_final_element(
                            PageIteratorLevel::RIL_TEXTLINE,
                            PageIteratorLevel::RIL_WORD,
                        );
                        let end_para = word_iter.is_at_final_element(
                            PageIteratorLevel::RIL_PARA,
                            PageIteratorLevel::RIL_WORD,
                        );

                        words.push(DetectedWord {
                            text: text_str,
                            bounding_rect,
                            confidence,
                            is_at_beginning_of_para,
                            end_line,
                            end_para,
                        });
                        word_count += 1;
                    }
                }
                android_log_debug!(format!("Processed {} words", word_count));
            } else {
                android_log_error!("Failed to get result iterator from recognized engine");
            }

            self.engine = Some(recognized_engine);
        } else {
            android_log_error!("get_word_boxes called but engine is None");
        }

        android_log_debug!(format!("get_word_boxes returning {} words", words.len()));
        Ok(words)
    }
}

#[cfg(feature = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_TesseractBinding_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    java_datapath: JString,
    java_language: JString,
) -> jlong {
    android_log_info!("nativeCreate called");

    let datapath: String = match env.get_string(&java_datapath) {
        Ok(path) => {
            let p: String = path.into();
            android_log_info!(format!("Received datapath: '{}'", p));
            p
        }
        Err(e) => {
            android_log_error!(format!("Failed to get datapath string: {:?}", e));
            return 0;
        }
    };

    let language: String = match env.get_string(&java_language) {
        Ok(lang) => {
            let l: String = lang.into();
            android_log_info!(format!("Received language: '{}'", l));
            l
        }
        Err(e) => {
            android_log_error!(format!("Failed to get language string: {:?}", e));
            return 0;
        }
    };

    let datapath_opt = if datapath.is_empty() {
        android_log_info!("Datapath is empty, using None");
        None
    } else {
        android_log_info!(format!("Using datapath: '{}'", datapath));
        Some(datapath.as_str())
    };

    let language_opt = if language.is_empty() {
        android_log_info!("Language is empty, using None");
        None
    } else {
        android_log_info!(format!("Using language: '{}'", language));
        Some(language.as_str())
    };

    match TesseractWrapper::new(datapath_opt, language_opt) {
        Ok(wrapper) => {
            android_log_info!(
                "TesseractWrapper created successfully, boxing and returning pointer"
            );
            let boxed_wrapper = Box::new(wrapper);
            let ptr = Box::into_raw(boxed_wrapper) as jlong;
            android_log_info!(format!("Returning pointer: {}", ptr));
            ptr
        }
        Err(e) => {
            android_log_error!(format!("TesseractWrapper::new failed: {:?}", e));
            0
        }
    }
}

#[cfg(feature = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_TesseractBinding_nativeSetFrame(
    env: JNIEnv,
    _: JClass,
    wrapper_ptr: jlong,
    frame_data: jbyteArray,
    width: jint,
    height: jint,
    bytes_per_pixel: jint,
    bytes_per_line: jint,
) -> jint {
    android_log_debug!(format!(
        "nativeSetFrame called: ptr={}, dims={}x{}, bpp={}, bpl={}",
        wrapper_ptr, width, height, bytes_per_pixel, bytes_per_line
    ));

    if wrapper_ptr == 0 {
        android_log_error!("nativeSetFrame: wrapper_ptr is 0");
        return 0;
    }

    unsafe {
        let wrapper = &mut *(wrapper_ptr as *mut TesseractWrapper);
        let byte_array = JByteArray::from_raw(frame_data);

        let data_len = env.get_array_length(&byte_array).unwrap_or(0) as usize;
        android_log_debug!(format!("Frame data length: {}", data_len));
        let mut data_vec = vec![0i8; data_len];

        match env.get_byte_array_region(&byte_array, 0, &mut data_vec) {
            Ok(_) => {
                let unsigned_data: Vec<u8> = data_vec.into_iter().map(|b| b as u8).collect();
                match wrapper.set_frame(
                    &unsigned_data,
                    width,
                    height,
                    bytes_per_pixel,
                    bytes_per_line,
                ) {
                    Ok(_) => {
                        android_log_debug!("nativeSetFrame completed successfully");
                        1
                    }
                    Err(e) => {
                        android_log_error!(format!("nativeSetFrame failed: {:?}", e));
                        0
                    }
                }
            }
            Err(e) => {
                android_log_error!(format!("Failed to get byte array region: {:?}", e));
                0
            }
        }
    }
}

#[cfg(feature = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_TesseractBinding_nativeSetPageSegMode(
    _env: JNIEnv,
    _: JClass,
    wrapper_ptr: jlong,
    mode: jint,
) {
    if wrapper_ptr == 0 {
        return;
    }

    unsafe {
        let wrapper = &mut *(wrapper_ptr as *mut TesseractWrapper);

        let page_seg_mode = match mode {
            0 => PageSegMode::PsmOsdOnly,
            1 => PageSegMode::PsmAutoOsd,
            2 => PageSegMode::PsmAutoOnly,
            3 => PageSegMode::PsmAuto,
            4 => PageSegMode::PsmSingleColumn,
            5 => PageSegMode::PsmSingleBlockVertText,
            6 => PageSegMode::PsmSingleBlock,
            7 => PageSegMode::PsmSingleLine,
            8 => PageSegMode::PsmSingleWord,
            9 => PageSegMode::PsmCircleWord,
            10 => PageSegMode::PsmSingleChar,
            11 => PageSegMode::PsmSparseText,
            12 => PageSegMode::PsmSparseTextOsd,
            13 => PageSegMode::PsmRawLine,
            _ => PageSegMode::PsmAuto,
        };

        wrapper.set_page_seg_mode(page_seg_mode);
    }
}

#[cfg(feature = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_TesseractBinding_nativeGetWordBoxes(
    mut env: JNIEnv,
    _: JClass,
    wrapper_ptr: jlong,
) -> jobject {
    android_log_debug!(format!(
        "nativeGetWordBoxes called with ptr={}",
        wrapper_ptr
    ));

    if wrapper_ptr == 0 {
        android_log_error!("nativeGetWordBoxes: wrapper_ptr is 0");
        return std::ptr::null_mut();
    }

    let wrapper = unsafe { &mut *(wrapper_ptr as *mut TesseractWrapper) };
    match wrapper.get_word_boxes() {
        Ok(words) => {
            android_log_debug!(format!("Got {} words from tesseract", words.len()));
            let words_list = match env.new_object("java/util/ArrayList", "()V", &[]) {
                Ok(list) => list,
                Err(e) => {
                    android_log_error!(format!("Failed to create ArrayList: {:?}", e));
                    return std::ptr::null_mut();
                }
            };

            for (i, word) in words.iter().enumerate() {
                let word_obj = create_detected_word_jobject(&mut env, word);
                if word_obj.is_null() {
                    android_log_error!(format!("Failed to create word object for word {}", i));
                    return std::ptr::null_mut();
                }

                match env.call_method(
                    &words_list,
                    "add",
                    "(Ljava/lang/Object;)Z",
                    &[(&unsafe { JObject::from_raw(word_obj) }).into()],
                ) {
                    Ok(_) => {}
                    Err(e) => {
                        android_log_error!(format!("Failed to add word {} to list: {:?}", i, e));
                    }
                }
            }

            android_log_debug!("nativeGetWordBoxes completed successfully");
            words_list.into_raw()
        }
        Err(e) => {
            android_log_error!(format!("get_word_boxes failed: {:?}", e));
            std::ptr::null_mut()
        }
    }
}

#[cfg(feature = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_translator_TesseractBinding_nativeDestroy(
    _env: JNIEnv,
    _: JClass,
    wrapper_ptr: jlong,
) {
    if wrapper_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(wrapper_ptr as *mut TesseractWrapper);
        }
    }
}

#[cfg(feature = "android")]
fn create_detected_word_jobject(env: &mut JNIEnv, word: &DetectedWord) -> jobject {
    let text_string = env.new_string(&word.text).unwrap();
    let left = word.bounding_rect.left;
    let top = word.bounding_rect.top;
    let right = word.bounding_rect.right;
    let bottom = word.bounding_rect.bottom;
    let confidence = word.confidence;
    let is_at_beginning_of_para = word.is_at_beginning_of_para as u8;
    let end_para = word.end_para as u8;
    let end_line = word.end_line as u8;

    match env.new_object(
        "dev/davidv/translator/DetectedWord",
        "(Ljava/lang/String;IIIIFZZZ)V",
        &[
            (&text_string).into(),
            left.into(),
            top.into(),
            right.into(),
            bottom.into(),
            confidence.into(),
            is_at_beginning_of_para.into(),
            end_line.into(),
            end_para.into(),
        ],
    ) {
        Ok(obj) => obj.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
