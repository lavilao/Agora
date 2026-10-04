#include "llama_chat_generation.h"
#include <algorithm>

namespace agora::chat {

static constexpr int32_t PENALTY_LAST_N = 64;

void clear_text_cache(ChatHandle * handle) {
    if (handle->ctx) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
    }
    handle->decoded_tokens.clear();
}

// Longest suffix of the decoded cache that equals a prefix of the new prompt.
//
// The provider window can drop the OLDEST turns between requests, so the prompt is not
// guaranteed to extend the previous one: after a trim it starts mid-conversation while the
// cache still holds the trimmed head. A plain prefix match then degrades to zero and every
// request would re-prefill the whole conversation. Matching a cache SUFFIX against the
// prompt prefix instead covers both shapes: a normal follow-up message (whole cache kept)
// and a windowed one (stale head dropped below).
//
// Classic KMP with the failure function built over the prompt truncated to
// min(|cache|, |prompt|) — no prefix longer than that can be a suffix of the cache — and
// a single scan of the cache. O(|cache| + |prompt|), microseconds at context sizes.
static size_t longest_suffix_prefix_overlap(
    const std::vector<llama_token> & cache,
    const std::vector<llama_token> & prompt
) {
    if (cache.empty() || prompt.empty()) return 0;
    const size_t max_overlap = std::min(cache.size(), prompt.size());
    std::vector<size_t> failure(max_overlap, 0);
    for (size_t i = 1; i < max_overlap; ++i) {
        size_t matched = failure[i - 1];
        while (matched > 0 && prompt[i] != prompt[matched]) {
            matched = failure[matched - 1];
        }
        if (prompt[i] == prompt[matched]) ++matched;
        failure[i] = matched;
    }
    size_t matched = 0;
    for (const llama_token token : cache) {
        while (matched > 0 &&
               (matched >= max_overlap || token != prompt[matched])) {
            matched = failure[matched - 1];
        }
        if (matched < max_overlap && token == prompt[matched]) ++matched;
    }
    return matched;
}

size_t prepare_text_cache(
    ChatHandle * handle,
    const std::vector<llama_token> & prompt_tokens
) {
    const std::vector<llama_token> & cache = handle->decoded_tokens;
    // Plain prefix: the new prompt still begins with everything decoded so far. This covers
    // the normal follow-up (prompt extends the cache) and regeneration (prompt equals the
    // cached head while a stale generated tail hangs behind it).
    size_t prefix = 0;
    const size_t prefix_max = std::min(cache.size(), prompt_tokens.size());
    while (prefix < prefix_max && cache[prefix] == prompt_tokens[prefix]) {
        prefix++;
    }

    size_t overlap;
    bool shift = false;
    if (prefix == cache.size() && prefix > 0) {
        // The whole cache is a prefix of the prompt: keep the cells where they are.
        overlap = prefix;
    } else {
        // The provider window may have dropped the OLDEST turns, so the prompt can start
        // mid-conversation. Match the longest cache suffix against the prompt prefix and
        // shift the survivors down instead of losing the whole cache.
        overlap = longest_suffix_prefix_overlap(cache, prompt_tokens);
        shift = overlap > prefix;
        if (!shift) overlap = prefix;
    }

    // Sampling needs logits from this request, so an exact prompt match must replay one token.
    if (overlap == prompt_tokens.size() && overlap > 0) {
        overlap--;
    }
    if (overlap == 0) {
        clear_text_cache(handle);
        return 0;
    }

    llama_memory_t memory = llama_get_memory(handle->ctx);
    const size_t cached_len = cache.size();
    if (shift) {
        // The conversation head fell out of the provider window: discard the stale cells and
        // shift the survivors down to positions [0, overlap) so the incoming tokens decode
        // contiguously after them. This is llama.cpp's "context shift": the shifted KV cells
        // stay valid because RoPE attention only depends on the distance between positions,
        // which the uniform shift preserves.
        const llama_pos drop = static_cast<llama_pos>(cached_len - overlap);
        if (!llama_memory_seq_rm(memory, 0, 0, drop)) {
            clear_text_cache(handle);
            return 0;
        }
        llama_memory_seq_add(memory, 0, 0, -1, -drop);
        // The shifted-out head is gone from the memory, so it must go from the token mirror
        // as well: the surviving tokens are the TAIL of the previous mirror.
        handle->decoded_tokens.erase(
            handle->decoded_tokens.begin(), handle->decoded_tokens.end() - overlap
        );
    } else {
        handle->decoded_tokens.resize(overlap);
    }
    // Replay back-off and stale generated tails both land here: every position from the
    // retained overlap on must be recomputed by this request.
    llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(overlap), -1);
    const llama_pos pos_min = llama_memory_seq_pos_min(memory, 0);
    const llama_pos pos_max = llama_memory_seq_pos_max(memory, 0);
    // A partial-trim failure (recurrent memory, an SWA ring that already discarded cells
    // behind its window, etc.) is reported as a mismatch instead of being trusted.
    if (pos_min != 0 || pos_max + 1 != static_cast<llama_pos>(overlap)) {
        clear_text_cache(handle);
        return 0;
    }
    return overlap;
}

