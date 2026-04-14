use std::fs;
use std::path::{Path, PathBuf};

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jobject, jobjectArray};
use serde_json::Value;
use translator::{
    CatalogSnapshot, DeletePlan, DictionaryInfo, DownloadPlan, DownloadTask, LangAvailability,
    Language, LanguageCatalog, PackInstallChecker, TranslationPlan, TranslationStep,
    TtsVoicePackInfo, build_catalog_snapshot, can_translate_in_snapshot,
    parse_and_validate_catalog, plan_delete_dictionary_in_snapshot,
    plan_delete_language_in_snapshot, plan_delete_superseded_tts_in_snapshot,
    plan_delete_tts_in_snapshot, plan_dictionary_download_in_snapshot,
    plan_language_download_in_snapshot, plan_tts_download_in_snapshot,
    resolve_translation_plan_in_snapshot, resolve_tts_voice_files_in_snapshot, select_best_catalog,
};

struct FsInstallChecker {
    base_dir: PathBuf,
}

impl FsInstallChecker {
    fn resolve(&self, relative_path: &str) -> PathBuf {
        self.base_dir.join(relative_path)
    }
}

impl PackInstallChecker for FsInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool {
        self.resolve(install_path).exists()
    }

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool {
        let marker_file = self.resolve(marker_path);
        if !marker_file.exists() {
            return false;
        }

        let Ok(contents) = fs::read_to_string(marker_file) else {
            return false;
        };
        let Ok(json) = serde_json::from_str::<Value>(&contents) else {
            return false;
        };
        json.get("version")
            .and_then(Value::as_i64)
            .and_then(|value| i32::try_from(value).ok())
            == Some(expected_version)
    }
}

fn java_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value).ok().map(Into::into)
}

fn optional_java_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    java_string(env, value).filter(|value| !value.is_empty())
}

fn new_java_string<'local>(env: &mut JNIEnv<'local>, value: &str) -> Option<JString<'local>> {
    env.new_string(value).ok()
}

fn snapshot_from_handle<'a>(handle: jlong) -> Option<&'a CatalogSnapshot> {
    if handle == 0 {
        return None;
    }
    Some(unsafe { &*(handle as *const CatalogSnapshot) })
}

fn box_snapshot(snapshot: CatalogSnapshot) -> jlong {
    Box::into_raw(Box::new(snapshot)) as jlong
}

fn drop_snapshot(handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut CatalogSnapshot) });
    }
}

fn parse_selected_catalog(bundled_json: &str, disk_json: Option<&str>) -> Option<LanguageCatalog> {
    let preferred = select_best_catalog(bundled_json, disk_json).ok()?;
    let fallback = if std::ptr::eq(preferred, bundled_json) {
        disk_json
    } else {
        Some(bundled_json)
    };

    parse_and_validate_catalog(preferred)
        .ok()
        .or_else(|| fallback.and_then(|json| parse_and_validate_catalog(json).ok()))
}

fn new_string_array(env: &mut JNIEnv, values: &[String]) -> Option<jobjectArray> {
    let string_class = env.find_class("java/lang/String").ok()?;
    let array = env
        .new_object_array(values.len() as i32, string_class, JObject::null())
        .ok()?;
    for (index, value) in values.iter().enumerate() {
        let string = new_java_string(env, value)?;
        env.set_object_array_element(&array, index as i32, string)
            .ok()?;
    }
    Some(array.into_raw())
}

fn new_nullable_integer<'a>(env: &mut JNIEnv<'a>, value: Option<i32>) -> Option<JObject<'a>> {
    match value {
        Some(value) => env
            .new_object("java/lang/Integer", "(I)V", &[JValue::Int(value)])
            .ok(),
        None => Some(JObject::null()),
    }
}

fn new_native_language<'a>(env: &mut JNIEnv<'a>, language: &Language) -> Option<JObject<'a>> {
    let code = new_java_string(env, &language.code)?;
    let display_name = new_java_string(env, &language.display_name)?;
    let short_display_name = new_java_string(env, &language.short_display_name)?;
    let tess_name = new_java_string(env, &language.tess_name)?;
    let script = new_java_string(env, &language.script)?;
    let dictionary_code = new_java_string(env, &language.dictionary_code)?;
    env.new_object(
        "dev/davidv/translator/NativeLanguage",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
        &[
            JValue::Object(&JObject::from(code)),
            JValue::Object(&JObject::from(display_name)),
            JValue::Object(&JObject::from(short_display_name)),
            JValue::Object(&JObject::from(tess_name)),
            JValue::Object(&JObject::from(script)),
            JValue::Object(&JObject::from(dictionary_code)),
            JValue::Long(language.tessdata_size_bytes as jlong),
        ],
    )
    .ok()
}

