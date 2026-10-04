#pragma once

#include "llama.h"
#include "chat.h"
#include "speculative.h"
#include "mtmd.h"
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace agora::chat {

struct EngineOptions;

// One saved KV cache snapshot. The token mirror doubles as the match key: a snapshot is
// worth restoring only while it is a longer prefix of the incoming prompt than the live
// cache (koboldcpp --smartcache, "saving KV cache snapshots to RAM").
struct KvSnapshot {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
    uint64_t last_used = 0;
};

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

    // ── Engine options as loaded; gates cache behavior during generation ──
    bool smart_context = true;
    bool context_shift = true;
    bool fast_forward = true;
    bool smart_cache = false;

    // ── Speculative decoding runtime (koboldcpp --usemtp/--draftmodel/--draftamount) ──
    // spec_params must outlive spec: the n-gram containers and the draft model pointer it
    // carries are owned here and referenced by the speculator.
    common_params_speculative spec_params;
    common_speculative * spec = nullptr;
    llama_model * draft_model = nullptr;

    // ── Smart cache slots (koboldcpp --smartcache [limit]) ──
    std::vector<KvSnapshot> snapshots;
    int32_t smart_cache_slots = 1;
    uint64_t snapshot_clock = 0;
};

} // namespace agora::chat
