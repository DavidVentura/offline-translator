use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};

use bergamot_sys::{
    BlockingService, TokenAlignment as BergamotTokenAlignment,
    TranslationModel as BergamotModel,
    TranslationWithAlignment as BergamotTranslationWithAlignment,
};

use crate::translate::{TokenAlignment, TranslationWithAlignment};

const DEFAULT_CACHE_SIZE: usize = 256;

pub struct BergamotEngine {
    service: BlockingService,
    models: HashMap<String, BergamotModel>,
}

impl BergamotEngine {
    pub fn new() -> Self {
        Self {
            service: BlockingService::new(DEFAULT_CACHE_SIZE),
            models: HashMap::new(),
        }
    }

    pub fn load_model_into_cache(
        &mut self,
        config: &str,
        key: &str,
    ) -> Result<(), String> {
        if self.models.contains_key(key) {
            return Ok(());
        }

        let model = catch_bergamot_panic(|| BergamotModel::from_config(config))?;
        self.models.insert(key.to_string(), model);
        Ok(())
    }

    pub fn translate_multiple(
        &self,
        inputs: &[String],
        key: &str,
    ) -> Result<Vec<String>, String> {
        let model = self.model(key)?;
        let refs = inputs.iter().map(String::as_str).collect::<Vec<_>>();
        catch_bergamot_panic(|| Ok(self.service.translate(model, &refs)))
    }

    pub fn translate_multiple_with_alignment(
        &self,
        inputs: &[String],
        key: &str,
    ) -> Result<Vec<TranslationWithAlignment>, String> {
        let model = self.model(key)?;
        let refs = inputs.iter().map(String::as_str).collect::<Vec<_>>();
        catch_bergamot_panic(|| {
            Ok(self
                .service
                .translate_with_alignment(model, &refs)
                .into_iter()
                .map(map_alignment_result)
                .collect())
        })
    }

    pub fn pivot_multiple(
        &self,
        first_key: &str,
        second_key: &str,
        inputs: &[String],
    ) -> Result<Vec<String>, String> {
        let first_model = self.model(first_key)?;
        let second_model = self.model(second_key)?;
        let refs = inputs.iter().map(String::as_str).collect::<Vec<_>>();
        catch_bergamot_panic(|| Ok(self.service.pivot(first_model, second_model, &refs)))
    }

    pub fn pivot_multiple_with_alignment(
        &self,
        first_key: &str,
        second_key: &str,
        inputs: &[String],
    ) -> Result<Vec<TranslationWithAlignment>, String> {
        let first_model = self.model(first_key)?;
        let second_model = self.model(second_key)?;
        let refs = inputs.iter().map(String::as_str).collect::<Vec<_>>();
        catch_bergamot_panic(|| {
            Ok(self
                .service
                .pivot_with_alignment(first_model, second_model, &refs)
                .into_iter()
                .map(map_alignment_result)
                .collect())
        })
    }

    pub fn clear(&mut self) {
        self.models.clear();
    }

    fn model(
        &self,
        key: &str,
    ) -> Result<&BergamotModel, String> {
        self.models
            .get(key)
            .ok_or_else(|| format!("Model not loaded for key: {key}"))
    }
}

impl Default for BergamotEngine {
    fn default() -> Self {
        Self::new()
    }
}

fn map_alignment_result(result: BergamotTranslationWithAlignment) -> TranslationWithAlignment {
    TranslationWithAlignment {
        source_text: result.source,
        translated_text: result.target,
        alignments: result.alignments.into_iter().map(map_alignment).collect(),
    }
}

fn map_alignment(alignment: BergamotTokenAlignment) -> TokenAlignment {
    TokenAlignment {
        src_begin: alignment.src_begin,
        src_end: alignment.src_end,
        tgt_begin: alignment.tgt_begin,
        tgt_end: alignment.tgt_end,
    }
}

fn catch_bergamot_panic<T, F>(f: F) -> Result<T, String>
where
    F: FnOnce() -> Result<T, String>,
{
    catch_unwind(AssertUnwindSafe(f))
        .map_err(panic_to_string)?
}

fn panic_to_string(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "Bergamot panicked".to_string()
    }
}
