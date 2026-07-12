#include "llama-ahsma.h"

#include "ggml-backend.h"
#include "llama-kv-cache.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <map>
#include <set>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace {

static float read_route_value(const uint8_t * row, ggml_type type, uint32_t idx) {
    switch (type) {
        case GGML_TYPE_F32:
            return ((const float *) row)[idx];
        case GGML_TYPE_F16:
            return ggml_fp16_to_fp32(((const ggml_fp16_t *) row)[idx]);
        case GGML_TYPE_BF16:
            return ggml_bf16_to_fp32(((const ggml_bf16_t *) row)[idx]);
        default:
            return 0.0f;
    }
}

static float fallback_route_value(const llama_ahsma_token_ref & ref, uint32_t d) {
    const float pos_term = std::sin(0.013f * float(ref.logical_pos + 1) * float(d + 1));
    const float cell_term = std::cos(0.017f * float(ref.physical_cell + 1) * float(d + 1));
    return pos_term + cell_term;
}

static void normalize(std::vector<float> & values) {
    float norm = 0.0f;
    for (float x : values) {
        norm += x * x;
    }
    norm = std::sqrt(std::max(norm, 1e-20f));
    for (float & x : values) {
        x /= norm;
    }
}

} // namespace

struct llama_ahsma_index::impl {
    explicit impl(llama_ahsma_params p) : cfg(p) {}

    struct block_state {
        int32_t seq_id = -1;
        int64_t first_pos = 0;
        int64_t last_pos = 0;
        std::vector<uint32_t> cells;
        std::vector<float> summary;
    };

    struct layer_state {
        uint32_t n_kv = 0;
        uint64_t generation = 0;
        uint64_t cached_generation = 0;
        uint64_t cached_step = 0;
        bool cache_valid = false;
        std::vector<llama_ahsma_token_ref> refs;
        std::vector<block_state> blocks;
        llama_ahsma_route cached;
    };

    llama_ahsma_params cfg;
    std::unordered_map<int32_t, layer_state> layers;
};

llama_ahsma_index::llama_ahsma_index(llama_ahsma_params params)
    : pimpl(new impl(params)) {
    if (params.block_size == 0 || params.route_dim == 0) {
        throw std::invalid_argument("invalid AHSMA parameters");
    }
}

llama_ahsma_index::~llama_ahsma_index() = default;

const llama_ahsma_params & llama_ahsma_index::params() const {
    return pimpl->cfg;
}

bool llama_ahsma_index::is_enabled() const {
    return pimpl->cfg.enabled;
}

void llama_ahsma_index::update_layer(
        const llama_kv_cache & cache,
        int32_t layer,
        uint32_t n_kv) {
    auto & state = pimpl->layers[layer];
    state.n_kv = n_kv == 0 ? cache.get_size() : n_kv;
    state.generation++;
    state.cache_valid = false;
    state.refs = cache.collect_ahsma_refs(layer);
    state.blocks.clear();

    if (state.refs.empty()) {
        return;
    }

    const ggml_tensor * storage = cache.get_k_storage(layer);
    if (storage == nullptr) {
        throw std::runtime_error("AHSMA cache storage is unavailable");
    }

    const bool direct_route = storage->type == GGML_TYPE_F32 ||
                              storage->type == GGML_TYPE_F16 ||
                              storage->type == GGML_TYPE_BF16;

    const uint32_t route_dim = pimpl->cfg.route_dim;
    const uint32_t row_elems = storage->ne[0];
    const uint32_t route_dim_read = std::min(route_dim, row_elems);
    const size_t row_bytes = ggml_row_size(storage->type, row_elems);
    const size_t total_bytes = ggml_nbytes(storage);
    std::vector<uint8_t> raw;

    if (direct_route) {
        raw.resize(total_bytes);
        ggml_backend_tensor_get(storage, raw.data(), 0, raw.size());
    }

    std::vector<std::size_t> order(state.refs.size());
    for (std::size_t i = 0; i < order.size(); ++i) {
        order[i] = i;
    }

    std::sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) {
        const auto & ra = state.refs[a];
        const auto & rb = state.refs[b];
        if (ra.seq_id != rb.seq_id) {
            return ra.seq_id < rb.seq_id;
        }
        if (ra.logical_pos != rb.logical_pos) {
            return ra.logical_pos < rb.logical_pos;
        }
        if (ra.stream != rb.stream) {
            return ra.stream < rb.stream;
        }
        return ra.physical_cell < rb.physical_cell;
    });

    std::map<int32_t, std::vector<std::size_t>> per_seq;
    for (std::size_t idx : order) {
        per_seq[state.refs[idx].seq_id].push_back(idx);
    }

    for (auto & [seq_id, indices] : per_seq) {
        (void) seq_id;
        for (std::size_t begin = 0; begin < indices.size(); begin += pimpl->cfg.block_size) {
            const std::size_t end = std::min(indices.size(), begin + pimpl->cfg.block_size);

            impl::block_state block;
            block.seq_id = state.refs[indices[begin]].seq_id;
            block.first_pos = state.refs[indices[begin]].logical_pos;
            block.last_pos = state.refs[indices[end - 1]].logical_pos;
            block.summary.assign(route_dim, 0.0f);

            for (std::size_t j = begin; j < end; ++j) {
                const auto & ref = state.refs[indices[j]];
                block.cells.push_back(ref.physical_cell);

                if (!direct_route) {
                    for (uint32_t d = 0; d < route_dim; ++d) {
                        block.summary[d] += fallback_route_value(ref, d);
                    }
                    continue;
                }

                const size_t row_index = static_cast<size_t>(ref.physical_cell);
                const size_t byte_offset = row_index * row_bytes;
                if (byte_offset + row_bytes > total_bytes) {
                    continue;
                }

                const uint8_t * row = raw.data() + byte_offset;
                for (uint32_t d = 0; d < route_dim_read; ++d) {
                    block.summary[d] += read_route_value(row, storage->type, d);
                }
            }

            normalize(block.summary);
            state.blocks.push_back(std::move(block));
        }
    }
}

