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


package com.starrocks.connector.hive.events;

import com.google.common.collect.Lists;
import com.starrocks.common.Config;
import com.starrocks.common.ThreadPoolManager;
import com.starrocks.common.util.FrontendDaemon;
import com.starrocks.connector.hive.HiveCacheUpdateProcessor;
import com.starrocks.server.CatalogMgr;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.messaging.MessageDeserializer;
import org.apache.hadoop.hive.metastore.messaging.json.JSONMessageDeserializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * A metastore event is a instance of the class
 * {@link NotificationEvent}. Metastore can be
 * configured, to work with Listeners which are called on various DDL operations like
 * create/alter/drop operations on database, table, partition etc. Each event has a unique
 * incremental id and the generated events are be fetched from Metastore to get
 * incremental updates to the metadata stored in Hive metastore using the the public API
 * <code>get_next_notification</code> These events could be generated by external
 * Metastore clients like Apache Hive or Apache Spark configured to talk with the same metastore.
 * <p>
 * This class is used to poll metastore for such events at a given frequency. By observing
 * such events, we can take appropriate action on the {@link com.starrocks.connector.hive.CachingHiveMetastore}
 * (refresh/invalidate/add/remove) so that represents the latest information
 * available in metastore. We keep track of the last synced event id in each polling
 * iteration so the next batch can be requested appropriately. The current batch size is
 * constant and set to {@link Config#hms_events_batch_size_per_rpc}.
 */
public class MetastoreEventsProcessor extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(MetastoreEventsProcessor.class);
    public static final String HMS_ADD_THRIFT_OBJECTS_IN_EVENTS_CONFIG_KEY =
            "hive.metastore.notifications.add.thrift.objects";

    // for deserializing from JSON strings from metastore event
    private static final MessageDeserializer MESSAGE_DESERIALIZER = new JSONMessageDeserializer();

    // thread pool for processing the metastore events
    private final ExecutorService eventsProcessExecutor =
            ThreadPoolManager.newDaemonFixedThreadPool(Config.hms_process_events_parallel_num,
                    Integer.MAX_VALUE, "hms-event-processor-executor", true);

    // event factory which is used to get or create MetastoreEvents
    private final MetastoreEventFactory metastoreEventFactory;

    private final Map<String, HiveCacheUpdateProcessor> cacheUpdateProcessors = new ConcurrentHashMap<>();

    // [catalogName.dbName.tableName] for hive table with resource
    private final List<String> externalTables = Lists.newArrayList();

    public MetastoreEventsProcessor() {
        super(MetastoreEventsProcessor.class.getName(), Config.hms_events_polling_interval_ms);
        this.metastoreEventFactory = new MetastoreEventFactory(externalTables);
    }

    public void registerCacheUpdateProcessor(String catalogName, HiveCacheUpdateProcessor cache) {
        LOG.info("Start to synchronize hive metadata cache on catalog {}", catalogName);
        cacheUpdateProcessors.put(catalogName, cache);
    }

    public void unRegisterCacheUpdateProcessor(String catalogName) {
        LOG.info("Stop to synchronize hive metadata cache on catalog {}", catalogName);
        cacheUpdateProcessors.remove(catalogName);
    }

    public void registerTableFromResource(String catalogTableName) {
        externalTables.add(catalogTableName);
        LOG.info("Succeed to register {} to Metastore event processor", catalogTableName);
    }

    public void unRegisterTableFromResource(String catalogTableName) {
        externalTables.remove(catalogTableName);
        LOG.info("Succeed to remove {} from Metastore event processor", catalogTableName);
    }

    /**
     * Gets metastore notification events from the given eventId. The returned list of
     * NotificationEvents are filtered using the NotificationFilter provided if it is not null.
     *
     * @param catalogName The catalog name of current hive metastore instance.
     * @param getAllEvents If this is true all the events since eventId are returned.
     *                     Note that Hive MetaStore can limit the response to a specific
     *                     maximum number of limit based on the value of configuration
     *                     {@code hive.metastore.max.event.response}.
     *                     If it is false, only {@link Config#hms_events_batch_size_per_rpc} events are
     *                     returned, caller is expected to issue more calls to this method
     *                     to fetch the remaining events.
     * @param filter       This is a nullable argument. If not null, the events are filtered
     *                     and then returned using this. Otherwise, all the events are returned.
     * @return List of NotificationEvents from metastore since eventId.
     * @throws MetastoreNotificationFetchException In case of exceptions from HMS.
     */
    private List<NotificationEvent> getNextHMSEvents(String catalogName,
                                                     final boolean getAllEvents,
                                                     @Nullable final IMetaStoreClient.NotificationFilter filter) {
        LOG.info("Start to pull events on catalog [{}]", catalogName);
        HiveCacheUpdateProcessor updateProcessor = cacheUpdateProcessors.get(catalogName);
        if (updateProcessor == null) {
            LOG.error("Failed to get cacheUpdateProcessor by catalog {}.", catalogName);
            return Collections.emptyList();
        }

        NotificationEventResponse response = updateProcessor.getNextEventResponse(catalogName, getAllEvents);
        if (response == null) {
            return Collections.emptyList();
        }

        if (filter == null) {
            return response.getEvents();
        }

        List<NotificationEvent> filteredEvents = new ArrayList<>();
        for (NotificationEvent event : response.getEvents()) {
            if (filter.accept(event)) {
                filteredEvents.add(event);
            }
        }

        return filteredEvents;
    }

    /**
     * Fetch the next batch of NotificationEvents from metastore. The default batch size is
     * <code>{@link Config#hms_events_batch_size_per_rpc}</code>
     */
    private List<NotificationEvent> getNextHMSEvents(String catalogName)
            throws MetastoreNotificationFetchException {
        return getNextHMSEvents(catalogName, false, null);
    }

    private void doExecuteWithPartialProgress(List<MetastoreEvent> events) {
        List<Future<?>> futures = Lists.newArrayList();
        events.forEach(event -> {
            futures.add(eventsProcessExecutor.submit(event::process));
        });

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new MetastoreNotificationException(e);
            }
        }
    }

    private void doExecute(List<MetastoreEvent> events, HiveCacheUpdateProcessor cacheProcessor) {
        for (MetastoreEvent event : events) {
            try {
                event.process();
            } catch (Exception e) {
                if (event instanceof BatchEvent) {
                    cacheProcessor.setLastSyncedEventId(((BatchEvent<?>) event).getFirstEventId() - 1);
                } else {
                    cacheProcessor.setLastSyncedEventId(event.getEventId() - 1);
                }
                throw e;
            }
        }
    }

    /**
     * Process the given list of notification events. Useful for tests which provide a list of events
     */
    private void processEvents(List<NotificationEvent> events, String catalogName) {
        HiveCacheUpdateProcessor cacheProcessor = cacheUpdateProcessors.get(catalogName);
        List<MetastoreEvent> filteredEvents = metastoreEventFactory.getFilteredEvents(events, cacheProcessor, catalogName);

        if (filteredEvents.isEmpty()) {
            cacheProcessor.setLastSyncedEventId(events.get(events.size() - 1).getEventId());
            return;
        }

        LOG.info("Notification events {} to be processed on catalog [{}]", events, catalogName);

        if (Config.enable_hms_parallel_process_evens) {
            doExecuteWithPartialProgress(filteredEvents);
        } else {
            doExecute(filteredEvents, cacheProcessor);
        }
        cacheProcessor.setLastSyncedEventId(filteredEvents.get(filteredEvents.size() - 1).getEventId());
    }

    @Override
    protected void runAfterCatalogReady() {
        List<String> catalogs = Lists.newArrayList(cacheUpdateProcessors.keySet());
        int resourceCatalogNum = (int) cacheUpdateProcessors.keySet().stream()
                .filter(CatalogMgr.ResourceMappingCatalog::isResourceMappingCatalog).count();
        int catalogNum = cacheUpdateProcessors.size() - resourceCatalogNum;
        LOG.info("Start to pull [{}] events. resource mapping catalog size [{}], normal catalog log size [{}]",
                catalogs, resourceCatalogNum, catalogNum);

        for (String catalogName : catalogs) {
            List<NotificationEvent> events = Collections.emptyList();
            try {
                events = getNextHMSEvents(catalogName);
                if (!events.isEmpty()) {
                    LOG.info("Events size are {} on catalog [{}]", events.size(), catalogName);
                    processEvents(events, catalogName);
                }
            } catch (MetastoreNotificationFetchException e) {
                LOG.error("Failed to fetch hms events on {}. msg: ", catalogName, e);
            } catch (Exception ex) {
                LOG.error("Failed to process hive metastore [{}] events " +
                                "in the range of event id from {} to {}.", catalogName,
                        events.get(0).getEventId(), events.get(events.size() - 1).getEventId(), ex);
            }
        }
    }

    public static MessageDeserializer getMessageDeserializer() {
        return MESSAGE_DESERIALIZER;
    }
}
