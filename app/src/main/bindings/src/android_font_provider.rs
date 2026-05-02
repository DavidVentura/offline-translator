//! Android-native [`FontProvider`] backed by `/system/etc/fonts.xml`.
//!
//! Latin-script targets render correctly through the PDF writer's
//! Standard-14 Helvetica/Courier fallback (WinAnsi covers the latin
//! glyphs that show up in en/es/fr/de/...), so for those [`locate`]
//! returns `None` and we never touch system font lookup. Only non-Latin
//! scripts (Cyrillic, CJK, Arabic, Indic, ...) need a real font handle
//! so the writer can embed a TTF subset.
//!
//! Lookup parses `/system/etc/fonts.xml` (the same data Skia / minikin
//! consume internally) and resolves to files in `/system/fonts/`. The
//! whole provider works back to minSdk 21 — the API-version differences
//! only affect *how* we identify a script's family, not whether we can.
//!
//! Three layered search passes (all run on every API level):
//!
//! 1. `<family lang="...">` exact match. CJK families (`ja`, `ko`,
//!    `zh-Hans`, `zh-Hant`) have carried this attribute since API 21.
//!    Other scripts (`und-Arab`, `und-Hebr`, `und-Thai`, Indic, …)
//!    only got `lang=` in the API 28 fonts.xml refresh, so this pass
//!    misses them on older devices.
//! 2. Filename keyword match (`NotoSansArabic`, `NotoNaskh`, …). On
//!    API 21..27 the non-CJK fallback families have no `lang` and
//!    are only identifiable by their Noto filenames; this pass picks
//!    them up. Harmless on API 28+ since pass 1 already hit.
//! 3. Default `name="sans-serif"` family (Roboto). Covers Cyrillic and
//!    Greek on every release — Roboto has shipped both glyph blocks
//!    since Android 5.0, and no `und-Cyrl` / `und-Grek` family ever
//!    existed.
//!
//! Within the chosen family we pick the closest `(weight, italic)`
//! match.

use std::cell::RefCell;
use std::path::PathBuf;
use std::sync::OnceLock;

use markup5ever::buffer_queue::BufferQueue;
use translator::font_provider::{FontHandle, FontProvider, FontRequest};
use xml5ever::tendril::StrTendril;
use xml5ever::tokenizer::{EndTag, ProcessResult, StartTag, Token, TokenSink, XmlTokenizer};

const FONTS_XML_PATH: &str = "/system/etc/fonts.xml";
const SYSTEM_FONTS_DIR: &str = "/system/fonts";

#[derive(Debug, Clone)]
struct FontEntry {
    file_name: String,
    weight: u16,
    italic: bool,
    ttc_index: u32,
}

#[derive(Debug, Default, Clone)]
struct FamilyEntry {
    name: Option<String>,
    langs: Vec<String>,
    fonts: Vec<FontEntry>,
}

#[derive(Default)]
struct ParseState {
    families: Vec<FamilyEntry>,
    current_family: Option<FamilyEntry>,
    current_font: Option<FontEntry>,
    font_text_buf: String,
}

struct ParserSink {
    state: RefCell<ParseState>,
}

impl TokenSink for ParserSink {
    type Handle = ();

