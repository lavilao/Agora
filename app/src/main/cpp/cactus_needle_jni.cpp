// Agora's JNI boundary for the Cactus engine on 32-bit ARM (armeabi-v7a).
//
// The full Cactus runtime (thirdparty/cactus) writes its NEON kernels for
// ARMv8.2-A + FP16 + DotProd + I8MM and cannot run on a 32-bit phone. Cactus
// also publishes the Needle 3 engine as a prebuilt static library
// (HuggingFace Cactus-Compute/needle3, android-armv7/libneedle.a) that runs
// the same family's tiny tool-calling models from single ".cact" files.
// This bridge exposes that runtime behind the exact same Kotlin surface the
// arm64 boundary exposes for the full engine, so CactusChatEngine drives both
// without knowing which one it holds:
//
//   nativeInit(path)     path is a .cact FILE (not a bundle directory);
//                        the bytes are read once and kept alive for the
//                        whole process because the engine maps them in place.
//   nativeComplete(...)  messagesJson carries a needle session envelope
//                        ({"system", "tools", "feed", "reset",
//                        "max_new_tokens"}) built by the Kotlin layer, which
//                        owns the conversation bookkeeping: the needle
//                        runtime keeps its own accumulating conversation and
//                        is NOT stateless like the full engine.
//   nativeStop(...)      best effort only: generation itself cannot be
//                        interrupted, it is bounded by max_new_tokens and
//                        the response grammar.
//
// The same engine also runs Whistle speech models: needle keeps one model
// per kind (NEEDLE_TEXT, NEEDLE_SPEECH), so the chat model and a 16 kHz
// transcription model are resident side by side. The speech side is exposed
// through CactusSpeechEngine:
//   nativeLoadSpeechModel(path)  loads a Whistle .cact (kept independent of
//                                 the resident chat model);
//   nativeTranscribe(pcm, ...)    16 kHz mono float PCM in [-1, 1], at most
//                                 30 s, returns the engine's JSON verbatim
//                                 or an error envelope.
//
// The needle API is plain C, so no STL types cross the library boundary, but
// the prebuilt engine's internals live in the NDK libc++ "__ndk1" namespace.
// The module links the shared STL (ANDROID_STL=c++_shared), whose r28
// libc++ no longer defines std::__ndk1::__hash_memory that the armv7 engine
// references out of line - hash_memory_shim.cpp provides that one symbol as
// a weak definition (verbatim LLVM murmur2 algorithm).
//
// Privacy: unlike the full engine, the needle runtime exposes no telemetry
// or cloud-handoff surface at all; its API is pure local inference.

#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "jni_utf8.h"
#include "needle.h"

#define LOG_TAG "AgoraCactusNeedle"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// needle keeps one process-global, non-thread-safe model: every entry point
// serializes through this mutex.
std::mutex g_needle_mutex;

// The engine reads the .cact bytes in place and has no unload API, so every
// loaded file's buffer must outlive the process. They are keyed by path; a
// slot's bytes are only dropped when that model kind is definitively replaced
// (see the reconciliation notes in nativeInit / nativeLoadSpeechModel).
std::map<std::string, std::vector<unsigned char>> g_act_bytes;
std::string g_text_path;   // .cact currently loaded as the NEEDLE_TEXT model
std::string g_speech_path; // .cact currently loaded as the NEEDLE_SPEECH model

// Static-prefix cache: needle_init() re-tokenizes system + tools; it is only
// re-run when either side actually changed between completions.
std::string g_last_system;
std::string g_last_tools;
bool g_initialized = false;

// Bridge-level error, reported when the engine itself has nothing to say.
std::string g_bridge_error;

// Set by nativeStop(); checked between fed turns so a cancelled request
// stops replaying history as soon as possible.
bool g_stop_requested = false;

constexpr int kOutCapacity = 64 * 1024;
constexpr long kMaxActSize = 512L * 1024L * 1024L;

