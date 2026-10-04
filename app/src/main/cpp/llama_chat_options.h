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

    // ── Speculative decoding (koboldcpp --usemtp / --draftmodel / --draftamount) ──
    // "off" | "ngram_simple" | "ngram_map_k" | "ngram_map_k4v" | "ngram_mod" | "draft".
    // "draft" requires draft_model_path; the n-gram types draft from the conversation
    // itself and need no extra model.
    std::string speculative_type = "off";
    // Tokens drafted per verification chunk (koboldcpp --draftamount / llama.cpp n_max)
    int32_t spec_draft_amount = 4;
    // n-gram hash window for ngram_mod (llama.cpp --spec-ngram-mod-n-match)
    int32_t ngram_match = 24;
    // Per-model draft GGUF; only used when speculative_type == "draft"
    std::string draft_model_path;

    // ── Cache behavior (koboldcpp --smartcache / --smartcontext / --noshift / --nofastforward) ──
    // Keep KV snapshots in RAM so switching conversations restores instead of re-prefilling
    bool smart_cache = false;
    // Snapshot slots (koboldcpp --smartcache [limit])
    int32_t smart_cache_slots = 1;
    // Reuse the KV across middle-diverged prompts (compaction keeps the head, drops the middle)
    bool smart_context = true;
    // Master gate for trim-and-shift of the KV window (koboldcpp --noshift disables)
    bool context_shift = true;
    // Master gate for KV reuse; off always reprocesses (koboldcpp --nofastforward)
    bool fast_forward = true;

    // ── Loading / batching ──
    // Direct I/O GGUF reads bypass the page cache (koboldcpp --usedirectio)
    bool direct_io = false;
    // Logical batch size; 0 keeps min(512, n_ctx) (koboldcpp --batchsize)
    int32_t n_batch = 0;
    // Physical batch size; 0 keeps the library default (koboldcpp --ubatchsize)
    int32_t n_ubatch = 0;
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

inline bool engine_option_speculative(const std::string & value) {
    return value == "ngram_simple" || value == "ngram_map_k" || value == "ngram_map_k4v" ||
           value == "ngram_mod" || value == "draft";
}

inline int32_t clamp_option_int(int32_t value, int32_t low, int32_t high, int32_t fallback) {
    return value >= low && value <= high ? value : fallback;
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
    jfieldID spec_type_field = env->GetFieldID(options_class, "speculativeType", "Ljava/lang/String;");
    jfieldID draft_amount_field = env->GetFieldID(options_class, "specDraftAmount", "I");
    jfieldID ngram_match_field = env->GetFieldID(options_class, "ngramMatch", "I");
    jfieldID draft_path_field = env->GetFieldID(options_class, "draftModelPath", "Ljava/lang/String;");
    jfieldID smart_cache_field = env->GetFieldID(options_class, "smartCache", "Z");
    jfieldID cache_slots_field = env->GetFieldID(options_class, "smartCacheSlots", "I");
    jfieldID smart_context_field = env->GetFieldID(options_class, "smartContext", "Z");
    jfieldID context_shift_field = env->GetFieldID(options_class, "contextShift", "Z");
    jfieldID fast_forward_field = env->GetFieldID(options_class, "fastForward", "Z");
    jfieldID direct_io_field = env->GetFieldID(options_class, "directIo", "Z");
    jfieldID batch_field = env->GetFieldID(options_class, "nBatch", "I");
    jfieldID ubatch_field = env->GetFieldID(options_class, "nUbatch", "I");
    if (!flash_field || !mmap_field || !type_k_field || !type_v_field || !swa_field ||
        !threads_field || !spec_type_field || !draft_amount_field || !ngram_match_field ||
        !draft_path_field || !smart_cache_field || !cache_slots_field ||
        !smart_context_field || !context_shift_field || !fast_forward_field ||
        !direct_io_field || !batch_field || !ubatch_field) {
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
    const std::string spec = read_option_string(spec_type_field, result.speculative_type);
    if (engine_option_speculative(spec) || spec == "off") result.speculative_type = spec;
    result.draft_model_path = read_option_string(draft_path_field, "");
    result.use_mmap = env->GetBooleanField(options, mmap_field) == JNI_TRUE;
    result.swa_full = env->GetBooleanField(options, swa_field) == JNI_TRUE;
    const jint threads = env->GetIntField(options, threads_field);
    result.threads = threads > 0 && threads <= 32 ? threads : 0;
    result.spec_draft_amount = clamp_option_int(
        env->GetIntField(options, draft_amount_field), 1, 64, result.spec_draft_amount
    );
    result.ngram_match = clamp_option_int(
        env->GetIntField(options, ngram_match_field), 8, 64, result.ngram_match
    );
    result.smart_cache = env->GetBooleanField(options, smart_cache_field) == JNI_TRUE;
    result.smart_cache_slots = clamp_option_int(
        env->GetIntField(options, cache_slots_field), 1, 4, result.smart_cache_slots
    );
    result.smart_context = env->GetBooleanField(options, smart_context_field) == JNI_TRUE;
    result.context_shift = env->GetBooleanField(options, context_shift_field) == JNI_TRUE;
    result.fast_forward = env->GetBooleanField(options, fast_forward_field) == JNI_TRUE;
    result.direct_io = env->GetBooleanField(options, direct_io_field) == JNI_TRUE;
    result.n_batch = clamp_option_int(
        env->GetIntField(options, batch_field), 0, 4096, 0
    );
    result.n_ubatch = clamp_option_int(
        env->GetIntField(options, ubatch_field), 0, 4096, 0
    );
    if (result.n_batch > 0 && result.n_batch < 16) result.n_batch = 16;
    if (result.n_ubatch > 0 && result.n_ubatch < 16) result.n_ubatch = 16;

    env->DeleteLocalRef(options_class);
    return true;
}

} // namespace agora::chat
