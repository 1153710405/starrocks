// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once

#include <atomic>
#include <chrono>
#include <mutex>
#include <unordered_map>

#include "exec/pipeline/fragment_context.h"
#include "exec/pipeline/pipeline_fwd.h"
#include "gen_cpp/InternalService_types.h" // for TQueryOptions
#include "gen_cpp/Types_types.h"           // for TUniqueId
#include "runtime/query_statistics.h"
#include "runtime/runtime_state.h"
#include "util/debug/query_trace.h"
#include "util/hash_util.hpp"
#include "util/time.h"

namespace starrocks {
namespace pipeline {

using std::chrono::seconds;
using std::chrono::milliseconds;
using std::chrono::steady_clock;
using std::chrono::duration_cast;

// The context for all fragment of one query in one BE
class QueryContext : public std::enable_shared_from_this<QueryContext> {
public:
    QueryContext();
    ~QueryContext();
    void set_exec_env(ExecEnv* exec_env) { _exec_env = exec_env; }
    void set_query_id(const TUniqueId& query_id) { _query_id = query_id; }
    TUniqueId query_id() const { return _query_id; }
    void set_total_fragments(size_t total_fragments) { _total_fragments = total_fragments; }

    void increment_num_fragments() {
        _num_fragments.fetch_add(1);
        _num_active_fragments.fetch_add(1);
    }

    bool count_down_fragments() {
        size_t old = _num_active_fragments.fetch_sub(1);
        DCHECK_GE(old, 1);
        return old == 1;
    }
    int num_active_fragments() const { return _num_active_fragments.load(); }
    bool has_no_active_instances() { return _num_active_fragments.load() == 0; }

    void set_delivery_expire_seconds(int expire_seconds) { _delivery_expire_seconds = seconds(expire_seconds); }
    void set_query_expire_seconds(int expire_seconds) { _query_expire_seconds = seconds(expire_seconds); }
    inline int get_query_expire_seconds() const { return _query_expire_seconds.count(); }
    // now time point pass by deadline point.
    bool is_delivery_expired() const {
        auto now = duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
        return now > _delivery_deadline;
    }
    bool is_query_expired() const {
        auto now = duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
        return now > _query_deadline;
    }

    bool is_dead() const { return _num_active_fragments == 0 && _num_fragments == _total_fragments; }
    // add expired seconds to deadline
    void extend_delivery_lifetime() {
        _delivery_deadline =
                duration_cast<milliseconds>(steady_clock::now().time_since_epoch() + _delivery_expire_seconds).count();
    }
    void extend_query_lifetime() {
        _query_deadline =
                duration_cast<milliseconds>(steady_clock::now().time_since_epoch() + _query_expire_seconds).count();
    }
    void set_report_profile() { _is_report_profile = true; }
    bool is_report_profile() { return _is_report_profile; }
    void set_profile_level(const TPipelineProfileLevel::type& profile_level) { _profile_level = profile_level; }
    const TPipelineProfileLevel::type& profile_level() { return _profile_level; }

    FragmentContextManager* fragment_mgr();

    void cancel(const Status& status);

    void set_is_runtime_filter_coordinator(bool flag) { _is_runtime_filter_coordinator = flag; }

    ObjectPool* object_pool() { return &_object_pool; }
    void set_desc_tbl(DescriptorTbl* desc_tbl) {
        DCHECK(_desc_tbl == nullptr);
        _desc_tbl = desc_tbl;
    }

    DescriptorTbl* desc_tbl() {
        DCHECK(_desc_tbl != nullptr);
        return _desc_tbl;
    }
    // If option_query_mem_limit > 0, use it directly.
    // Otherwise, use per_instance_mem_limit * num_fragments * pipeline_dop.
    int64_t compute_query_mem_limit(int64_t parent_mem_limit, int64_t per_instance_mem_limit, size_t pipeline_dop,
                                    int64_t option_query_mem_limit);
    size_t total_fragments() { return _total_fragments; }
    /// Initialize the mem_tracker of this query.
    /// Positive `big_query_mem_limit` and non-null `wg` indicate
    /// that there is a big query memory limit of this resource group.
    void init_mem_tracker(int64_t query_mem_limit, MemTracker* parent, int64_t big_query_mem_limit = -1,
                          workgroup::WorkGroup* wg = nullptr);
    std::shared_ptr<MemTracker> mem_tracker() { return _mem_tracker; }

    Status init_query_once(workgroup::WorkGroup* wg);
    /// Release the workgroup token only once to avoid double-free.
    /// This method should only be invoked while the QueryContext is still valid,
    /// to avoid double-free between the destruction and this method.
    void release_workgroup_token_once();

