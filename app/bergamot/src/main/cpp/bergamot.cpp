#include <jni.h>
#include <string>
#include <algorithm>
#include <android/log.h>
#include "translator/byte_array_util.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/utils.h"
#include "third_party/cld2/public/compact_lang_det.h"
#include <string>
using namespace marian::bergamot;

#include <unordered_map>
#include <mutex>
static std::unordered_map<std::string, std::shared_ptr<TranslationModel>> model_cache;
static std::unique_ptr<BlockingService> global_service = nullptr;
static std::mutex service_mutex;
static std::mutex translation_mutex;

void initializeService() {
    std::lock_guard<std::mutex> lock(service_mutex);

    if (global_service == nullptr) {
        BlockingService::Config blockingConfig;
        blockingConfig.cacheSize = 256;
        blockingConfig.logger.level = "off";
        global_service = std::make_unique<BlockingService>(blockingConfig);
    }
}

void loadModelIntoCache(const std::string& cfg, const std::string& key) {
    std::lock_guard<std::mutex> lock(service_mutex);

    auto validate = true;
    auto pathsDir = "";

    if (model_cache.find(key) == model_cache.end()) {
        auto options = parseOptionsFromString(cfg, validate, pathsDir);
        model_cache[key] = std::make_shared<TranslationModel>(options);
    }
}

std::vector<std::string> translateMultiple(std::vector<std::string> &&inputs, const char *key) {
    initializeService();

    std::string key_str(key);

    // Assume model is already loaded in cache
    std::shared_ptr<TranslationModel> model = model_cache[key_str];

    std::vector<ResponseOptions> responseOptions;
    responseOptions.reserve(inputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
        ResponseOptions opts;
        opts.HTML = false;
        opts.qualityScores = false;
        opts.alignment = false;
        opts.sentenceMappings = false;
        responseOptions.emplace_back(opts);
    }

    std::lock_guard<std::mutex> translation_lock(translation_mutex);
    std::vector<Response> responses = global_service->translateMultiple(model, std::move(inputs), responseOptions);

    std::vector<std::string> results;
    results.reserve(responses.size());
    for (const auto &response: responses) {
        results.push_back(response.target.text);
    }

    return results;
}