// ---------------------------------------------------------------------------
// Envelope parsing
// ---------------------------------------------------------------------------
//
// The Kotlin layer owns full JSON handling and sends this exact shape (keys
// in any order, unknown keys ignored):
//   {"system": "...", "feed": ["turn", ...], "reset": false,
//    "max_new_tokens": 512}
// Only string / array-of-string / bool / int values are needed, but the
// scanner skips anything else robustly so a future envelope stays loadable.

struct Envelope {
    std::string system;
    std::vector<std::string> feed;
    bool reset = false;
    int max_new_tokens = 512;
};

void append_utf8(std::string & out, unsigned int cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

bool parse_hex4(const char *& p, const char * end, unsigned int & value) {
    if (end - p < 4) return false;
    value = 0;
    for (int i = 0; i < 4; ++i) {
        char c = p[i];
        value <<= 4;
        if (c >= '0' && c <= '9') value |= static_cast<unsigned>(c - '0');
        else if (c >= 'a' && c <= 'f') value |= static_cast<unsigned>(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') value |= static_cast<unsigned>(c - 'A' + 10);
        else return false;
    }
    p += 4;
    return true;
}

// Parses a JSON string literal starting at the opening quote. Raw bytes are
// copied verbatim (kotlinx emits non-ASCII as UTF-8); the seven escapes and
// \uXXXX (including surrogate pairs) are resolved.
bool parse_json_string(const char *& p, const char * end, std::string & out) {
    if (p == end || *p != '"') return false;
    ++p;
    while (p < end) {
        char c = *p;
        if (c == '"') {
            ++p;
            return true;
        }
        if (static_cast<unsigned char>(c) < 0x20) return false;
        if (c == '\\') {
            ++p;
            if (p == end) return false;
            char esc = *p;
            switch (esc) {
                case '"': out.push_back('"'); ++p; break;
                case '\\': out.push_back('\\'); ++p; break;
                case '/': out.push_back('/'); ++p; break;
                case 'b': out.push_back('\b'); ++p; break;
                case 'f': out.push_back('\f'); ++p; break;
                case 'n': out.push_back('\n'); ++p; break;
                case 'r': out.push_back('\r'); ++p; break;
                case 't': out.push_back('\t'); ++p; break;
                case 'u': {
                    ++p;
                    unsigned int cp = 0;
                    if (!parse_hex4(p, end, cp)) return false;
                    if (cp >= 0xD800 && cp <= 0xDBFF && end - p >= 6 &&
                        p[0] == '\\' && p[1] == 'u') {
                        const char * save = p;
                        p += 2;
                        unsigned int low = 0;
                        if (parse_hex4(p, end, low) && low >= 0xDC00 && low <= 0xDFFF) {
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                        } else {
                            p = save;
                        }
                    }
                    append_utf8(out, cp);
                    break;
                }
                default:
                    return false;
            }
        } else {
            out.push_back(c);
            ++p;
        }
    }
    return false;
}

void skip_whitespace(const char *& p, const char * end) {
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
}

bool skip_value(const char *& p, const char * end) {
    skip_whitespace(p, end);
    if (p == end) return false;
    char c = *p;
    if (c == '"') {
        std::string ignored;
        return parse_json_string(p, end, ignored);
    }
    if (c == '{' || c == '[') {
        const char open = c;
        const char close = (open == '{') ? '}' : ']';
        ++p;
        int depth = 1;
        while (p < end && depth > 0) {
            char d = *p;
            if (d == '"') {
                std::string ignored;
                if (!parse_json_string(p, end, ignored)) return false;
                continue;
            }
            if (d == open) ++depth;
            if (d == close) --depth;
            ++p;
        }
        return depth == 0;
    }
    // number / true / false / null: consume until a delimiter
    while (p < end && *p != ',' && *p != '}' && *p != ']') ++p;
    return p > end - 1 || *(p) == ',' || *(p) == '}' || *(p) == ']';
}

bool parse_envelope(const char * json, size_t length, Envelope & out) {
    const char * p = json;
    const char * end = json + length;
    skip_whitespace(p, end);
    if (p == end || *p != '{') return false;
    ++p;
    skip_whitespace(p, end);
    if (p != end && *p == '}') return true;
    while (p < end) {
        std::string key;
        if (!parse_json_string(p, end, key)) return false;
        skip_whitespace(p, end);
        if (p == end || *p != ':') return false;
        ++p;
        skip_whitespace(p, end);
        if (key == "system") {
            if (!parse_json_string(p, end, out.system)) return false;
        } else if (key == "feed") {
            if (p == end || *p != '[') return false;
            ++p;
            skip_whitespace(p, end);
            if (p != end && *p == ']') {
                ++p;
            } else {
                while (p < end) {
                    std::string item;
                    if (!parse_json_string(p, end, item)) return false;
                    out.feed.push_back(std::move(item));
                    skip_whitespace(p, end);
                    if (p != end && *p == ',') {
                        ++p;
                        skip_whitespace(p, end);
                        continue;
                    }
                    if (p != end && *p == ']') {
                        ++p;
                        break;
                    }
                    return false;
                }
            }
        } else if (key == "reset") {
            if (end - p >= 4 && std::strncmp(p, "true", 4) == 0) {
                out.reset = true;
                p += 4;
            } else if (end - p >= 5 && std::strncmp(p, "false", 5) == 0) {
                out.reset = false;
                p += 5;
            } else {
                return false;
            }
        } else if (key == "max_new_tokens") {
            int value = 0;
            bool any = false;
            while (p < end && *p >= '0' && *p <= '9') {
                value = value * 10 + (*p - '0');
                ++p;
                any = true;
            }
            if (!any) return false;
            out.max_new_tokens = value;
        } else {
            if (!skip_value(p, end)) return false;
        }
        skip_whitespace(p, end);
        if (p != end && *p == ',') {
            ++p;
            skip_whitespace(p, end);
            continue;
        }
        if (p != end && *p == '}') {
            ++p;
            return true;
        }
        return false;
    }
    return false;
}

// Minimal JSON string escaper for the two bridge-generated envelopes.
std::string json_escape(const std::string & value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(c);
                }
        }
    }
    return out;
}