    // Some statistic about the query, including cpu, scan_rows, scan_bytes
    int64_t mem_cost_bytes() const { return _mem_tracker->peak_consumption(); }
    void incr_cpu_cost(int64_t cost) {
        _total_cpu_cost_ns += cost;
        _delta_cpu_cost_ns += cost;
    }
    void incr_cur_scan_rows_num(int64_t rows_num) {
        _total_scan_rows_num += rows_num;
        _delta_scan_rows_num += rows_num;
    }
    void incr_cur_scan_bytes(int64_t scan_bytes) {
        _total_scan_bytes += scan_bytes;
        _delta_scan_bytes += scan_bytes;
    }
    int64_t cpu_cost() const { return _total_cpu_cost_ns; }
    int64_t cur_scan_rows_num() const { return _total_scan_rows_num; }
    int64_t get_scan_bytes() const { return _total_scan_bytes; }

    // Query start time, used to check how long the query has been running
    // To ensure that the minimum run time of the query will not be killed by the big query checking mechanism
    int64_t query_begin_time() const { return _query_begin_time; }
    void init_query_begin_time() { _query_begin_time = MonotonicNanos(); }

    void set_scan_limit(int64_t scan_limit) { _scan_limit = scan_limit; }
    int64_t get_scan_limit() const { return _scan_limit; }
    void set_query_trace(std::shared_ptr<starrocks::debug::QueryTrace> query_trace);

    starrocks::debug::QueryTrace* query_trace() { return _query_trace.get(); }

    std::shared_ptr<starrocks::debug::QueryTrace> shared_query_trace() { return _query_trace; }

    // Delta statistic since last retrieve
    std::shared_ptr<QueryStatistics> intermediate_query_statistic();
    // Merged statistic from all executor nodes
    std::shared_ptr<QueryStatistics> final_query_statistic();
    std::shared_ptr<QueryStatisticsRecvr> maintained_query_recv();
    bool is_result_sink() const { return _is_result_sink; }
    void set_result_sink(bool value) { _is_result_sink = value; }

    QueryContextPtr get_shared_ptr() { return shared_from_this(); }

public:
    static constexpr int DEFAULT_EXPIRE_SECONDS = 300;

private:
    ExecEnv* _exec_env = nullptr;
    TUniqueId _query_id;
    std::unique_ptr<FragmentContextManager> _fragment_mgr;
    size_t _total_fragments;
    std::atomic<size_t> _num_fragments;
    std::atomic<size_t> _num_active_fragments;
    int64_t _delivery_deadline = 0;
    int64_t _query_deadline = 0;
    seconds _delivery_expire_seconds = seconds(DEFAULT_EXPIRE_SECONDS);
    seconds _query_expire_seconds = seconds(DEFAULT_EXPIRE_SECONDS);
    bool _is_runtime_filter_coordinator = false;
    std::once_flag _init_mem_tracker_once;
    std::shared_ptr<RuntimeProfile> _profile;
    bool _is_report_profile = false;
    TPipelineProfileLevel::type _profile_level;
    std::shared_ptr<MemTracker> _mem_tracker;
    ObjectPool _object_pool;
    DescriptorTbl* _desc_tbl = nullptr;
    std::once_flag _query_trace_init_flag;
    std::shared_ptr<starrocks::debug::QueryTrace> _query_trace;

    std::once_flag _init_query_once;
    int64_t _query_begin_time = 0;
    std::atomic<int64_t> _total_cpu_cost_ns = 0;
    std::atomic<int64_t> _total_scan_rows_num = 0;
    std::atomic<int64_t> _total_scan_bytes = 0;
    std::atomic<int64_t> _delta_cpu_cost_ns = 0;
    std::atomic<int64_t> _delta_scan_rows_num = 0;
    std::atomic<int64_t> _delta_scan_bytes = 0;
    bool _is_result_sink = false;
    std::shared_ptr<QueryStatisticsRecvr> _sub_plan_query_statistics_recvr; // For receive

    int64_t _scan_limit = 0;
    workgroup::RunningQueryTokenPtr _wg_running_query_token_ptr;
    std::atomic<workgroup::RunningQueryToken*> _wg_running_query_token_atomic_ptr = nullptr;
};

class QueryContextManager {
public:
    QueryContextManager(size_t log2_num_slots);
    ~QueryContextManager();
    Status init();
    QueryContext* get_or_register(const TUniqueId& query_id);
    QueryContextPtr get(const TUniqueId& query_id);
    size_t size();
    bool remove(const TUniqueId& query_id);
    // used for graceful exit
    void clear();

private:
    static void _clean_func(QueryContextManager* manager);
    void _clean_query_contexts();
    void _stop_clean_func() { _stop.store(true); }
    bool _is_stopped() { return _stop; }
    size_t _slot_idx(const TUniqueId& query_id);
    void _clean_slot_unlocked(size_t i);

private:
    const size_t _num_slots;
    const size_t _slot_mask;
    std::vector<std::shared_mutex> _mutexes;
    std::vector<std::unordered_map<TUniqueId, QueryContextPtr>> _context_maps;
    std::vector<std::unordered_map<TUniqueId, QueryContextPtr>> _second_chance_maps;

    std::atomic<bool> _stop{false};
    std::shared_ptr<std::thread> _clean_thread;

    inline static const char* _metric_name = "pip_query_ctx_cnt";
    std::unique_ptr<UIntGauge> _query_ctx_cnt;
};

} // namespace pipeline
} // namespace starrocks
