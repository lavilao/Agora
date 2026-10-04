#pragma once

// Speculative decoding runtime for the embedded llama.cpp engine, wired after
// koboldcpp's --usemtp / --draftmodel / --draftamount options. The llama.cpp build
// vendored here exposes the n-gram speculators (ngram_mod, ngram_simple, ngram_map_k,
// ngram_map_k4v) and the classic draft-model path; MTP layers are not yet consumed by
// this pin, so "draft" + a draft GGUF stands in for the MTP toggle.

#include "llama_chat_handle.h"
#include "llama_chat_options.h"

namespace agora::chat {

// Creates the speculator on the handle when the options ask for one. The draft model
// (if any) is loaded with the same backend pinning as the target model. Returns false
// only on a hard failure that should fail the whole model load.
bool spec_runtime_init(
    ChatHandle * handle,
    const EngineOptions & options,
    ggml_backend_dev_t device
);

// Releases the speculator and the draft model in the correct order (the speculator's
// contexts outlive the model they evaluate).
void spec_runtime_free(ChatHandle * handle);

} // namespace agora::chat