    fn process_token(&self, token: Token) -> ProcessResult<()> {
        let mut state = self.state.borrow_mut();
        match token {
            Token::Tag(tag) => {
                let local = &*tag.name.local;
                match tag.kind {
                    StartTag => {
                        if local == "family" {
                            let mut entry = FamilyEntry::default();
                            for attr in tag.attrs.iter() {
                                match &*attr.name.local {
                                    "name" => entry.name = Some(attr.value.to_string()),
                                    "lang" => {
                                        entry.langs = attr
                                            .value
                                            .split_whitespace()
                                            .map(str::to_string)
                                            .collect();
                                    }
                                    _ => {}
                                }
                            }
                            state.current_family = Some(entry);
                        } else if local == "font" && state.current_family.is_some() {
                            let mut font = FontEntry {
                                file_name: String::new(),
                                weight: 400,
                                italic: false,
                                ttc_index: 0,
                            };
                            for attr in tag.attrs.iter() {
                                match &*attr.name.local {
                                    "weight" => {
                                        font.weight = attr.value.parse().unwrap_or(400);
                                    }
                                    "style" => {
                                        font.italic = &*attr.value == "italic";
                                    }
                                    "index" => {
                                        font.ttc_index = attr.value.parse().unwrap_or(0);
                                    }
                                    _ => {}
                                }
                            }
                            state.current_font = Some(font);
                            state.font_text_buf.clear();
                        }
                    }
                    EndTag => {
                        if local == "font" {
                            if let Some(mut font) = state.current_font.take() {
                                font.file_name = state.font_text_buf.trim().to_string();
                                state.font_text_buf.clear();
                                if !font.file_name.is_empty()
                                    && let Some(family) = state.current_family.as_mut()
                                {
                                    family.fonts.push(font);
                                }
                            }
                        } else if local == "family"
                            && let Some(family) = state.current_family.take()
                            && !family.fonts.is_empty()
                        {
                            state.families.push(family);
                        }
                    }
                    _ => {}
                }
            }
            Token::Characters(s) => {
                if state.current_font.is_some() {
                    state.font_text_buf.push_str(&s);
                }
            }
            _ => {}
        }
        ProcessResult::Continue
    }
}

fn parse_fonts_xml(xml: &str) -> Vec<FamilyEntry> {
    let sink = ParserSink {
        state: RefCell::new(ParseState::default()),
    };
    let buffer = BufferQueue::default();
    buffer.push_back(StrTendril::from_slice(xml));
    let tok = XmlTokenizer::new(sink, Default::default());
    let _ = tok.feed(&buffer);
    tok.end();
    tok.sink.state.into_inner().families
}

fn families() -> &'static [FamilyEntry] {
    static CACHE: OnceLock<Vec<FamilyEntry>> = OnceLock::new();
    CACHE.get_or_init(|| match std::fs::read_to_string(FONTS_XML_PATH) {
        Ok(xml) => {
            let parsed = parse_fonts_xml(&xml);
            log::debug!(
                "[font] parsed {} families from {FONTS_XML_PATH}",
                parsed.len()
            );
            parsed
        }
        Err(e) => {
            log::warn!("[font] cannot read {FONTS_XML_PATH}: {e}");
            Vec::new()
        }
    })
}

/// Scripts the translator catalog actually has models for (per
/// `index.json`). Anything outside this set returns `None` from
/// [`non_latin_script`] and rides the Latin / Helvetica path. Adding a
/// new pack to the catalog with a non-Latin script means adding a
/// variant here, a code in [`non_latin_script`], an entry in
/// [`lang_tags`], and a fallback keyword in [`name_keywords`] — the
/// `script_table_covers_all_index_scripts` test catches misses.
#[derive(Clone, Copy)]
enum Script {
    Cyrillic,       // be, bg, ru, sr, uk
    Greek,          // el
    Arabic,         // ar, fa
    Hebrew,         // he
    Thai,           // th
    Devanagari,     // hi
    Bengali,        // bn
    Tamil,          // ta
    Telugu,         // te
    Kannada,        // kn
    Malayalam,      // ml
    Gujarati,       // gu
    Japanese,       // ja
    Korean,         // ko
    HanSimplified,  // zh
    HanTraditional, // zh_hant
}

