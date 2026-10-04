#pragma once

// Koboldcpp-inspired engine capability switches for the embedded llama.cpp runtime.
// Mirrors the Kotlin `LlamaEngineOptions` object field-for-field; read through JNI in
// nativeChatLoadModel. Defaults reproduce llama.cpp's own context defaults so the
// previous behavior is the unset state.

#include "llama.h"
#include "ggml.h"
#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>

#include "jni_utf8.h"

namespace agora::chat {

struct EngineOptions {
    // "auto" | "on" | "off" (koboldcpp -fa / --flash-attn)
    std::string flash_attention = "auto";
    // Memory-map the GGUF instead of reading it into RAM (koboldcpp --mmap / --no-mmap)
    bool use_mmap = true;
    // KV cache element type for K (koboldcpp -ctk)
    std::string cache_type_k = "f16";
    // KV cache element type for V (koboldcpp -ctv); quantized values need flash attention
    std::string cache_type_v = "f16";
    // Full-size SWA KV cache (koboldcpp --noswa / --swa-full): keeps long-conversation
    // KV reuse possible on sliding-window models at the cost of memory.
    bool swa_full = false;
    // CPU threads for decode and prefill; 0 keeps the library default (koboldcpp --threads)
    int32_t threads = 0;
};

inline bool engine_option_parseable(const std::string & value) {
    return value == "f32" || value == "f16" || value == "bf16" || value == "q8_0" ||
           value == "q4_0" || value == "q4_1" || value == "iq4_nl" || value == "q5_0" ||
           value == "q5_1";
}

inline ggml_type engine_option_ggml_type(const std::string & value) {
    if (value == "f32") return GGML_TYPE_F32;
    if (value == "bf16") return GGML_TYPE_BF16;
    if (value == "q8_0") return GGML_TYPE_Q8_0;
    if (value == "q4_0") return GGML_TYPE_Q4_0;
    if (value == "q4_1") return GGML_TYPE_Q4_1;
    if (value == "iq4_nl") return GGML_TYPE_IQ4_NL;
    if (value == "q5_0") return GGML_TYPE_Q5_0;
    if (value == "q5_1") return GGML_TYPE_Q5_1;
    return GGML_TYPE_F16;
}

inline llama_flash_attn_type engine_option_flash_attn(const std::string & value) {
    if (value == "on") return LLAMA_FLASH_ATTN_TYPE_ENABLED;
    if (value == "off") return LLAMA_FLASH_ATTN_TYPE_DISABLED;
    return LLAMA_FLASH_ATTN_TYPE_AUTO;
}

// Reads the fields of the Kotlin LlamaEngineOptions object. Unknown or missing values fall
// back to the defaults, so a stale UI cannot make the native loader reject a model.
inline bool read_engine_options(JNIEnv * env, jobject options, EngineOptions & result) {
    if (!options) return true; // null object keeps every default
    jclass options_class = env->GetObjectClass(options);
    if (!options_class) return false;

    jfieldID flash_field = env->GetFieldID(options_class, "flashAttention", "Ljava/lang/String;");
    jfieldID mmap_field = env->GetFieldID(options_class, "useMmap", "Z");
    jfieldID type_k_field = env->GetFieldID(options_class, "cacheTypeK", "Ljava/lang/String;");
    jfieldID type_v_field = env->GetFieldID(options_class, "cacheTypeV", "Ljava/lang/String;");
    jfieldID swa_field = env->GetFieldID(options_class, "swaFull", "Z");
    jfieldID threads_field = env->GetFieldID(options_class, "threads", "I");
    if (!flash_field || !mmap_field || !type_k_field || !type_v_field || !swa_field ||
        !threads_field) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(options_class);
        return false;
    }

    auto read_option_string = [env, options](
        jfieldID field, const std::string & fallback
    ) -> std::string {
        jstring value = static_cast<jstring>(env->GetObjectField(options, field));
        if (!value) return fallback;
        std::string decoded;
        if (!agora::jni::read_java_string(env, value, decoded)) return fallback;
        env->DeleteLocalRef(value);
        return decoded.empty() ? fallback : decoded;
    };

    const std::string flash = read_option_string(flash_field, result.flash_attention);
    if (flash == "on" || flash == "off" || flash == "auto") result.flash_attention = flash;
    const std::string type_k = read_option_string(type_k_field, result.cache_type_k);
    if (engine_option_parseable(type_k)) result.cache_type_k = type_k;
    const std::string type_v = read_option_string(type_v_field, result.cache_type_v);
    if (engine_option_parseable(type_v)) result.cache_type_v = type_v;
    result.use_mmap = env->GetBooleanField(options, mmap_field) == JNI_TRUE;
    result.swa_full = env->GetBooleanField(options, swa_field) == JNI_TRUE;
    const jint threads = env->GetIntField(options, threads_field);
    result.threads = threads > 0 && threads <= 32 ? threads : 0;

    env->DeleteLocalRef(options_class);
    return true;
}

} // namespace agora::chat