fn new_native_lang_availability<'a>(
    env: &mut JNIEnv<'a>,
    code: &str,
    availability: LangAvailability,
) -> Option<JObject<'a>> {
    let code = new_java_string(env, code)?;
    env.new_object(
        "dev/davidv/translator/NativeLangAvailability",
        "(Ljava/lang/String;ZZZZZ)V",
        &[
            JValue::Object(&JObject::from(code)),
            JValue::Bool(availability.has_from_english as u8),
            JValue::Bool(availability.has_to_english as u8),
            JValue::Bool(availability.ocr_files as u8),
            JValue::Bool(availability.dictionary_files as u8),
            JValue::Bool(availability.tts_files as u8),
        ],
    )
    .ok()
}

fn new_dictionary_info<'a>(env: &mut JNIEnv<'a>, info: &DictionaryInfo) -> Option<JObject<'a>> {
    let filename = new_java_string(env, &info.filename)?;
    let type_name = new_java_string(env, &info.type_name)?;
    env.new_object(
        "dev/davidv/translator/DictionaryInfo",
        "(JLjava/lang/String;JLjava/lang/String;J)V",
        &[
            JValue::Long(info.date),
            JValue::Object(&JObject::from(filename)),
            JValue::Long(info.size as jlong),
            JValue::Object(&JObject::from(type_name)),
            JValue::Long(info.word_count as jlong),
        ],
    )
    .ok()
}

fn new_native_tts_region<'a>(
    env: &mut JNIEnv<'a>,
    code: &str,
    display_name: &str,
    voices: &[String],
) -> Option<JObject<'a>> {
    let code = new_java_string(env, code)?;
    let display_name = new_java_string(env, display_name)?;
    let voices = new_string_array(env, voices)?;
    env.new_object(
        "dev/davidv/translator/NativeLanguageTtsRegion",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
        &[
            JValue::Object(&JObject::from(code)),
            JValue::Object(&JObject::from(display_name)),
            JValue::Object(&JObject::from(unsafe { JObjectArray::from_raw(voices) })),
        ],
    )
    .ok()
}

fn new_native_tts_voice_pack_info<'a>(
    env: &mut JNIEnv<'a>,
    info: &TtsVoicePackInfo,
) -> Option<JObject<'a>> {
    let pack_id = new_java_string(env, &info.pack_id)?;
    let display_name = new_java_string(env, &info.display_name)?;
    let quality = match info.quality.as_deref() {
        Some(value) => JObject::from(new_java_string(env, value)?),
        None => JObject::null(),
    };
    env.new_object(
        "dev/davidv/translator/NativeTtsVoicePackInfo",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
        &[
            JValue::Object(&JObject::from(pack_id)),
            JValue::Object(&JObject::from(display_name)),
            JValue::Object(&quality),
            JValue::Long(info.size_bytes as jlong),
        ],
    )
    .ok()
}

fn new_native_download_task<'a>(env: &mut JNIEnv<'a>, task: &DownloadTask) -> Option<JObject<'a>> {
    let pack_id = new_java_string(env, &task.pack_id)?;
    let install_path = new_java_string(env, &task.install_path)?;
    let url = new_java_string(env, &task.url)?;
    let archive_format = match task.archive_format.as_deref() {
        Some(value) => JObject::from(new_java_string(env, value)?),
        None => JObject::null(),
    };
    let extract_to = match task.extract_to.as_deref() {
        Some(value) => JObject::from(new_java_string(env, value)?),
        None => JObject::null(),
    };
    let install_marker_path = match task.install_marker_path.as_deref() {
        Some(value) => JObject::from(new_java_string(env, value)?),
        None => JObject::null(),
    };
    let install_marker_version = new_nullable_integer(env, task.install_marker_version)?;
    env.new_object(
        "dev/davidv/translator/NativeDownloadTask",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZLjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/Integer;)V",
        &[
            JValue::Object(&JObject::from(pack_id)),
            JValue::Object(&JObject::from(install_path)),
            JValue::Object(&JObject::from(url)),
            JValue::Long(task.size_bytes as jlong),
            JValue::Bool(task.decompress as u8),
            JValue::Object(&archive_format),
            JValue::Object(&extract_to),
            JValue::Bool(task.delete_after_extract as u8),
            JValue::Object(&install_marker_path),
            JValue::Object(&install_marker_version),
        ],
    )
    .ok()
}