fn non_latin_script(language: &str) -> Option<Script> {
    let normalized = language.replace('_', "-").to_ascii_lowercase();
    let mut parts = normalized.split('-');
    let primary = parts.next().unwrap_or(&normalized);
    let suffix = parts.next();
    Some(match primary {
        "ja" => Script::Japanese,
        "ko" => Script::Korean,
        "zh" => match suffix {
            Some("hant") | Some("tw") | Some("hk") | Some("mo") => Script::HanTraditional,
            _ => Script::HanSimplified,
        },
        "ru" | "uk" | "be" | "bg" | "sr" => Script::Cyrillic,
        "el" => Script::Greek,
        "ar" | "fa" => Script::Arabic,
        "he" => Script::Hebrew,
        "th" => Script::Thai,
        "hi" => Script::Devanagari,
        "bn" => Script::Bengali,
        "ta" => Script::Tamil,
        "te" => Script::Telugu,
        "kn" => Script::Kannada,
        "ml" => Script::Malayalam,
        "gu" => Script::Gujarati,
        _ => return None,
    })
}

fn lang_tags(script: Script) -> &'static [&'static str] {
    match script {
        Script::Cyrillic | Script::Greek => &[],
        Script::Japanese => &["ja"],
        Script::Korean => &["ko"],
        Script::HanSimplified => &["zh-Hans"],
        Script::HanTraditional => &["zh-Hant", "zh-Bopo"],
        Script::Arabic => &["und-Arab"],
        Script::Hebrew => &["und-Hebr"],
        Script::Thai => &["und-Thai"],
        Script::Devanagari => &["und-Deva"],
        Script::Bengali => &["und-Beng"],
        Script::Tamil => &["und-Taml"],
        Script::Telugu => &["und-Telu"],
        Script::Kannada => &["und-Knda"],
        Script::Malayalam => &["und-Mlym"],
        Script::Gujarati => &["und-Gujr"],
    }
}

/// Substrings to look for in font filenames when no `lang=` family is
/// found. This handles API 21..27 fonts.xml, where most fallback
/// families have no `lang` attribute and the only signal is the Noto
/// family encoded in the filename.
fn name_keywords(script: Script) -> &'static [&'static str] {
    match script {
        Script::Cyrillic | Script::Greek => &[],
        Script::Japanese => &["NotoSansCJK", "NotoSansJP", "DroidSansJapanese"],
        Script::Korean => &["NotoSansCJK", "NotoSansKR", "DroidSansKorean"],
        Script::HanSimplified => &["NotoSansSC", "NotoSansHans", "NotoSansCJK"],
        Script::HanTraditional => &["NotoSansTC", "NotoSansHant", "NotoSansCJK"],
        Script::Arabic => &["NotoNaskh", "NotoSansArabic", "DroidNaskh"],
        Script::Hebrew => &["NotoSansHebrew"],
        Script::Thai => &["NotoSansThai", "DroidSansThai"],
        Script::Devanagari => &["NotoSansDevanagari"],
        Script::Bengali => &["NotoSansBengali"],
        Script::Tamil => &["NotoSansTamil"],
        Script::Telugu => &["NotoSansTelugu"],
        Script::Kannada => &["NotoSansKannada"],
        Script::Malayalam => &["NotoSansMalayalam"],
        Script::Gujarati => &["NotoSansGujarati"],
    }
}

fn pick_font(family: &FamilyEntry, want_weight: u16, want_italic: bool) -> Option<&FontEntry> {
    if let Some(exact) = family
        .fonts
        .iter()
        .find(|f| f.weight == want_weight && f.italic == want_italic)
    {
        return Some(exact);
    }
    if let Some(matching_italic) = family
        .fonts
        .iter()
        .filter(|f| f.italic == want_italic)
        .min_by_key(|f| (f.weight as i32 - want_weight as i32).abs())
    {
        return Some(matching_italic);
    }
    family
        .fonts
        .iter()
        .min_by_key(|f| (f.weight as i32 - want_weight as i32).abs())
}

fn resolve(file_name: &str, ttc_index: u32) -> Option<(PathBuf, u32)> {
    let path = PathBuf::from(SYSTEM_FONTS_DIR).join(file_name);
    if !path.exists() {
        log::warn!("[font] missing system font: {}", path.display());
        return None;
    }
    Some((path, ttc_index))
}