std::string error_envelope(const std::string & message) {
    return "{\"success\":false,\"error\":\"" + json_escape(message) + "\"}";
}

// Reads one .cact file's bytes into the path-keyed store. The engine maps the
// bytes in place, so a successful needle_load() transfers ownership: the entry
// stays until its model kind is definitively replaced. A path that is already
// resident reuses its original buffer - re-reading could free memory a loaded
// model still maps, so a file replaced on disk is only picked up after a
// process restart (the same-path fast paths already behave that way).
// Returns nullptr when the file cannot be read (the map is left untouched).
const std::vector<unsigned char> * read_act_file(const std::string & path) {
    const auto existing = g_act_bytes.find(path);
    if (existing != g_act_bytes.end()) {
        return &existing->second;
    }
    FILE * file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) {
        g_bridge_error = "cannot open " + path;
        LOGE("%s", g_bridge_error.c_str());
        return nullptr;
    }
    std::fseek(file, 0, SEEK_END);
    long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (size <= 0 || size > kMaxActSize) {
        g_bridge_error = "unusable .cact size: " + std::to_string(size);
        LOGE("%s", g_bridge_error.c_str());
        std::fclose(file);
        return nullptr;
    }
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    size_t read = std::fread(bytes.data(), 1, static_cast<size_t>(size), file);
    std::fclose(file);
    if (read != static_cast<size_t>(size)) {
        g_bridge_error = "short read of " + path;
        LOGE("%s", g_bridge_error.c_str());
        return nullptr;
    }
    const auto inserted = g_act_bytes.emplace(path, std::move(bytes));
    return &inserted.first->second;
}

