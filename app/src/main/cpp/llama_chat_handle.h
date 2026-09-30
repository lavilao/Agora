#pragma once

#include "llama.h"
#include "chat.h"
#include "mtmd.h"
#include <atomic>
#include <string>
#include <vector>

namespace agora::chat {

struct ChatHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr chat_templates;
    std::string path;
    int32_t n_ctx = 0;
    // Runtime backend the model was loaded on ("auto", "cpu", "gpu"/"vulkan").
    std::string backend_preference = "auto";
    // Human-readable description of the device that actually owns the weights.
    std::string backend_description;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> decoded_tokens;
    mtmd_context * mtmd_ctx = nullptr;  // multimodal context (for vision models)
};

} // namespace agora::chat