fn new_native_download_task_array(
    env: &mut JNIEnv,
    tasks: &[DownloadTask],
) -> Option<jobjectArray> {
    let class = env
        .find_class("dev/davidv/translator/NativeDownloadTask")
        .ok()?;
    let array = env
        .new_object_array(tasks.len() as i32, class, JObject::null())
        .ok()?;
    for (index, task) in tasks.iter().enumerate() {
        let object = new_native_download_task(env, task)?;
        env.set_object_array_element(&array, index as i32, object)
            .ok()?;
    }
    Some(array.into_raw())
}

fn new_native_download_plan<'a>(env: &mut JNIEnv<'a>, plan: &DownloadPlan) -> Option<JObject<'a>> {
    let tasks = new_native_download_task_array(env, &plan.tasks)?;
    env.new_object(
        "dev/davidv/translator/NativeDownloadPlan",
        "(J[Ldev/davidv/translator/NativeDownloadTask;)V",
        &[
            JValue::Long(plan.total_size as jlong),
            JValue::Object(&JObject::from(unsafe { JObjectArray::from_raw(tasks) })),
        ],
    )
    .ok()
}

fn new_native_delete_plan<'a>(env: &mut JNIEnv<'a>, plan: &DeletePlan) -> Option<JObject<'a>> {
    let file_paths = new_string_array(env, &plan.file_paths)?;
    let directory_paths = new_string_array(env, &plan.directory_paths)?;
    env.new_object(
        "dev/davidv/translator/NativeDeletePlan",
        "([Ljava/lang/String;[Ljava/lang/String;)V",
        &[
            JValue::Object(&JObject::from(unsafe {
                JObjectArray::from_raw(file_paths)
            })),
            JValue::Object(&JObject::from(unsafe {
                JObjectArray::from_raw(directory_paths)
            })),
        ],
    )
    .ok()
}

fn new_native_tts_voice_files<'a>(
    env: &mut JNIEnv<'a>,
    engine: &str,
    model_path: &str,
    aux_path: &str,
    language_code: &str,
    speaker_id: Option<i32>,
) -> Option<JObject<'a>> {
    let engine = new_java_string(env, engine)?;
    let model_path = new_java_string(env, model_path)?;
    let aux_path = new_java_string(env, aux_path)?;
    let language_code = new_java_string(env, language_code)?;
    let speaker_id = new_nullable_integer(env, speaker_id)?;
    env.new_object(
        "dev/davidv/translator/NativeTtsVoiceFiles",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)V",
        &[
            JValue::Object(&JObject::from(engine)),
            JValue::Object(&JObject::from(model_path)),
            JValue::Object(&JObject::from(aux_path)),
            JValue::Object(&JObject::from(language_code)),
            JValue::Object(&speaker_id),
        ],
    )
    .ok()
}

fn new_native_translation_plan_step<'a>(
    env: &mut JNIEnv<'a>,
    step: &TranslationStep,
) -> Option<JObject<'a>> {
    let from_code = new_java_string(env, &step.from_code)?;
    let to_code = new_java_string(env, &step.to_code)?;
    let cache_key = new_java_string(env, &step.cache_key)?;
    let config = new_java_string(env, &step.config)?;
    env.new_object(
        "dev/davidv/translator/NativeTranslationPlanStep",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        &[
            JValue::Object(&JObject::from(from_code)),
            JValue::Object(&JObject::from(to_code)),
            JValue::Object(&JObject::from(cache_key)),
            JValue::Object(&JObject::from(config)),
        ],
    )
    .ok()
}

fn new_native_translation_plan_step_array(
    env: &mut JNIEnv,
    steps: &[TranslationStep],
) -> Option<jobjectArray> {
    let class = env
        .find_class("dev/davidv/translator/NativeTranslationPlanStep")
        .ok()?;
    let array = env
        .new_object_array(steps.len() as i32, class, JObject::null())
        .ok()?;
    for (index, step) in steps.iter().enumerate() {
        let object = new_native_translation_plan_step(env, step)?;
        env.set_object_array_element(&array, index as i32, object)
            .ok()?;
    }
    Some(array.into_raw())
}

fn new_native_translation_plan<'a>(
    env: &mut JNIEnv<'a>,
    plan: &TranslationPlan,
) -> Option<JObject<'a>> {
    let steps = new_native_translation_plan_step_array(env, &plan.steps)?;
    env.new_object(
        "dev/davidv/translator/NativeTranslationPlan",
        "([Ldev/davidv/translator/NativeTranslationPlanStep;)V",
        &[JValue::Object(&JObject::from(unsafe {
            JObjectArray::from_raw(steps)
        }))],
    )
    .ok()
}