// The needle runtime answers with its own JSON object (function_calls,
// suppressed_calls, reasoning, confidence, prefill_tps, decode_tps,
// peak_ram_mb); the Kotlin result parser understands it natively, so the
// last generation is passed through verbatim. The continuation flag marks a
// settled no-op (after tool results) so the chat layer adds no filler text.
const char kNoopEnvelope[] =
    "{\"success\":true,\"error\":null,\"response\":\"\",\"function_calls\":[],"
    "\"suppressed_calls\":[],\"continuation\":true}";

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeEngineBackend(
        JNIEnv * env, jobject /*thiz*/) {
    return env->NewStringUTF("needle");
}

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring bundle_path) {
    std::string path;
    if (!agora::jni::read_java_path(env, bundle_path, path)) return 0;
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    g_bridge_error.clear();

    // Reloading the very same file only needs a fresh conversation.
    if (g_text_path == path && (needle_models() & NEEDLE_TEXT)) {
        needle_reset();
        g_last_system.clear();
        g_last_tools.clear();
        g_initialized = false;
        return 1;
    }

    const std::vector<unsigned char> * bytes = read_act_file(path);
    if (bytes == nullptr) return 0;
    const int models_before = needle_models();
    const int rc = needle_load(
        bytes->data(), static_cast<unsigned long long>(bytes->size()));
    if (rc != 0) {
        g_bridge_error = needle_last_error() ? needle_last_error() : "needle_load failed";
        LOGE("needle_load failed: rc=%d err=%s", rc, g_bridge_error.c_str());
        // Nothing was loaded from these bytes; the engine does not map them.
        g_act_bytes.erase(path);
        return 0;
    }

    // needle_load reads whichever kind the .cact carries and keeps the other:
    // reconcile which slot owns the file now. needle_models() only reveals a
    // newly-added kind, so the one ambiguous case (both kinds resident, the
    // file replaced one of them) is resolved by comparing the bytes with the
    // already-loaded speech model. Freeing a still-mapped buffer is the one
    // unrecoverable mistake; retaining a replaced one only costs its file size.
    const int models_after = needle_models();
    const bool was_text = (models_before & NEEDLE_TEXT) != 0;
    const bool was_speech = (models_before & NEEDLE_SPEECH) != 0;
    const bool now_speech = (models_after & NEEDLE_SPEECH) != 0;
    bool carried_speech = !was_speech && now_speech;
    if (!carried_speech && was_speech && was_text) {
        const auto loaded = g_act_bytes.find(g_speech_path);
        carried_speech = loaded != g_act_bytes.end() &&
            loaded->second.size() == bytes->size() &&
            std::memcmp(loaded->second.data(), bytes->data(), bytes->size()) == 0;
    }
    if (carried_speech) {
        // The file carried a speech model (Whistle): it cannot be a chat model.
        g_speech_path = path;
        g_bridge_error = "this .cact carries a speech model, not a text model";
        LOGE("%s", g_bridge_error.c_str());
        return 0;
    }
    if (g_text_path != path) {
        // Safe to forget the replaced text model's bytes only when it cannot
        // still be mapped: speech was not loaded, so the engine's text model
        // necessarily came from this file. With both kinds resident the old
        // text bytes are retained instead (bounded by the files ever loaded).
        if (!was_speech) g_act_bytes.erase(g_text_path);
        g_text_path = path;
    }
    g_last_system.clear();
    g_last_tools.clear();
    g_initialized = false;
    return 1;
}

