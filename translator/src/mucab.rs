use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use mucab::Dictionary;

static DICTIONARY_CACHE: OnceLock<Mutex<HashMap<String, Dictionary>>> = OnceLock::new();

fn with_cache<T, F>(f: F) -> Result<T, String>
where
    F: FnOnce(&mut HashMap<String, Dictionary>) -> Result<T, String>,
{
    let mut cache = DICTIONARY_CACHE
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| "mucab cache mutex poisoned".to_string())?;
    f(&mut cache)
}

pub fn transliterate_with_path(
    path: &str,
    text: &str,
    spaced: bool,
) -> Result<String, String> {
    with_cache(|cache| {
        if !cache.contains_key(path) {
            let dict = Dictionary::load(path)
                .map_err(|err| format!("failed to load mucab dictionary {path}: {err:?}"))?;
            cache.insert(path.to_string(), dict);
        }

        let dict = cache
            .get_mut(path)
            .ok_or_else(|| "mucab dictionary missing after initialization".to_string())?;
        Ok(mucab::transliterate(text, dict, spaced))
    })
}
