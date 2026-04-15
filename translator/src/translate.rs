use std::path::Path;

use crate::BergamotEngine;
use crate::catalog::CatalogSnapshot;
#[cfg(test)]
use crate::catalog::{
    LanguageCatalog, PackInstallChecker, PackResolver, has_translation_direction_installed,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslatedText {
    pub translated: String,
    pub transliterated: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct TokenAlignment {
    pub src_begin: usize,
    pub src_end: usize,
    pub tgt_begin: usize,
    pub tgt_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationWithAlignment {
    pub source_text: String,
    pub translated_text: String,
    pub alignments: Vec<TokenAlignment>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TranslationStep {
    pub from_code: String,
    pub to_code: String,
    pub cache_key: String,
    pub config: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct TranslationPlan {
    pub steps: Vec<TranslationStep>,
}

fn absolute_install_path(base_dir: &str, install_path: &str) -> String {
    Path::new(base_dir)
        .join(install_path)
        .to_string_lossy()
        .into_owned()
}

fn build_bergamot_config(base_dir: &str, step: &crate::language::LanguageDirection) -> String {
    let model_path = absolute_install_path(base_dir, &step.model.path);
    let src_vocab_path = absolute_install_path(base_dir, &step.src_vocab.path);
    let tgt_vocab_path = absolute_install_path(base_dir, &step.tgt_vocab.path);

    format!(
        "models:\n  - {model_path}\n\
vocabs:\n  - {src_vocab_path}\n  - {tgt_vocab_path}\n\
beam-size: 1\n\
normalize: 1.0\n\
word-penalty: 0\n\
max-length-break: 128\n\
mini-batch-words: 1024\n\
max-length-factor: 2.0\n\
skip-cost: true\n\
cpu-threads: 1\n\
quiet: true\n\
quiet-translation: true\n\
gemm-precision: int8shiftAlphaAll\n\
alignment: soft\n"
    )
}

fn cache_key(from_code: &str, to_code: &str) -> String {
    format!("{from_code}{to_code}")
}

#[cfg(test)]
pub(crate) fn resolve_translation_plan<C>(
    catalog: &LanguageCatalog,
    base_dir: &str,
    from_code: &str,
    to_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> Option<TranslationPlan>
where
    C: PackInstallChecker,
{
    if from_code == to_code {
        return Some(TranslationPlan::default());
    }

    let steps = if from_code == "en" {
        vec![resolve_translation_step(
            catalog, base_dir, "en", to_code, resolver,
        )?]
    } else if to_code == "en" {
        vec![resolve_translation_step(
            catalog, base_dir, from_code, "en", resolver,
        )?]
    } else {
        vec![
            resolve_translation_step(catalog, base_dir, from_code, "en", resolver)?,
            resolve_translation_step(catalog, base_dir, "en", to_code, resolver)?,
        ]
    };

    Some(TranslationPlan { steps })
}

pub(crate) fn resolve_translation_plan_in_snapshot(
    snapshot: &CatalogSnapshot,
    from_code: &str,
    to_code: &str,
) -> Option<TranslationPlan> {
    if from_code == to_code {
        return Some(TranslationPlan::default());
    }

    let step = |from: &str, to: &str| {
        let pack_id = snapshot.catalog.translation_pack_id(from, to)?;
        let status = snapshot.pack_statuses.get(&pack_id)?;
        if !status.installed {
            return None;
        }
        let direction = snapshot.catalog.translation_direction(from, to)?;
        Some(TranslationStep {
            from_code: from.to_string(),
            to_code: to.to_string(),
            cache_key: cache_key(from, to),
            config: build_bergamot_config(&snapshot.base_dir, &direction),
        })
    };

    let steps = if from_code == "en" {
        vec![step("en", to_code)?]
    } else if to_code == "en" {
        vec![step(from_code, "en")?]
    } else {
        vec![step(from_code, "en")?, step("en", to_code)?]
    };

    Some(TranslationPlan { steps })
}

pub(crate) fn execute_translation_plan(
    engine: &mut BergamotEngine,
    plan: &TranslationPlan,
    texts: &[String],
) -> Result<Vec<String>, String> {
    ensure_plan_loaded(engine, plan)?;
    match plan.steps.as_slice() {
        [step] => engine.translate_multiple(texts, &step.cache_key),
        [first, second] => engine.pivot_multiple(&first.cache_key, &second.cache_key, texts),
        _ => Ok(Vec::new()),
    }
}

pub(crate) fn execute_translation_plan_with_alignment(
    engine: &mut BergamotEngine,
    plan: &TranslationPlan,
    texts: &[String],
) -> Result<Vec<TranslationWithAlignment>, String> {
    ensure_plan_loaded(engine, plan)?;
    match plan.steps.as_slice() {
        [step] => engine.translate_multiple_with_alignment(texts, &step.cache_key),
        [first, second] => {
            engine.pivot_multiple_with_alignment(&first.cache_key, &second.cache_key, texts)
        }
        _ => Ok(Vec::new()),
    }
}

pub fn translate_texts_in_snapshot(
    engine: &mut BergamotEngine,
    snapshot: &CatalogSnapshot,
    from_code: &str,
    to_code: &str,
    texts: &[String],
) -> Option<Result<Vec<String>, String>> {
    let plan = resolve_translation_plan_in_snapshot(snapshot, from_code, to_code)?;
    Some(execute_translation_plan(engine, &plan, texts))
}

pub fn translate_texts_with_alignment_in_snapshot(
    engine: &mut BergamotEngine,
    snapshot: &CatalogSnapshot,
    from_code: &str,
    to_code: &str,
    texts: &[String],
) -> Option<Result<Vec<TranslationWithAlignment>, String>> {
    let plan = resolve_translation_plan_in_snapshot(snapshot, from_code, to_code)?;
    Some(execute_translation_plan_with_alignment(engine, &plan, texts))
}

fn ensure_plan_loaded(
    engine: &mut BergamotEngine,
    plan: &TranslationPlan,
) -> Result<(), String> {
    for step in &plan.steps {
        engine.load_model_into_cache(&step.config, &step.cache_key)?;
    }
    Ok(())
}

#[cfg(test)]
fn resolve_translation_step<C>(
    catalog: &LanguageCatalog,
    base_dir: &str,
    from_code: &str,
    to_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> Option<TranslationStep>
where
    C: PackInstallChecker,
{
    if !has_translation_direction_installed(catalog, from_code, to_code, resolver) {
        return None;
    }

    let direction = catalog.translation_direction(from_code, to_code)?;
    Some(TranslationStep {
        from_code: from_code.to_string(),
        to_code: to_code.to_string(),
        cache_key: cache_key(from_code, to_code),
        config: build_bergamot_config(base_dir, &direction),
    })
}