// Loads one .cact as the process's NEEDLE_SPEECH model (Whistle). The speech
// model is independent of the chat model: loading it never disturbs the text
// model, and the engine keeps both resident once loaded. Returns JNI_FALSE
// with g_bridge_error set when the file cannot be read or carries a text
// model instead.
JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_CactusSpeechEngine_nativeLoadSpeechModel(
        JNIEnv * env, jobject /*thiz*/, jstring model_path) {
    std::string path;
    if (!agora::jni::read_java_path(env, model_path, path)) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    g_bridge_error.clear();

    if (g_speech_path == path && (needle_models() & NEEDLE_SPEECH)) {
        return JNI_TRUE;
    }

    const std::vector<unsigned char> * bytes = read_act_file(path);
    if (bytes == nullptr) return JNI_FALSE;
    const int models_before = needle_models();
    const int rc = needle_load(
        bytes->data(), static_cast<unsigned long long>(bytes->size()));
    if (rc != 0) {
        g_bridge_error = needle_last_error() ? needle_last_error() : "needle_load failed";
        LOGE("needle_load failed: rc=%d err=%s", rc, g_bridge_error.c_str());
        g_act_bytes.erase(path);
        return JNI_FALSE;
    }

    const int models_after = needle_models();
    const bool was_text = (models_before & NEEDLE_TEXT) != 0;
    const bool was_speech = (models_before & NEEDLE_SPEECH) != 0;
    const bool now_text = (models_after & NEEDLE_TEXT) != 0;
    const bool now_speech = (models_after & NEEDLE_SPEECH) != 0;
    bool carried_text = !was_text && now_text;
    if (!carried_text && !now_speech) {
        // A text file with no speech model anywhere: the text model was
        // replaced by it and nothing usable for the mic remains.
        carried_text = true;
    }
    if (!carried_text && was_text && was_speech) {
        // Both kinds were resident and the model bits alone cannot tell which
        // one this file replaced: byte-identical to the loaded text model
        // means it carried the text model.
        const auto loaded = g_act_bytes.find(g_text_path);
        carried_text = loaded != g_act_bytes.end() &&
            loaded->second.size() == bytes->size() &&
            std::memcmp(loaded->second.data(), bytes->data(), bytes->size()) == 0;
    }
    if (carried_text) {
        // The file carried a text model (Needle 3): it cannot transcribe.
        g_text_path = path;
        g_bridge_error = "this .cact carries a text model, not a speech model";
        LOGE("%s", g_bridge_error.c_str());
        return JNI_FALSE;
    }
    if (g_speech_path != path) {
        // The speech model was replaced; its predecessor's bytes are only
        // safe to drop when no text model shares the ambiguity (see above).
        if (!was_text) g_act_bytes.erase(g_speech_path);
        g_speech_path = path;
    }
    return JNI_TRUE;
}

// Transcribes 16 kHz mono float PCM in [-1, 1] (at most 30 s) with the loaded
// speech model. Returns the engine's own JSON verbatim
// ({"text","language","ttft_ms","decode_tps"}) or an error envelope. The call
// serializes with chat generation on the engine's single mutex.
JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_CactusSpeechEngine_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/, jfloatArray pcm, jint samples,
        jstring language, jstring keywords) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    g_bridge_error.clear();
    if (pcm == nullptr || samples <= 0) {
        g_bridge_error = "no audio was captured";
        return env->NewStringUTF(error_envelope(g_bridge_error).c_str());
    }
    const jsize capacity = env->GetArrayLength(pcm);
    if (samples > capacity) {
        g_bridge_error = "audio sample count exceeds the buffer";
        return env->NewStringUTF(error_envelope(g_bridge_error).c_str());
    }
    if (!(needle_models() & NEEDLE_SPEECH)) {
        g_bridge_error = "no speech model is loaded";
        return env->NewStringUTF(error_envelope(g_bridge_error).c_str());
    }

    std::string language_code;
    if (language != nullptr &&
        !agora::jni::read_java_string(env, language, language_code)) {
        return env->NewStringUTF(error_envelope("invalid language argument").c_str());
    }
    std::string keywords_text;
    if (keywords != nullptr &&
        !agora::jni::read_java_string(env, keywords, keywords_text)) {
        return env->NewStringUTF(error_envelope("invalid keywords argument").c_str());
    }

    jfloat * audio = env->GetFloatArrayElements(pcm, nullptr);
    if (audio == nullptr) {
        return env->NewStringUTF(error_envelope("cannot access the audio buffer").c_str());
    }
    std::vector<char> out(kOutCapacity);
    const int rc = needle_transcribe(
        audio, samples,
        language != nullptr ? language_code.c_str() : nullptr,
        keywords != nullptr ? keywords_text.c_str() : nullptr,
        0, out.data(), kOutCapacity);
    env->ReleaseFloatArrayElements(pcm, audio, JNI_ABORT);
    if (rc < 0) {
        const char * engine_error = needle_last_error();
        g_bridge_error = engine_error ? engine_error : "needle_transcribe failed";
        LOGE("needle_transcribe failed: %s", g_bridge_error.c_str());
        return env->NewStringUTF(error_envelope(g_bridge_error).c_str());
    }
    return env->NewStringUTF(out.data());
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong /*handle*/) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    // The runtime has no unload API and its weights are mapped in place, so
    // the model stays resident until another .cact replaces it or the
    // process dies. Rewind the conversation so the next session starts clean.
    needle_reset();
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeStop(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong /*handle*/) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    // Generation itself cannot be interrupted (the C API is synchronous and
    // offers no callback); stop further history replay for this request.
    g_stop_requested = true;
}