std::vector<std::string> pivotMultiple(const char *firstKey, const char *secondKey, std::vector<std::string> &&inputs) {
    initializeService();

    std::string first_key_str(firstKey);
    std::string second_key_str(secondKey);

    // Assume models are already loaded in cache
    std::shared_ptr<TranslationModel> firstModel = model_cache[first_key_str];
    std::shared_ptr<TranslationModel> secondModel = model_cache[second_key_str];

    std::vector<ResponseOptions> responseOptions;
    responseOptions.reserve(inputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
        ResponseOptions opts;
        opts.HTML = false;
        opts.qualityScores = false;
        opts.alignment = false;
        opts.sentenceMappings = false;
        responseOptions.emplace_back(opts);
    }

    std::lock_guard<std::mutex> translation_lock(translation_mutex);
    std::vector<Response> responses = global_service->pivotMultiple(firstModel, secondModel, std::move(inputs), responseOptions);

    std::vector<std::string> results;
    results.reserve(responses.size());
    for (const auto &response: responses) {
        results.push_back(response.target.text);
    }

    return results;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_initializeService(
        JNIEnv* env,
        jobject /* this */) {
    try {
        initializeService();
    } catch(const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_loadModelIntoCache(
        JNIEnv* env,
        jobject /* this */,
        jstring cfg,
        jstring key) {

    const char* c_cfg = env->GetStringUTFChars(cfg, nullptr);
    const char* c_key = env->GetStringUTFChars(key, nullptr);

    try {
        std::string cfg_str(c_cfg);
        std::string key_str(c_key);
        loadModelIntoCache(cfg_str, key_str);
    } catch(const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(cfg, c_cfg);
    env->ReleaseStringUTFChars(key, c_key);
}

// Cleanup function to be called when the library is unloaded
extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_translateMultiple(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray inputs,
        jstring key) {

    const char *c_key = env->GetStringUTFChars(key, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);

    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        std::vector<std::string> translations = translateMultiple(std::move(cpp_inputs), c_key);

        jclass stringClass = env->FindClass("java/lang/String");
        result = env->NewObjectArray((jsize) translations.size(), stringClass, nullptr);

        for (size_t i = 0; i < translations.size(); ++i) {
            jstring jstr = env->NewStringUTF(translations[i].c_str());
            env->SetObjectArrayElement(result, (jsize) i, jstr);
            env->DeleteLocalRef(jstr);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(key, c_key);

    return result;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_pivotMultiple(
        JNIEnv *env,
        jobject /* this */,
        jstring firstKey,
        jstring secondKey,
        jobjectArray inputs) {

    const char *c_firstKey = env->GetStringUTFChars(firstKey, nullptr);
    const char *c_secondKey = env->GetStringUTFChars(secondKey, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);

    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        std::vector<std::string> translations = pivotMultiple(c_firstKey, c_secondKey, std::move(cpp_inputs));

        jclass stringClass = env->FindClass("java/lang/String");
        result = env->NewObjectArray((jsize) translations.size(), stringClass, nullptr);

        for (size_t i = 0; i < translations.size(); ++i) {
            jstring jstr = env->NewStringUTF(translations[i].c_str());
            env->SetObjectArrayElement(result, (jsize) i, jstr);
            env->DeleteLocalRef(jstr);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(firstKey, c_firstKey);
    env->ReleaseStringUTFChars(secondKey, c_secondKey);

    return result;
}

static size_t byteOffsetToCodepointOffset(const std::string& s, size_t byteOffset) {
    size_t cp = 0;
    for (size_t i = 0; i < byteOffset && i < s.size(); ) {
        unsigned char c = s[i];
        if (c < 0x80) i += 1;
        else if ((c & 0xE0) == 0xC0) i += 2;
        else if ((c & 0xF0) == 0xE0) i += 3;
        else i += 4;
        cp++;
    }
    return cp;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_translateMultipleWithAlignment(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray inputs,
        jstring key) {

    const char *c_key = env->GetStringUTFChars(key, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);
    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        initializeService();

        std::string key_str(c_key);
        std::shared_ptr<TranslationModel> model = model_cache[key_str];

        std::vector<ResponseOptions> responseOptions;
        responseOptions.reserve(cpp_inputs.size());
        for (size_t i = 0; i < cpp_inputs.size(); ++i) {
            ResponseOptions opts;
            opts.HTML = false;
            opts.qualityScores = false;
            opts.alignment = true;
            opts.sentenceMappings = false;
            responseOptions.emplace_back(opts);
        }

        std::vector<Response> responses;
        {
            std::lock_guard<std::mutex> translation_lock(translation_mutex);
            responses = global_service->translateMultiple(model, std::move(cpp_inputs), responseOptions);
        }

        jclass alignClass = env->FindClass("dev/davidv/bergamot/TokenAlignment");
        jmethodID alignCtor = env->GetMethodID(alignClass, "<init>", "(IIII)V");

        jclass resultClass = env->FindClass("dev/davidv/bergamot/TranslationWithAlignment");
        jmethodID resultCtor = env->GetMethodID(resultClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;[Ldev/davidv/bergamot/TokenAlignment;)V");

        result = env->NewObjectArray((jsize) responses.size(), resultClass, nullptr);

        for (size_t i = 0; i < responses.size(); ++i) {
            const auto& resp = responses[i];

            std::vector<std::tuple<int, int, int, int>> aligns;
            for (size_t s = 0; s < resp.source.numSentences(); ++s) {
                size_t numTarget = resp.target.numWords(s);
                size_t numSource = resp.source.numWords(s);
                if (numSource == 0) continue;

                for (size_t t = 0; t < numTarget; ++t) {
                    ByteRange tgtRange = resp.target.wordAsByteRange(s, t);
                    if (tgtRange.begin == tgtRange.end) continue;

                    const auto& row = resp.alignments[s][t];
                    size_t bestSrc = std::max_element(row.begin(), row.begin() + numSource) - row.begin();
                    ByteRange srcRange = resp.source.wordAsByteRange(s, bestSrc);

                    aligns.emplace_back(
                        (int) byteOffsetToCodepointOffset(resp.source.text, srcRange.begin),
                        (int) byteOffsetToCodepointOffset(resp.source.text, srcRange.end),
                        (int) byteOffsetToCodepointOffset(resp.target.text, tgtRange.begin),
                        (int) byteOffsetToCodepointOffset(resp.target.text, tgtRange.end)
                    );
                }
            }

            jobjectArray alignArray = env->NewObjectArray((jsize) aligns.size(), alignClass, nullptr);
            for (size_t j = 0; j < aligns.size(); ++j) {
                auto [sb, se, tb, te] = aligns[j];
                jobject alignObj = env->NewObject(alignClass, alignCtor, sb, se, tb, te);
                env->SetObjectArrayElement(alignArray, (jsize) j, alignObj);
                env->DeleteLocalRef(alignObj);
            }

            jstring jSource = env->NewStringUTF(resp.source.text.c_str());
            jstring jTarget = env->NewStringUTF(resp.target.text.c_str());

            jobject resultObj = env->NewObject(resultClass, resultCtor, jSource, jTarget, alignArray);
            env->SetObjectArrayElement(result, (jsize) i, resultObj);

            env->DeleteLocalRef(jSource);
            env->DeleteLocalRef(jTarget);
            env->DeleteLocalRef(alignArray);
            env->DeleteLocalRef(resultObj);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(key, c_key);
    return result;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_pivotMultipleWithAlignment(
        JNIEnv *env,
        jobject /* this */,
        jstring firstKey,
        jstring secondKey,
        jobjectArray inputs) {

    const char *c_firstKey = env->GetStringUTFChars(firstKey, nullptr);
    const char *c_secondKey = env->GetStringUTFChars(secondKey, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);
    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        initializeService();

        std::string first_key_str(c_firstKey);
        std::string second_key_str(c_secondKey);
        std::shared_ptr<TranslationModel> firstModel = model_cache[first_key_str];
        std::shared_ptr<TranslationModel> secondModel = model_cache[second_key_str];

        std::vector<ResponseOptions> responseOptions;
        responseOptions.reserve(cpp_inputs.size());
        for (size_t i = 0; i < cpp_inputs.size(); ++i) {
            ResponseOptions opts;
            opts.HTML = false;
            opts.qualityScores = false;
            opts.alignment = true;
            opts.sentenceMappings = false;
            responseOptions.emplace_back(opts);
        }

        std::vector<Response> responses;
        {
            std::lock_guard<std::mutex> translation_lock(translation_mutex);
            responses = global_service->pivotMultiple(firstModel, secondModel, std::move(cpp_inputs), responseOptions);
        }

        jclass alignClass = env->FindClass("dev/davidv/bergamot/TokenAlignment");
        jmethodID alignCtor = env->GetMethodID(alignClass, "<init>", "(IIII)V");

        jclass resultClass = env->FindClass("dev/davidv/bergamot/TranslationWithAlignment");
        jmethodID resultCtor = env->GetMethodID(resultClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;[Ldev/davidv/bergamot/TokenAlignment;)V");

        result = env->NewObjectArray((jsize) responses.size(), resultClass, nullptr);

        for (size_t i = 0; i < responses.size(); ++i) {
            const auto& resp = responses[i];

            std::vector<std::tuple<int, int, int, int>> aligns;
            for (size_t s = 0; s < resp.source.numSentences(); ++s) {
                size_t numTarget = resp.target.numWords(s);
                size_t numSource = resp.source.numWords(s);
                if (numSource == 0) continue;

                for (size_t t = 0; t < numTarget; ++t) {
                    ByteRange tgtRange = resp.target.wordAsByteRange(s, t);
                    if (tgtRange.begin == tgtRange.end) continue;

                    const auto& row = resp.alignments[s][t];
                    size_t bestSrc = std::max_element(row.begin(), row.begin() + numSource) - row.begin();
                    ByteRange srcRange = resp.source.wordAsByteRange(s, bestSrc);

                    aligns.emplace_back(
                        (int) byteOffsetToCodepointOffset(resp.source.text, srcRange.begin),
                        (int) byteOffsetToCodepointOffset(resp.source.text, srcRange.end),
                        (int) byteOffsetToCodepointOffset(resp.target.text, tgtRange.begin),
                        (int) byteOffsetToCodepointOffset(resp.target.text, tgtRange.end)
                    );
                }
            }

            jobjectArray alignArray = env->NewObjectArray((jsize) aligns.size(), alignClass, nullptr);
            for (size_t j = 0; j < aligns.size(); ++j) {
                auto [sb, se, tb, te] = aligns[j];
                jobject alignObj = env->NewObject(alignClass, alignCtor, sb, se, tb, te);
                env->SetObjectArrayElement(alignArray, (jsize) j, alignObj);
                env->DeleteLocalRef(alignObj);
            }

            jstring jSource = env->NewStringUTF(resp.source.text.c_str());
            jstring jTarget = env->NewStringUTF(resp.target.text.c_str());

            jobject resultObj = env->NewObject(resultClass, resultCtor, jSource, jTarget, alignArray);
            env->SetObjectArrayElement(result, (jsize) i, resultObj);

            env->DeleteLocalRef(jSource);
            env->DeleteLocalRef(jTarget);
            env->DeleteLocalRef(alignArray);
            env->DeleteLocalRef(resultObj);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(firstKey, c_firstKey);
    env->ReleaseStringUTFChars(secondKey, c_secondKey);
    return result;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_cleanup(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(service_mutex);
    global_service.reset();
    model_cache.clear();
}


struct DetectionResult {
    std::string language;
    bool isReliable;
    int confidence;
};

DetectionResult detectLanguage(const char *text, const char *language_hint = nullptr) {
    bool is_reliable;
    int text_bytes = (int) strlen(text);
    bool is_plain_text = true;

    CLD2::Language hint_lang = CLD2::UNKNOWN_LANGUAGE;
    if (language_hint != nullptr && strlen(language_hint) > 0) {
        hint_lang = CLD2::GetLanguageFromName(language_hint);
    }

    CLD2::CLDHints hints = {nullptr, nullptr, 0, hint_lang};
    CLD2::Language language3[3];
    int percent3[3];
    double normalized_score3[3];
    int chunk_bytes;

    CLD2::ExtDetectLanguageSummary(
            text,
            text_bytes,
            is_plain_text,
            &hints,
            0,
            language3,
            percent3,
            normalized_score3,
            nullptr,
            &chunk_bytes,
            &is_reliable
    );

    /*
    __android_log_print(ANDROID_LOG_DEBUG, "Bergamot", "Language detection results:");
    for (int i = 0; i < 3; i++) {
        __android_log_print(ANDROID_LOG_DEBUG, "Bergamot", "  %d: %s - %d%% (score: %.3f)",
                            i + 1,
                            CLD2::LanguageCode(language3[i]),
                            percent3[i],
                            normalized_score3[i]);
    }
    */

    return DetectionResult{
            CLD2::LanguageCode(language3[0]),
            is_reliable,
            percent3[0]
    };
}


extern "C" __attribute__((visibility("default"))) JNIEXPORT jobject JNICALL
Java_dev_davidv_bergamot_LangDetect_detectLanguage(
        JNIEnv *env,
        jobject /* this */,
        jstring text,
        jstring hint) {

    const char *c_text = env->GetStringUTFChars(text, nullptr);
    const char *c_hint = nullptr;
    if (hint != nullptr) {
        c_hint = env->GetStringUTFChars(hint, nullptr);
    }

    // Find the Result class and its constructor
    jclass resultClass = env->FindClass("dev/davidv/bergamot/DetectionResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>",
                                             "(Ljava/lang/String;ZI)V");

    try {
        DetectionResult result = detectLanguage(c_text, c_hint);

        // Convert C++ string to jstring
        jstring j_language = env->NewStringUTF(result.language.c_str());

        // Create new Result object
        jobject j_result = env->NewObject(resultClass, constructor,
                                          j_language,
                                          result.isReliable,
                                          result.confidence);

        env->ReleaseStringUTFChars(text, c_text);
        if (c_hint != nullptr) {
            env->ReleaseStringUTFChars(hint, c_hint);
        }
        return j_result;

    } catch(const std::exception &e) {
        env->ReleaseStringUTFChars(text, c_text);
        if (c_hint != nullptr) {
            env->ReleaseStringUTFChars(hint, c_hint);
        }
        // Handle error
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
        return nullptr;
    }
}