fn locate_for(script: Script, bold: bool, italic: bool) -> Option<(PathBuf, u32)> {
    let want_weight: u16 = if bold { 700 } else { 400 };
    let families = families();

    for want in lang_tags(script) {
        for family in families {
            if !family.langs.iter().any(|l| l == want) {
                continue;
            }
            if let Some(font) = pick_font(family, want_weight, italic)
                && let Some(r) = resolve(&font.file_name, font.ttc_index)
            {
                return Some(r);
            }
        }
    }

    for keyword in name_keywords(script) {
        for family in families {
            if !family.fonts.iter().any(|f| f.file_name.contains(keyword)) {
                continue;
            }
            if let Some(font) = pick_font(family, want_weight, italic)
                && let Some(r) = resolve(&font.file_name, font.ttc_index)
            {
                return Some(r);
            }
        }
    }

    for family in families {
        if family.name.as_deref() != Some("sans-serif") {
            continue;
        }
        if let Some(font) = pick_font(family, want_weight, italic)
            && let Some(r) = resolve(&font.file_name, font.ttc_index)
        {
            return Some(r);
        }
    }

    None
}

pub struct AndroidFontProvider;

impl FontProvider for AndroidFontProvider {
    fn locate(&self, request: &FontRequest) -> Option<FontHandle> {
        let script = non_latin_script(&request.language)?;
        let (path, ttc_index) = locate_for(script, request.bold, request.italic)?;
        log::debug!(
            "[font] {} bold={} italic={} -> {} (ttc={})",
            request.language,
            request.bold,
            request.italic,
            path.display(),
            ttc_index
        );
        Some(FontHandle::new(path, ttc_index))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FONTS_XML_API28: &str = include_str!("../testdata/fonts_api28.xml");

    /// Snapshot of every language from `bucket/index.json` paired with
    /// its `meta.script` ISO 15924 code. Update both this list and the
    /// `non_latin_script` / `lang_tags` / `name_keywords` tables when
    /// new packs are added to the catalog. The two tests below assert
    /// that every entry here resolves correctly.
    const INDEX_LANGUAGES: &[(&str, &str)] = &[
        ("ar", "Arab"),
        ("az", "Latn"),
        ("be", "Cyrl"),
        ("bg", "Cyrl"),
        ("bn", "Beng"),
        ("bs", "Latn"),
        ("ca", "Latn"),
        ("cs", "Latn"),
        ("da", "Latn"),
        ("de", "Latn"),
        ("el", "Grek"),
        ("en", "Latn"),
        ("es", "Latn"),
        ("et", "Latn"),
        ("fa", "Arab"),
        ("fi", "Latn"),
        ("fr", "Latn"),
        ("gu", "Gujr"),
        ("he", "Hebr"),
        ("hi", "Deva"),
        ("hr", "Latn"),
        ("hu", "Latn"),
        ("id", "Latn"),
        ("is", "Latn"),
        ("it", "Latn"),
        ("ja", "Jpan"),
        ("kn", "Knda"),
        ("ko", "Hang"),
        ("lt", "Latn"),
        ("lv", "Latn"),
        ("ml", "Mlym"),
        ("ms", "Latn"),
        ("nb", "Latn"),
        ("nl", "Latn"),
        ("nn", "Latn"),
        ("no", "Latn"),
        ("pl", "Latn"),
        ("pt", "Latn"),
        ("ro", "Latn"),
        ("ru", "Cyrl"),
        ("sk", "Latn"),
        ("sl", "Latn"),
        ("sq", "Latn"),
        ("sr", "Cyrl"),
        ("sv", "Latn"),
        ("ta", "Taml"),
        ("te", "Telu"),
        ("th", "Thai"),
        ("tr", "Latn"),
        ("uk", "Cyrl"),
        ("vi", "Latn"),
        ("zh", "Hans"),
        ("zh_hant", "Hant"),
    ];

    #[test]
    fn every_index_language_maps_correctly() {
        for &(code, script) in INDEX_LANGUAGES {
            let mapped = non_latin_script(code);
            if script == "Latn" {
                assert!(
                    mapped.is_none(),
                    "{code} ({script}) must map to None — Latin uses Helvetica",
                );
            } else {
                assert!(
                    mapped.is_some(),
                    "{code} ({script}) is non-Latin but non_latin_script returned None",
                );
            }
        }
    }

    #[test]
    fn every_non_latin_script_resolves_in_api28_fonts_xml() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        for &(code, script) in INDEX_LANGUAGES {
            if script == "Latn" {
                continue;
            }
            let s = non_latin_script(code).unwrap();
            let want_weight = 400;
            // Replicate the locate_for search on the parsed families,
            // without touching /system/fonts.
            let mut hit = None;
            for want in lang_tags(s) {
                if let Some(family) = families.iter().find(|f| f.langs.iter().any(|l| l == want))
                    && let Some(font) = pick_font(family, want_weight, false)
                {
                    hit = Some(font.file_name.clone());
                    break;
                }
            }
            if hit.is_none() {
                for keyword in name_keywords(s) {
                    if let Some(family) = families
                        .iter()
                        .find(|f| f.fonts.iter().any(|f| f.file_name.contains(keyword)))
                        && let Some(font) = pick_font(family, want_weight, false)
                    {
                        hit = Some(font.file_name.clone());
                        break;
                    }
                }
            }
            if hit.is_none()
                && let Some(family) = families
                    .iter()
                    .find(|f| f.name.as_deref() == Some("sans-serif"))
                && let Some(font) = pick_font(family, want_weight, false)
            {
                hit = Some(font.file_name.clone());
            }
            assert!(
                hit.is_some(),
                "{code} ({script}) cannot be resolved from API 28 fonts.xml",
            );
        }
    }