fn new_native_languages_array(env: &mut JNIEnv, languages: &[Language]) -> Option<jobjectArray> {
    let class = env
        .find_class("dev/davidv/translator/NativeLanguage")
        .ok()?;
    let array = env
        .new_object_array(languages.len() as i32, class, JObject::null())
        .ok()?;
    for (index, language) in languages.iter().enumerate() {
        let object = new_native_language(env, language)?;
        env.set_object_array_element(&array, index as i32, object)
            .ok()?;
    }
    Some(array.into_raw())
}

fn new_native_lang_availability_array(
    env: &mut JNIEnv,
    rows: &[(String, LangAvailability)],
) -> Option<jobjectArray> {
    let class = env
        .find_class("dev/davidv/translator/NativeLangAvailability")
        .ok()?;
    let array = env
        .new_object_array(rows.len() as i32, class, JObject::null())
        .ok()?;
    for (index, (code, availability)) in rows.iter().enumerate() {
        let object = new_native_lang_availability(env, code, *availability)?;
        env.set_object_array_element(&array, index as i32, object)
            .ok()?;
    }
    Some(array.into_raw())
}

fn new_native_tts_region_array(
    env: &mut JNIEnv,
    rows: &[(String, translator::LanguageTtsRegionV2)],
) -> Option<jobjectArray> {
    let class = env
        .find_class("dev/davidv/translator/NativeLanguageTtsRegion")
        .ok()?;
    let array = env
        .new_object_array(rows.len() as i32, class, JObject::null())
        .ok()?;
    for (index, (code, region)) in rows.iter().enumerate() {
        let object = new_native_tts_region(env, code, &region.display_name, &region.voices)?;
        env.set_object_array_element(&array, index as i32, object)
            .ok()?;
    }
    Some(array.into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeOpenCatalog(
    mut env: JNIEnv,
    _: JClass,
    bundled_json: JString,
    disk_json: JString,
    base_dir: JString,
) -> jlong {
    let Some(bundled_json) = java_string(&mut env, bundled_json) else {
        return 0;
    };
    let disk_json = optional_java_string(&mut env, disk_json);
    let Some(base_dir) = java_string(&mut env, base_dir) else {
        return 0;
    };
    let Some(catalog) = parse_selected_catalog(&bundled_json, disk_json.as_deref()) else {
        return 0;
    };
    let checker = FsInstallChecker {
        base_dir: Path::new(&base_dir).to_path_buf(),
    };
    box_snapshot(build_catalog_snapshot(catalog, base_dir, &checker))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeCloseCatalog(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    drop_snapshot(handle);
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeFormatVersion(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jint {
    snapshot_from_handle(handle)
        .map(|snapshot| snapshot.catalog.format_version)
        .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeGeneratedAt(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jlong {
    snapshot_from_handle(handle)
        .map(|snapshot| snapshot.catalog.generated_at)
        .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeDictionaryVersion(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jint {
    snapshot_from_handle(handle)
        .map(|snapshot| snapshot.catalog.dictionary_version)
        .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeLanguages(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jobjectArray {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let languages = snapshot.catalog.language_list();
    new_native_languages_array(&mut env, &languages).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeComputeLanguageAvailability(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jobjectArray {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let mut availability = snapshot
        .availability_by_code
        .iter()
        .map(|(code, availability)| (code.clone(), *availability))
        .collect::<Vec<_>>();
    availability.sort_by(|left, right| left.0.cmp(&right.0));
    new_native_lang_availability_array(&mut env, &availability).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeDictionaryInfo(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    dictionary_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(dictionary_code) = java_string(&mut env, dictionary_code) else {
        return std::ptr::null_mut();
    };
    let Some(info) = snapshot.catalog.dictionary_info(&dictionary_code) else {
        return std::ptr::null_mut();
    };
    new_dictionary_info(&mut env, &info)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeTtsPackIds(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobjectArray {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let pack_ids = snapshot.catalog.tts_pack_ids_for_language(&language_code);
    new_string_array(&mut env, &pack_ids).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeOrderedTtsRegions(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobjectArray {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let regions = snapshot
        .catalog
        .ordered_tts_regions_for_language(&language_code);
    new_native_tts_region_array(&mut env, &regions).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeTtsVoicePackInfo(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    pack_id: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(pack_id) = java_string(&mut env, pack_id) else {
        return std::ptr::null_mut();
    };
    let Some(info) = snapshot.catalog.tts_voice_pack_info(&pack_id) else {
        return std::ptr::null_mut();
    };
    new_native_tts_voice_pack_info(&mut env, &info)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeCanSwapLanguages(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    from_code: JString,
    to_code: JString,
) -> jboolean {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return 0;
    };
    let Some(from_code) = java_string(&mut env, from_code) else {
        return 0;
    };
    let Some(to_code) = java_string(&mut env, to_code) else {
        return 0;
    };
    snapshot.catalog.can_swap_languages(&from_code, &to_code) as u8
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeCanTranslate(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    from_code: JString,
    to_code: JString,
) -> jboolean {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return 0;
    };
    let Some(from_code) = java_string(&mut env, from_code) else {
        return 0;
    };
    let Some(to_code) = java_string(&mut env, to_code) else {
        return 0;
    };
    can_translate_in_snapshot(snapshot, &from_code, &to_code) as u8
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeResolveTranslationPlan(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    from_code: JString,
    to_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(from_code) = java_string(&mut env, from_code) else {
        return std::ptr::null_mut();
    };
    let Some(to_code) = java_string(&mut env, to_code) else {
        return std::ptr::null_mut();
    };
    let Some(plan) = resolve_translation_plan_in_snapshot(snapshot, &from_code, &to_code) else {
        return std::ptr::null_mut();
    };
    new_native_translation_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanLanguageDownload(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let plan = plan_language_download_in_snapshot(snapshot, &language_code);
    new_native_download_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanDictionaryDownload(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let Some(plan) = plan_dictionary_download_in_snapshot(snapshot, &language_code) else {
        return std::ptr::null_mut();
    };
    new_native_download_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanTtsDownload(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
    selected_pack_id: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let selected_pack_id = optional_java_string(&mut env, selected_pack_id);
    let Some(plan) =
        plan_tts_download_in_snapshot(snapshot, &language_code, selected_pack_id.as_deref())
    else {
        return std::ptr::null_mut();
    };
    new_native_download_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanDeleteLanguage(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let plan = plan_delete_language_in_snapshot(snapshot, &language_code);
    new_native_delete_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanDeleteDictionary(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let plan = plan_delete_dictionary_in_snapshot(snapshot, &language_code);
    new_native_delete_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanDeleteTts(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let plan = plan_delete_tts_in_snapshot(snapshot, &language_code);
    new_native_delete_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativePlanDeleteSupersededTts(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
    selected_pack_id: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let Some(selected_pack_id) = java_string(&mut env, selected_pack_id) else {
        return std::ptr::null_mut();
    };
    let plan = plan_delete_superseded_tts_in_snapshot(snapshot, &language_code, &selected_pack_id);
    new_native_delete_plan(&mut env, &plan)
        .map(JObject::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeTtsSizeBytesForLanguage(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jlong {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return 0;
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return 0;
    };
    snapshot.catalog.tts_size_bytes_for_language(&language_code) as jlong
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeTranslationSizeBytesForLanguage(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jlong {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return 0;
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return 0;
    };
    snapshot
        .catalog
        .translation_size_bytes_for_language(&language_code) as jlong
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeDefaultTtsPackIdForLanguage(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    snapshot
        .catalog
        .default_tts_pack_id_for_language(&language_code)
        .and_then(|value| new_java_string(&mut env, &value))
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_davidv_translator_CatalogBinding_nativeResolveTtsVoiceFiles(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    language_code: JString,
) -> jobject {
    let Some(snapshot) = snapshot_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let Some(language_code) = java_string(&mut env, language_code) else {
        return std::ptr::null_mut();
    };
    let Some(files) = resolve_tts_voice_files_in_snapshot(snapshot, &language_code) else {
        return std::ptr::null_mut();
    };
    let base_dir = &snapshot.base_dir;
    let checker = FsInstallChecker {
        base_dir: Path::new(base_dir).to_path_buf(),
    };
    let model_path = checker.resolve(&files.model_install_path);
    if !model_path.exists() {
        return std::ptr::null_mut();
    }
    let aux_path = checker.resolve(&files.aux_install_path);
    if !aux_path.exists() {
        return std::ptr::null_mut();
    }

    new_native_tts_voice_files(
        &mut env,
        &files.engine,
        &model_path.to_string_lossy(),
        &aux_path.to_string_lossy(),
        &files.language_code,
        files.speaker_id,
    )
    .map(JObject::into_raw)
    .unwrap_or(std::ptr::null_mut())
}