// Returns the number of response bytes written into response_buffer, or -1 on
// failure. messagesJson carries the needle session envelope; the response is
// the engine's own JSON for the last fed turn, verbatim.
JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeComplete(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jstring messages_json, jstring /*options_json*/, jstring tools_json,
        jobject /*token_callback*/, jbyteArray response_buffer) {
    if (handle == 0) return -1;

    std::string envelopeJson;
    if (!agora::jni::read_java_string(env, messages_json, envelopeJson)) return -1;
    std::string tools;
    if (tools_json != nullptr && !agora::jni::read_java_string(env, tools_json, tools)) {
        return -1;
    }
    if (tools.empty()) tools = "[]";

    Envelope envelope;
    if (!parse_envelope(envelopeJson.c_str(), envelopeJson.size(), envelope)) {
        g_bridge_error = "needle bridge: malformed session envelope";
        LOGE("%s", g_bridge_error.c_str());
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_needle_mutex);
    g_stop_requested = false;
    g_bridge_error.clear();

    if (!(needle_models() & NEEDLE_TEXT)) {
        g_bridge_error = "needle bridge: no text model is loaded";
        return -1;
    }

    if (envelope.reset) {
        needle_reset();
    }

    if (!g_initialized || envelope.system != g_last_system || tools != g_last_tools) {
        const int rc = needle_init(envelope.system.c_str(), tools.c_str(), nullptr);
        if (rc < 0) {
            const char * engine_error = needle_last_error();
            g_bridge_error = engine_error ? engine_error : "needle_init failed";
            LOGE("needle_init failed: %s", g_bridge_error.c_str());
            g_initialized = false;
        } else {
            g_initialized = true;
            g_last_system = envelope.system;
            g_last_tools = tools;
        }
    }

    std::string response;
    bool generated = false;
    bool stoppedEarly = false;
    if (g_initialized) {
        std::vector<char> out(kOutCapacity);
        for (const std::string & turn : envelope.feed) {
            if (g_stop_requested) {
                stoppedEarly = true;
                break;
            }
            const int rc = needle_complete(
                turn.c_str(), nullptr, 0, envelope.max_new_tokens,
                out.data(), kOutCapacity);
            if (rc < 0) {
                const char * engine_error = needle_last_error();
                g_bridge_error = engine_error ? engine_error : "needle_complete failed";
                LOGE("needle_complete failed: %s", g_bridge_error.c_str());
                return -1;
            }
            response.assign(out.data(), strnlen(out.data(), kOutCapacity));
            generated = true;
        }
    } else {
        return -1;
    }
    if (stoppedEarly) {
        // Report the interruption instead of a partial success so the Kotlin
        // session keeps its conversation shadow unadvanced and the next
        // request realigns with a full replay.
        g_bridge_error = "stopped";
        return -1;
    }
    if (!generated) {
        // A continuation after tool results: nothing new to route, answer
        // with the settled no-op envelope.
        response = kNoopEnvelope;
    }

    const jsize capacity = env->GetArrayLength(response_buffer);
    if (static_cast<jsize>(response.size()) > capacity) {
        g_bridge_error = "needle bridge: response exceeds the transfer buffer";
        return -1;
    }
    env->SetByteArrayRegion(
        response_buffer, 0, static_cast<jsize>(response.size()),
        reinterpret_cast<const jbyte *>(response.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return static_cast<jint>(response.size());
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeGetLastError(
        JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    const char * message = g_bridge_error.empty() ? needle_last_error() : g_bridge_error.c_str();
    if (message == nullptr) message = "";
    return env->NewStringUTF(message);
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeSetLogLevel(
        JNIEnv * /*env*/, jobject /*thiz*/, jint /*level*/) {
    // The needle runtime has no configurable log level.
}

} // extern "C"
