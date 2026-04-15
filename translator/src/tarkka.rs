use std::collections::HashMap;
use std::fs::File;
use std::sync::{Mutex, OnceLock};

use tarkka::reader::DictionaryReader;
pub use tarkka::WordWithTaggedEntries;

static DICTIONARY_READERS: OnceLock<Mutex<HashMap<String, DictionaryReader<File>>>> =
    OnceLock::new();

fn with_reader_cache<T, F>(f: F) -> Result<T, String>
where
    F: FnOnce(&mut HashMap<String, DictionaryReader<File>>) -> Result<T, String>,
{
    let mut readers = DICTIONARY_READERS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| "dictionary cache mutex poisoned".to_string())?;
    f(&mut readers)
}

pub fn lookup_dictionary(
    path: &str,
    word: &str,
) -> Result<Option<WordWithTaggedEntries>, String> {
    with_reader_cache(|readers| {
        if !readers.contains_key(path) {
            let file = File::open(path)
                .map_err(|err| format!("failed to open dictionary file {path}: {err}"))?;
            let reader = DictionaryReader::open(file)
                .map_err(|err| format!("failed to open dictionary reader {path}: {err}"))?;
            eprintln!(
                "Dict version {}, timestamp {:?}",
                reader.version(),
                reader.created_at(),
            );
            readers.insert(path.to_string(), reader);
        }
        let reader = readers
            .get_mut(path)
            .ok_or_else(|| "dictionary reader missing after initialization".to_string())?;
        reader
            .lookup(word)
            .map_err(|err| format!("dictionary lookup failed: {err}"))
    })
}

pub fn close_dictionary(path: &str) -> Result<(), String> {
    with_reader_cache(|readers| {
        readers.remove(path);
        Ok(())
    })
}
