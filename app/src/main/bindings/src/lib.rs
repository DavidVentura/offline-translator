uniffi::setup_scaffolding!();

#[cfg(feature = "android")]
pub mod bergamot;
#[cfg(feature = "android")]
pub mod logging;
#[cfg(feature = "mucab")]
pub mod mucab;
#[cfg(feature = "tts")]
pub mod speech;
#[cfg(feature = "dictionary")]
pub mod tarkka;
#[cfg(feature = "tesseract")]
pub mod tesseract;
pub mod transliterate;
pub mod uniffi_catalog;
