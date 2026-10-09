// Agora's JNI boundary for the Cactus alternative engine (thirdparty/cactus).
//
// Cactus is a self-contained C++ inference engine (Cactus Engine + Graph +
// Kernels) that loads prebuilt ".cactus" bundle directories: config.txt,
// vocab.txt, components/manifest.json and *.weights files produced by the
// Cactus transpiler. It does not consume GGUF models.
//
// This wrapper follows the same conventions as the llama.cpp boundary:
//   - every Java string crosses through jni_utf8.h (standard UTF-8),
//   - the process-wide resident handle is owned by the Kotlin layer,
//   - streaming tokens cross one callback object returning boolean; when the
//     Kotlin consumer disappeared the bridge asks the engine to stop early.
//
// Privacy: Cactus ships opt-out telemetry (Supabase) and cloud handoff. Both
// are hard-disabled through process environment flags at library load time,
// before any cactus_init() can observe them, and every completion request also
// passes telemetry_enabled=false / auto_handoff=false. The engine then runs
// fully on device with no network access.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "cactus_engine.h"
#include "jni_utf8.h"

namespace {

struct TokenCallbackContext {
    JavaVM * jvm;
    jobject callback;
    jmethodID method;
    cactus_model_t model;
};

void token_callback_bridge(const char * token, uint32_t /*token_id*/, void * user_data) {
    auto * ctx = static_cast<TokenCallbackContext *>(user_data);
    if (!ctx || !ctx->callback || !token) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    jint status = ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (!env) return;
    // An empty token only marks the end of a stream the Kotlin layer already
    // receives through the final response JSON, so nothing is forwarded.
    jstring jtoken = env->NewStringUTF(token);
    jboolean consumed = JNI_TRUE;
    if (jtoken) {
        consumed = env->CallBooleanMethod(ctx->callback, ctx->method, jtoken);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            consumed = JNI_TRUE;
        }
        env->DeleteLocalRef(jtoken);
    }
    if (!consumed) {
        // The Kotlin stream consumer is gone: abort the remaining decode.
        cactus_stop(ctx->model);
    }
    if (attached) ctx->jvm->DetachCurrentThread();
}

bool g_privacy_flags_applied = false;

void apply_cactus_privacy_flags() {
    if (g_privacy_flags_applied) return;
    g_privacy_flags_applied = true;
    // Suppress the engine's Supabase telemetry dispatcher and any cloud
    // handoff path. apply_no_cloud_telemetry_env() runs inside cactus_init()
    // and reads these before the first telemetry event can be enqueued.
    setenv("CACTUS_NO_CLOUD_TELE", "1", 1);
    setenv("CACTUS_DISABLE_CLOUD_HANDOFF", "1", 1);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
    apply_cactus_privacy_flags();
    return JNI_VERSION_1_6;
}

// Which engine flavour this library implements: "cactus" for the full
// runtime built from source (arm64), "needle" for the prebuilt armv7
// runtime. CactusEngine.backendKind() surfaces it to Kotlin.
JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeEngineBackend(
        JNIEnv * env, jobject /*thiz*/) {
    return env->NewStringUTF("cactus");
}

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring bundle_path) {
    std::string path;
    if (!agora::jni::read_java_path(env, bundle_path, path)) return 0;
    apply_cactus_privacy_flags();
    return reinterpret_cast<jlong>(cactus_init(path.c_str(), nullptr, false));
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) {
        cactus_destroy(reinterpret_cast<cactus_model_t>(handle));
    }
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeStop(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0) {
        cactus_stop(reinterpret_cast<cactus_model_t>(handle));
    }
}

// Returns the number of response bytes written into response_buffer, or -1 on
// failure. When token_callback is non-null its onToken(String) method runs for
// every generated token; returning false from Kotlin aborts generation.
JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeComplete(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jstring messages_json, jstring options_json, jstring tools_json,
        jobject token_callback, jbyteArray response_buffer) {
    if (handle == 0) return -1;
    std::string messages;
    std::string options;
    std::string tools;
    if (!agora::jni::read_java_string(env, messages_json, messages)) return -1;
    if (options_json && !agora::jni::read_java_string(env, options_json, options)) return -1;
    if (tools_json && !agora::jni::read_java_string(env, tools_json, tools)) return -1;

    TokenCallbackContext ctx{};
    if (token_callback) {
        JavaVM * jvm = nullptr;
        if (env->GetJavaVM(&jvm) != JNI_OK) return -1;
        jclass callback_class = env->GetObjectClass(token_callback);
        if (!callback_class) return -1;
        ctx.jvm = jvm;
        ctx.model = reinterpret_cast<cactus_model_t>(handle);
        ctx.callback = env->NewGlobalRef(token_callback);
        ctx.method = env->GetMethodID(
            callback_class, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(callback_class);
        if (!ctx.method || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (ctx.callback) env->DeleteGlobalRef(ctx.callback);
            return -1;
        }
    }

    const size_t capacity = static_cast<size_t>(env->GetArrayLength(response_buffer));
    std::string response;
    response.resize(capacity > 0 ? capacity : 1);

    const int rc = cactus_complete(
        reinterpret_cast<cactus_model_t>(handle),
        messages.c_str(),
        response.data(),
        response.size(),
        options.empty() ? nullptr : options.c_str(),
        tools.empty() ? nullptr : tools.c_str(),
        ctx.callback ? token_callback_bridge : nullptr,
        ctx.callback ? &ctx : nullptr,
        nullptr,
        0);

    if (ctx.callback) {
        env->DeleteGlobalRef(ctx.callback);
        ctx.callback = nullptr;
    }

    if (rc < 0) {
        return -1;
    }

    // cactus_complete NUL-terminates the JSON inside the buffer; copy the
    // logical length back into the managed array.
    const size_t written = strnlen(response.c_str(), response.size());
    if (written > capacity) return -1;
    env->SetByteArrayRegion(
        response_buffer, 0, static_cast<jsize>(written),
        reinterpret_cast<const jbyte *>(response.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return static_cast<jint>(written);
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeGetLastError(
        JNIEnv * env, jobject /*thiz*/) {
    const char * message = cactus_get_last_error();
    if (!message) return env->NewStringUTF("");
    // The native message is produced by the engine in standard UTF-8.
    return env->NewStringUTF(message);
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_CactusEngine_nativeSetLogLevel(
        JNIEnv * /*env*/, jobject /*thiz*/, jint level) {
    cactus_log_set_level(static_cast<int>(level));
}

} // extern "C"
