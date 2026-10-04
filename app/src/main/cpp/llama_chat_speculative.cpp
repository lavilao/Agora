#include "llama_chat_speculative.h"
#include "llama_chat_log.h"
#include <cstring>

namespace agora::chat {

bool spec_runtime_init(
    ChatHandle * handle,
    const EngineOptions & options,
    ggml_backend_dev_t device
) {
    spec_runtime_free(handle);
    if (options.speculative_type == "off" || !handle->ctx) return true;
    // Speculative rounds trim unaccepted draft cells from the target KV, which requires
    // partial sequence removal. Recurrent-style memories cannot do that, so they stay on
    // the plain one-token path (and their KV reuse already falls back to full reprocess).
    if (common_context_can_seq_rm(handle->ctx) != COMMON_CONTEXT_SEQ_RM_TYPE_PART) {
        LOGD("Speculative decoding needs a partially trimmable KV cache; disabled");
        return true;
    }

    common_params_speculative & params = handle->spec_params;
    params = common_params_speculative{};

    if (options.speculative_type == "draft") {
        if (options.draft_model_path.empty()) {
            LOGD("Speculative draft model requested but no draft path set; disabled");
            return true;
        }
        llama_model_params draft_model_params = llama_model_default_params();
        draft_model_params.use_mmap = options.use_mmap;
        ggml_backend_dev_t requested[2] = { device, nullptr };
        if (device) draft_model_params.devices = requested;
        handle->draft_model = llama_model_load_from_file(
            options.draft_model_path.c_str(), draft_model_params
        );
        if (!handle->draft_model) {
            // A broken draft model must not take the target model down with it.
            LOGE("Failed to load draft model %s; speculative decoding disabled",
                 options.draft_model_path.c_str());
            return true;
        }
        params.draft.mparams.path = options.draft_model_path;
        params.draft.n_max = options.spec_draft_amount;
        params.draft.n_min = 0;
        params.draft.model = handle->draft_model;
        params.draft.cparams = llama_context_default_params();
        // The draft context tracks the target conversation plus the draft lookahead.
        params.draft.cparams.n_ctx = handle->n_ctx;
        if (options.threads > 0) {
            params.draft.cparams.n_threads = options.threads;
            params.draft.cparams.n_threads_batch = options.threads;
        }
        params.type = COMMON_SPECULATIVE_TYPE_DRAFT;
    } else if (options.speculative_type == "ngram_mod") {
        params.type = COMMON_SPECULATIVE_TYPE_NGRAM_MOD;
        params.ngram_mod.n_match = options.ngram_match;
        params.ngram_mod.n_max = options.spec_draft_amount;
        // Keep the library default floor (48) below the user ceiling.
        params.ngram_mod.n_min = std::min(48, options.spec_draft_amount);
    } else if (options.speculative_type == "ngram_simple") {
        params.type = COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE;
    } else if (options.speculative_type == "ngram_map_k") {
        params.type = COMMON_SPECULATIVE_TYPE_NGRAM_MAP_K;
    } else if (options.speculative_type == "ngram_map_k4v") {
        params.type = COMMON_SPECULATIVE_TYPE_NGRAM_MAP_K4V;
    } else {
        return true;
    }

    handle->spec = common_speculative_init(params, handle->ctx);
    if (!handle->spec) {
        LOGD("Speculative decoding unavailable for type %s", options.speculative_type.c_str());
        params = common_params_speculative{};
        return true;
    }
    LOGD("Speculative decoding active: type=%s, draft_amount=%d, ngram_match=%d",
         options.speculative_type.c_str(), options.spec_draft_amount, options.ngram_match);
    return true;
}

void spec_runtime_free(ChatHandle * handle) {
    if (handle->spec) {
        common_speculative_free(handle->spec);
        handle->spec = nullptr;
    }
    // The speculator frees the draft contexts it created; the model itself is ours.
    if (handle->draft_model) {
        llama_model_free(handle->draft_model);
        handle->draft_model = nullptr;
    }
    handle->spec_params = common_params_speculative{};
}

} // namespace agora::chat
