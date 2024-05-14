// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "block_cache/cachelib_wrapper.h"

#include "common/logging.h"
#include "common/statusor.h"
#include "gutil/strings/fastmem.h"
#include "util/filesystem_util.h"

namespace starrocks {

Status CacheLibWrapper::init(const CacheOptions& options) {
    Cache::Config config;
    config.setCacheSize(options.mem_space_size).setCacheName("default cache").setAccessConfig({25, 10}).validate();

    std::vector<std::string> nvm_files;
    if (!options.disk_spaces.empty()) {
        Cache::NvmCacheConfig nvmConfig;
        nvmConfig.navyConfig.setBlockSize(4096);

        for (auto& dir : options.disk_spaces) {
            nvm_files.emplace_back(dir.path + "/cachelib_data");
            // If exist, truncate it to empty file
            FileSystemUtil::resize_file(nvm_files.back(), 0);
        }
        if (nvm_files.size() == 1) {
            nvmConfig.navyConfig.setSimpleFile(nvm_files[0], options.disk_spaces[0].size, false);
        } else {
            nvmConfig.navyConfig.setRaidFiles(nvm_files, options.disk_spaces[0].size, false);
        }
        nvmConfig.navyConfig.blockCache().setRegionSize(16 * 1024 * 1024);
        nvmConfig.navyConfig.blockCache().setDataChecksum(options.enable_checksum);
        if (options.max_flying_memory_mb > 0) {
            nvmConfig.navyConfig.setMaxParcelMemoryMB(options.max_flying_memory_mb);
        }
        nvmConfig.navyConfig.setMaxConcurrentInserts(options.max_concurrent_inserts);
        config.enableNvmCache(nvmConfig);
    }

    Cache::MMConfig mm_config;
    mm_config.lruInsertionPointSpec = 1;
    _cache = std::make_unique<Cache>(config);
    _default_pool = _cache->addPool("default pool", _cache->getCacheMemoryStats().cacheSize, {}, mm_config);
    _meta_path = options.meta_path;
    return Status::OK();
}

Status CacheLibWrapper::write_buffer(const std::string& key, const IOBuffer& buffer, WriteCacheOptions* options) {
    //  Simulate the behavior of skipping if exists
    if (options && !options->overwrite && _cache->find(key)) {
        return Status::AlreadyExist("the cache item already exists");
    }
    // TODO: check size for chain item
    auto handle = _cache->allocate(_default_pool, key, buffer.size());
    if (!handle) {
        return Status::InternalError("allocate cachelib item failed");
    }
    buffer.copy_to(handle->getMemory());
    _cache->insertOrReplace(handle);
    return Status::OK();
}

Status CacheLibWrapper::read_buffer(const std::string& key, size_t off, size_t size, IOBuffer* buffer,
                                    ReadCacheOptions* options) {
    // TODO:
    // 1. check chain item
    // 2. replace with async methods
    auto handle = _cache->find(key);
    if (!handle) {
        return Status::NotFound("not found cachelib item");
    }
    DCHECK((off + size) <= handle->getSize());
    // std::memcpy(value, (char*)handle->getMemory() + off, size);
    void* data = malloc(size);
    strings::memcpy_inlined(data, (char*)handle->getMemory() + off, size);
    buffer->append_user_data(data, size, nullptr);
    return Status::OK();
}

Status CacheLibWrapper::remove(const std::string& key) {
    _cache->remove(key);
    return Status::OK();
}

const DataCacheMetrics CacheLibWrapper::cache_metrics(int level) {
    // not implemented
    DataCacheMetrics metrics{};
    return metrics;
}

Status CacheLibWrapper::write_object(const std::string& key, const void* ptr, size_t size,
                                     std::function<void()> deleter, DataCacheHandle* handle,
                                     WriteCacheOptions* options) {
    return Status::NotSupported("not supported write object in cachelib");
}

Status CacheLibWrapper::read_object(const std::string& key, DataCacheHandle* handle, ReadCacheOptions* options) {
    return Status::NotSupported("not supported read object in cachelib");
}

Status CacheLibWrapper::update_mem_quota(size_t quota_bytes) {
    return Status::NotSupported("not support updating memory cache quota for cachelib");
}

Status update_disk_spaces(const std::vector<DirSpace>& spaces) {
    return Status::NotSupported("not support updating disk cache spaces for cachelib");
}

void CacheLibWrapper::record_read_remote(size_t size, int64_t lateny_us) {}

void CacheLibWrapper::record_read_cache(size_t size, int64_t lateny_us) {}

Status CacheLibWrapper::shutdown() {
    if (_cache) {
        _dump_cache_stats();
    }
    return Status::OK();
}

std::unordered_map<std::string, double> CacheLibWrapper::cache_stats() {
    const auto navy_stats = _cache->getNvmCacheStatsMap().toMap();
    return navy_stats;
}

void CacheLibWrapper::_dump_cache_stats() {
    std::ofstream of;
    of.open(_meta_path + "/cachelib_stat", std::ios::out | std::ios::trunc);
    const auto stats = cache_stats();
    for (auto& stat : stats) {
        of << stat.first << " : " << stat.second << "\n";
    }
    of.close();
}

} // namespace starrocks
