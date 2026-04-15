use icu::locale::Locale;
use icu_experimental::transliterate::Transliterator;

fn make_transliterator(source_script: &str) -> Option<Transliterator> {
    let locale_str = format!("und-Latn-t-und-{}", source_script.to_lowercase());
    let locale: Locale = locale_str.parse().ok()?;
    Transliterator::try_new(&locale).ok()
}

pub fn transliterate(text: &str, source_script: &str) -> Option<String> {
    match source_script {
        "Jpan" => {
            let kana = make_transliterator("Kana")?;
            let hira = make_transliterator("Hira")?;
            let result = kana.transliterate(text.to_string());
            Some(hira.transliterate(result))
        }
        _ => {
            let t = make_transliterator(source_script)?;
            Some(t.transliterate(text.to_string()))
        }
    }
}

pub fn transliterate_with_policy(
    text: &str,
    language_code: &str,
    source_script: &str,
    target_script: &str,
    japanese_preprocessed: Option<&str>,
) -> Option<String> {
    if source_script == target_script {
        return None;
    }

    let input = match language_code {
        "ja" => japanese_preprocessed.unwrap_or(text),
        _ => text,
    };

    transliterate(input, source_script)
}

#[cfg(feature = "android")]
mod jni_bridge {
    use super::*;
    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::{jlong, jstring};

    #[cfg(feature = "mucab")]
    fn preprocess_japanese(
        text: &str,
        dict_ptr: jlong,
        japanese_spaced: bool,
    ) -> Option<String> {
        if dict_ptr == 0 {
            return None;
        }

        let dict = unsafe { &mut *(dict_ptr as *mut mucab::Dictionary) };
        Some(mucab::transliterate(text, dict, japanese_spaced))
    }

    #[cfg(not(feature = "mucab"))]
    fn preprocess_japanese(
        _text: &str,
        _dict_ptr: jlong,
        _japanese_spaced: bool,
    ) -> Option<String> {
        None
    }

    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn Java_dev_davidv_translator_TransliterateBinding_nativeTransliterateWithPolicy(
        mut env: JNIEnv,
        _: JClass,
        java_text: JString,
        java_language_code: JString,
        java_source_script: JString,
        java_target_script: JString,
        japanese_dict_ptr: jlong,
        japanese_spaced: bool,
    ) -> jstring {
        let text: String = match env.get_string(&java_text) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let language_code: String = match env.get_string(&java_language_code) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let source_script: String = match env.get_string(&java_source_script) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let target_script: String = match env.get_string(&java_target_script) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let japanese_preprocessed = if language_code == "ja" {
            preprocess_japanese(&text, japanese_dict_ptr, japanese_spaced)
        } else {
            None
        };

        match transliterate_with_policy(
            &text,
            &language_code,
            &source_script,
            &target_script,
            japanese_preprocessed.as_deref(),
        ) {
            Some(result) => match env.new_string(&result) {
                Ok(jstring) => jstring.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn translit(script: &str, text: &str) -> String {
        transliterate(text, script).unwrap()
    }

    #[test]
    fn test_cyrillic() {
        assert_eq!(translit("Cyrl", "Привет мир"), "Privet mir");
    }

    #[test]
    fn test_arabic() {
        assert_eq!(translit("Arab", "مرحبا"), "mrḥbạ");
    }

    #[test]
    fn test_greek() {
        assert_eq!(translit("Grek", "Αθήνα"), "Athḗna");
    }

    #[test]
    fn test_devanagari() {
        assert_eq!(translit("Deva", "नमस्ते"), "namastē");
    }

    #[test]
    fn test_hangul() {
        assert_eq!(translit("Hang", "안녕하세요"), "annyeonghaseyo");
    }

    #[test]
    fn test_hebrew() {
        assert_eq!(translit("Hebr", "שלום"), "şlwm");
    }

    #[test]
    fn test_bengali() {
        assert_eq!(translit("Beng", "নমস্কার"), "namaskāra");
    }

    #[test]
    fn test_tamil() {
        assert_eq!(translit("Taml", "வணக்கம்"), "vaṇakkam");
    }

    #[test]
    fn test_telugu() {
        assert_eq!(translit("Telu", "నమస్కారం"), "namaskāraṁ");
    }

    #[test]
    fn test_han_simplified() {
        assert_eq!(translit("Hans", "你好世界"), "nǐ hǎo shì jiè");
    }

    #[test]
    fn test_han_traditional() {
        assert_eq!(translit("Hant", "你好世界"), "nǐ hǎo shì jiè");
    }

    #[test]
    fn test_japanese_hiragana() {
        assert_eq!(translit("Jpan", "こんにちは"), "kon'nichiha");
    }

    #[test]
    fn test_japanese_katakana() {
        assert_eq!(translit("Jpan", "カタカナ"), "katakana");
    }

    #[test]
    fn test_japanese_mixed_kana() {
        let result = translit("Jpan", "ひらがなカタカナ");
        assert!(result.contains("hiragana"));
        assert!(result.contains("katakana"));
    }

    #[test]
    fn test_jpan_preserves_kanji() {
        // After mucab, some kanji may remain unconverted.
        // Verify they pass through unchanged.
        assert_eq!(translit("Jpan", "東京 の ひと"), "東京 no hito");
    }

    #[test]
    fn test_jpan_simulated_mucab_output() {
        // mucab converts kanji→hiragana and adds spaces.
        // Simulate: "東京タワー" → mucab → "とうきょう タワー"
        // Then ICU should produce: "toukyou tawā"
        assert_eq!(translit("Jpan", "とうきょう タワー"), "toukyou tawā");
    }

    #[test]
    fn test_latin_is_none() {
        assert!(transliterate("Hello", "Latn").is_none());
    }

    #[test]
    fn test_policy_skips_same_script() {
        assert!(transliterate_with_policy("Hello", "en", "Latn", "Latn", None).is_none());
    }

    #[test]
    fn test_policy_uses_japanese_preprocessed_text() {
        assert_eq!(
            transliterate_with_policy("東京タワー", "ja", "Jpan", "Latn", Some("とうきょう タワー"))
                .unwrap(),
            "toukyou tawā"
        );
    }
}