bool token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & piece
) {
    char inline_buffer[256];
    int32_t length = llama_token_to_piece(
        vocab, token, inline_buffer, sizeof(inline_buffer), 0, true
    );
    if (length >= 0) {
        piece.assign(inline_buffer, static_cast<size_t>(length));
        return true;
    }
    std::vector<char> dynamic_buffer(static_cast<size_t>(-length));
    length = llama_token_to_piece(
        vocab, token, dynamic_buffer.data(), dynamic_buffer.size(), 0, true
    );
    if (length < 0) return false;
    piece.assign(dynamic_buffer.data(), static_cast<size_t>(length));
    return true;
}

common_sampler * init_chat_sampler(
    ChatHandle * handle,
    TemplateSamplingMetadata & metadata,
    float temperature,
    float top_p,
    float frequency_penalty,
    float presence_penalty,
    std::string & error
) {
    common_params_sampling params;
    params.samplers = {
        COMMON_SAMPLER_TYPE_PENALTIES,
        COMMON_SAMPLER_TYPE_MIN_P,
        COMMON_SAMPLER_TYPE_TOP_P,
        COMMON_SAMPLER_TYPE_TEMPERATURE,
    };
    params.penalty_last_n = PENALTY_LAST_N;
    params.penalty_repeat = 1.0f;
    params.penalty_freq = frequency_penalty;
    params.penalty_present = presence_penalty;
    params.min_p = 0.05f;
    params.min_keep = 1;
    params.top_p = top_p;
    params.temp = temperature;
    if (!metadata.grammar.empty()) {
        params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS, metadata.grammar };
    }
    params.grammar_lazy = metadata.grammar_lazy;
    params.generation_prompt = metadata.generation_prompt;
    for (const auto & value : metadata.preserved_tokens) {
        const auto tokens = common_tokenize(handle->vocab, value, false, true);
        if (tokens.size() == 1) {
            params.preserved_tokens.insert(tokens.front());
            metadata.preserved_token_ids.insert(tokens.front());
        }
    }
    for (const auto & source : metadata.grammar_triggers) {
        common_grammar_trigger trigger = source;
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD: {
                const auto tokens = common_tokenize(handle->vocab, trigger.value, false, true);
                if (tokens.size() == 1) {
                    if (metadata.preserved_token_ids.find(tokens.front()) ==
                        metadata.preserved_token_ids.end()) {
                        error = "Grammar trigger word is not a preserved token";
                        return nullptr;
                    }
                    trigger.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    trigger.token = tokens.front();
                }
                break;
            }
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (metadata.preserved_token_ids.find(trigger.token) ==
                    metadata.preserved_token_ids.end()) {
                    error = "Grammar trigger token is not preserved";
                    return nullptr;
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                break;
            default:
                error = "Unknown grammar trigger type";
                return nullptr;
        }
        params.grammar_triggers.push_back(std::move(trigger));
    }
    if (params.grammar_lazy && params.grammar_triggers.empty()) {
        error = "Lazy grammar requires at least one trigger";
        return nullptr;
    }
    try {
        common_sampler * sampler = common_sampler_init(handle->model, params);
        if (!sampler) error = "Unable to initialize chat sampler";
        return sampler;
    } catch (const std::exception & exception) {
        error = exception.what();
        return nullptr;
    }
}

bool is_preserved_token(
    const TemplateSamplingMetadata & metadata,
    llama_token token
) {
    return metadata.preserved_token_ids.find(token) != metadata.preserved_token_ids.end();
}

} // namespace agora::chat