void llama_ahsma_index::update_from_cache(const llama_kv_cache & cache) {
    for (const auto & layer : cache.get_layer_ids()) {
        update_layer(cache, static_cast<int32_t>(layer), cache.get_size());
    }
}

llama_ahsma_route llama_ahsma_index::route(
        int32_t layer,
        const float * q_route,
        uint32_t route_dim,
        uint32_t n_kv,
        uint64_t decode_step) {
    if (q_route == nullptr) {
        throw std::invalid_argument("AHSMA query is null");
    }
    if (route_dim != pimpl->cfg.route_dim) {
        throw std::invalid_argument("AHSMA routing dimension mismatch");
    }

    auto & state = pimpl->layers[layer];
    if (state.cache_valid &&
        state.cached_generation == state.generation &&
        decode_step >= state.cached_step &&
        decode_step - state.cached_step < pimpl->cfg.route_refresh) {
        auto out = state.cached;
        out.reused = true;
        return out;
    }

    llama_ahsma_route out;
    const int64_t current_pos = n_kv > 0 ? static_cast<int64_t>(n_kv - 1) : 0;
    const int64_t local_begin =
        current_pos > static_cast<int64_t>(pimpl->cfg.local_window)
            ? current_pos - static_cast<int64_t>(pimpl->cfg.local_window)
            : 0;

    std::set<uint32_t> selected;
    std::vector<std::pair<float, std::size_t>> scored;

    for (std::size_t bi = 0; bi < state.blocks.size(); ++bi) {
        const auto & block = state.blocks[bi];
        if (block.first_pos > current_pos) {
            continue;
        }

        if (block.last_pos >= local_begin) {
            selected.insert(block.cells.begin(), block.cells.end());
            continue;
        }

        float score = 0.0f;
        for (uint32_t d = 0; d < route_dim; ++d) {
            score += q_route[d] * block.summary[d];
        }
        scored.emplace_back(score, bi);
    }

    const std::size_t keep = std::min<std::size_t>(pimpl->cfg.retrieved_blocks, scored.size());
    if (keep < scored.size()) {
        std::nth_element(
            scored.begin(),
            scored.begin() + static_cast<std::ptrdiff_t>(keep),
            scored.end(),
            [](const auto & a, const auto & b) { return a.first > b.first; });
        scored.resize(keep);
    }

    std::sort(scored.begin(), scored.end(), [](const auto & a, const auto & b) {
        return a.second < b.second;
    });

    for (const auto & [score, block_id] : scored) {
        (void) score;
        out.block_ids.push_back(static_cast<uint32_t>(block_id));
        selected.insert(state.blocks[block_id].cells.begin(), state.blocks[block_id].cells.end());
    }

    for (const auto & ref : state.refs) {
        if (ref.logical_pos <= current_pos &&
            ref.logical_pos < static_cast<int64_t>(pimpl->cfg.global_tokens)) {
            selected.insert(ref.physical_cell);
        }
    }

    out.token_ids.assign(selected.begin(), selected.end());
    out.generation = decode_step;

    state.cached = out;
    state.cached_step = decode_step;
    state.cached_generation = state.generation;
    state.cache_valid = true;
    return out;
}

void llama_ahsma_index::clear() {
    pimpl->layers.clear();
}