    #[test]
    fn zh_hant_routes_to_traditional_family() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let s = non_latin_script("zh_hant").expect("zh_hant maps to a script");
        assert!(matches!(s, Script::HanTraditional));
        let tag = lang_tags(s)[0];
        let family = families
            .iter()
            .find(|f| f.langs.iter().any(|l| l == tag))
            .expect("API 28 fonts.xml has a zh-Hant family");
        let font = pick_font(family, 400, false).expect("zh-Hant has a regular font");
        assert!(
            font.ttc_index >= 1,
            "zh-Hant should pick a non-zero ttc index (got {})",
            font.ttc_index
        );
    }

    #[test]
    fn parses_default_sans_serif() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let sans = families
            .iter()
            .find(|f| f.name.as_deref() == Some("sans-serif"))
            .expect("sans-serif family present");
        assert!(
            sans.fonts
                .iter()
                .any(|f| f.file_name == "Roboto-Regular.ttf" && f.weight == 400 && !f.italic),
            "Roboto regular weight 400 normal must be parsed"
        );
        assert!(
            sans.fonts
                .iter()
                .any(|f| f.file_name == "Roboto-Bold.ttf" && f.weight == 700),
            "Roboto bold must be parsed"
        );
    }

    #[test]
    fn parses_lang_tagged_arabic() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        assert!(
            families
                .iter()
                .any(|f| f.langs.iter().any(|l| l == "und-Arab")),
            "und-Arab family must be parsed"
        );
    }

    #[test]
    fn parses_ttc_index_for_cjk() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let zh_hans = families
            .iter()
            .find(|f| f.langs.iter().any(|l| l == "zh-Hans"))
            .expect("zh-Hans family present");
        assert!(
            zh_hans.fonts.iter().any(|f| f.ttc_index > 0),
            "zh-Hans should have a non-zero ttc index in NotoSansCJK"
        );
    }
}
