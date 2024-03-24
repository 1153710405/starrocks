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

#pragma once

#include "storage/lake/key_index.h"
#include "storage/persistent_index.h"
#include "util/phmap/btree.h"

namespace starrocks::lake {

using IndexValueInfo = std::pair<int64_t, IndexValue>;

class PersistentIndexMemtable {
public:
    // |version|: version of index values
    Status upsert(size_t n, const Slice* keys, const IndexValue* values, IndexValue* old_values,
                  KeyIndexesInfo* not_found, size_t* num_found, int64_t version);

    // |version|: version of index values
    Status insert(size_t n, const Slice* keys, const IndexValue* values, int64_t version);

    // |version|: version of index values
    Status erase(size_t n, const Slice* keys, IndexValue* old_values, KeyIndexesInfo* not_found, size_t* num_found,
                 int64_t version);

    // |version|: version of index values
    Status replace(const Slice* keys, const IndexValue* values, const std::vector<size_t>& replace_idxes,
                   int64_t version);

    // |version|: version of index values
    Status get(size_t n, const Slice* keys, IndexValue* values, KeyIndexesInfo* not_found, size_t* num_found,
               int64_t version);

    void clear();

private:
    static void update_index_value(std::list<IndexValueInfo>* index_value_info, int64_t version,
                                   const IndexValue& value);

private:
    phmap::btree_map<std::string, std::list<IndexValueInfo>, std::less<>> _map;
};

} // namespace starrocks::lake
