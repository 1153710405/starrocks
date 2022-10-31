// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

#include "exec/vectorized/olap_meta_scanner.h"

#include "exec/vectorized/olap_meta_scan_node.h"
#include "storage/storage_engine.h"
#include "storage/tablet_manager.h"

namespace starrocks {
namespace vectorized {

OlapMetaScanner::OlapMetaScanner(OlapMetaScanNode* parent) : _parent(parent), _runtime_state(nullptr) {}

Status OlapMetaScanner::init(RuntimeState* runtime_state, const OlapMetaScannerParams& params) {
    _runtime_state = runtime_state;
    RETURN_IF_ERROR(_get_tablet(params.scan_range));
    RETURN_IF_ERROR(_init_meta_reader_params());
    _reader = std::make_shared<MetaReader>();

    if (_reader == nullptr) {
        return Status::InternalError("Failed to allocate meta reader.");
    }

    RETURN_IF_ERROR(_reader->init(_reader_params));
    return Status::OK();
}

Status OlapMetaScanner::_init_meta_reader_params() {
    _reader_params.tablet = _tablet;
    _reader_params.version = Version(0, _version);
    _reader_params.runtime_state = _runtime_state;
    _reader_params.chunk_size = _runtime_state->chunk_size();
    _reader_params.id_to_names = &_parent->_meta_scan_node.id_to_names;
    _reader_params.desc_tbl = &_parent->_desc_tbl;

    return Status::OK();
}

Status OlapMetaScanner::get_chunk(RuntimeState* state, ChunkPtr* chunk) {
    if (state->is_cancelled()) {
        return Status::Cancelled("canceled state");
    }

    if (!_is_open) {
        return Status::InternalError("OlapMetaScanner Not open.");
    }
    return _reader->do_get_next(chunk);
}

Status OlapMetaScanner::open(RuntimeState* state) {
    DCHECK(!_is_closed);
    if (!_is_open) {
        _is_open = true;
        RETURN_IF_ERROR(_reader->open());
    }
    return Status::OK();
}

void OlapMetaScanner::close(RuntimeState* state) {
    if (_is_closed) {
        return;
    }
    _tablet.reset();
    _reader.reset();
    _is_closed = true;
}

bool OlapMetaScanner::has_more() {
    return _reader->has_more();
}

Status OlapMetaScanner::_get_tablet(const TInternalScanRange* scan_range) {
    TTabletId tablet_id = scan_range->tablet_id;
    SchemaHash schema_hash = strtoul(scan_range->schema_hash.c_str(), nullptr, 10);
    _version = strtoul(scan_range->version.c_str(), nullptr, 10);

    std::string err;
    _tablet = StorageEngine::instance()->tablet_manager()->get_tablet(tablet_id, true, &err);
    if (!_tablet) {
        std::stringstream ss;
        ss << "failed to get tablet. tablet_id=" << tablet_id << ", with schema_hash=" << schema_hash
           << ", reason=" << err;
        LOG(WARNING) << ss.str();
        return Status::InternalError(ss.str());
    }
    return Status::OK();
}

} // namespace vectorized

} // namespace starrocks
