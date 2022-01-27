/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.service.logging;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.newrelic.agent.Agent;
import com.newrelic.agent.ExtendedTransactionListener;
import com.newrelic.agent.Harvestable;
import com.newrelic.agent.MetricNames;
import com.newrelic.agent.Transaction;
import com.newrelic.agent.TransactionData;
import com.newrelic.agent.attributes.AttributeSender;
import com.newrelic.agent.attributes.AttributeValidator;
import com.newrelic.agent.config.AgentConfig;
import com.newrelic.agent.config.AgentConfigListener;
import com.newrelic.agent.model.LogEvent;
import com.newrelic.agent.service.AbstractService;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.agent.service.analytics.DistributedSamplingPriorityQueue;
import com.newrelic.agent.stats.StatsEngine;
import com.newrelic.agent.stats.StatsWork;
import com.newrelic.agent.stats.TransactionStats;
import com.newrelic.agent.tracing.DistributedTraceServiceImpl;
import com.newrelic.agent.transport.HttpError;
import com.newrelic.api.agent.Logs;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static com.newrelic.agent.model.LogEvent.LOG_EVENT_TYPE;

public class LogSenderServiceImpl extends AbstractService implements LogSenderService {
    // Whether the service as a whole is enabled. Disabling shuts down all log events for transactions.
    private volatile boolean enabled;
    // Key is the app name, value is if it is enabled - should be a limited number of names
    private final ConcurrentMap<String, Boolean> isEnabledForApp = new ConcurrentHashMap<>();
    /*
     * Number of log events in the reservoir sampling buffer per-app. All apps get the same value.
     */
    private volatile int maxSamplesStored;

    // Key is app name, value is collection of per-transaction log events for next harvest for that app.
    private final ConcurrentHashMap<String, DistributedSamplingPriorityQueue<LogEvent>> reservoirForApp = new ConcurrentHashMap<>();

    private static final LoadingCache<String, String> stringCache = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterAccess(70, TimeUnit.SECONDS).executor(Runnable::run).build(key -> key);

    public static final String METHOD = "add log event attribute";
    public static final String LOG_SENDER_SERVICE = "Log Sender Service";

    /**
     * Lifecycle listener for log events associated with a transaction
     */
    protected final ExtendedTransactionListener transactionListener = new ExtendedTransactionListener() {
        @Override
        public void dispatcherTransactionStarted(Transaction transaction) {
        }

        @Override
        public void dispatcherTransactionFinished(TransactionData transactionData, TransactionStats transactionStats) {
            // FIXME not sure this is a great idea to store log events for the duration of a transaction...
            TransactionLogs data = (TransactionLogs) transactionData.getLogEventData();
            storeEvents(transactionData.getApplicationName(), transactionData.getPriority(), data.events);
        }

        @Override
        public void dispatcherTransactionCancelled(Transaction transaction) {
            // FIXME not sure this is a great idea to store log events for the duration of a transaction...
            // Even if the transaction is canceled we still want to send up any events that were held in it
            TransactionLogs data = (TransactionLogs) transaction.getLogEventData();
            storeEvents(transaction.getApplicationName(), transaction.getPriority(), data.events);
        }
    };

    /**
     * Listener to detect changes to the agent config
     */
    protected final AgentConfigListener configListener = new AgentConfigListener() {
        @Override
        public void configChanged(String appName, AgentConfig agentConfig) {
            // if the config has changed for the app, just remove it and regenerate enabled next transaction
            isEnabledForApp.remove(appName);
            enabled = agentConfig.getLogSenderConfig().isEnabled();
        }
    };

    private final List<Harvestable> harvestables = new ArrayList<>();

    public LogSenderServiceImpl() {
        super(LogSenderServiceImpl.class.getSimpleName());
        AgentConfig config = ServiceFactory.getConfigService().getDefaultAgentConfig();
        maxSamplesStored = config.getLogSenderConfig().getMaxSamplesStored();
        enabled = config.getLogSenderConfig().isEnabled();
        isEnabledForApp.put(config.getApplicationName(), enabled);
    }

    /**
     * Whether the LogSenderService is enabled or not
     * @return true if enabled, else false
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Start the LogSenderService
     * @throws Exception if service fails to start
     */
    @Override
    protected void doStart() throws Exception {
        // TODO it's not clear that log sender events should be tied to transactions in any way
        ServiceFactory.getTransactionService().addTransactionListener(transactionListener);
        ServiceFactory.getConfigService().addIAgentConfigListener(configListener);
    }

    /**
     * Stop the LogSenderService
     * @throws Exception if service fails to stop
     */
    @Override
    protected void doStop() throws Exception {
        removeHarvestables();
        // TODO it's not clear that log sender events should be tied to transactions in any way
        ServiceFactory.getTransactionService().removeTransactionListener(transactionListener);
        ServiceFactory.getConfigService().removeIAgentConfigListener(configListener);
        reservoirForApp.clear();
        isEnabledForApp.clear();
        stringCache.invalidateAll();
    }

    private void removeHarvestables() {
        for (Harvestable harvestable : harvestables) {
            ServiceFactory.getHarvestService().removeHarvestable(harvestable);
        }
    }

    /**
     * Records a LogEvent. If a LogEvent occurs within a Transaction it will be associated with it.
     * @param attributes A map of log event data (e.g. log message, log timestamp, log level)
     *                   Each key should be a String and each value should be a String, Number, or Boolean.
     *                   For map values that are not String, Number, or Boolean object types the toString value will be used.
     */
    @Override
    public void recordLogEvent(Map<String, ?> attributes) {
        if (logEventsDisabled()) {
            return;
        }

        Transaction transaction = ServiceFactory.getTransactionService().getTransaction(false);
        // FIXME perhaps ignore transaction status and just always send log events...
        //  what is the benefit of storing them on the transaction? Sampling?
        // Not in a Transaction or an existing Transaction is not in progress or is ignored
        if (transaction == null || !transaction.isInProgress() || transaction.isIgnore()) {
            String applicationName = ServiceFactory.getRPMService().getApplicationName();

            if (transaction != null && transaction.getApplicationName() != null) {
                applicationName = transaction.getApplicationName();
            }

            AgentConfig agentConfig = ServiceFactory.getConfigService().getAgentConfig(applicationName);

            if (!getIsEnabledForApp(agentConfig, applicationName)) {
                reservoirForApp.remove(applicationName);
                return;
            }
            createAndStoreEvent(applicationName, attributes);
        // In a Transaction that is in progress and not ignored
        } else {
            // FIXME not sure this is a great idea to store log events for the duration of a transaction...
            transaction.getLogEventData().recordLogEvent(attributes);
        }
        MetricNames.recordApiSupportabilityMetric(MetricNames.SUPPORTABILITY_API_RECORD_LOG_EVENT);
    }

    /**
     * Store a collection of LogEvents in the priority queue when a Transaction is finished or cancelled
     *
     * @param appName app name
     * @param priority sampling priority from Transaction
     * @param events collection of LogEvents to store
     */
    private void storeEvents(String appName, float priority, Collection<LogEvent> events) {
        if (events.size() > 0) {
            DistributedSamplingPriorityQueue<LogEvent> eventList = getReservoir(appName);
            for (LogEvent event : events) {
                // Set "priority" on LogEvent based on priority value from Transaction
                event.setPriority(priority);
                eventList.add(event);
            }
        }
    }

    /**
     * Register LogSenderHarvestable
     * @param appName application name
     */
    public void addHarvestableToService(String appName) {
        Harvestable harvestable = new LogSenderHarvestableImpl(this, appName);
        ServiceFactory.getHarvestService().addHarvestable(harvestable);
        harvestables.add(harvestable);
    }

    public int getMaxSamplesStored() {
        return maxSamplesStored;
    }

    public void setMaxSamplesStored(int maxSamplesStored) {
        this.maxSamplesStored = maxSamplesStored;
    }

    public void clearReservoir() {
        reservoirForApp.clear();
    }

    public void clearReservoir(String appName) {
        DistributedSamplingPriorityQueue<LogEvent> reservoir = reservoirForApp.get(appName);
        if (reservoir != null) {
            reservoir.clear();
        }
    }

    @VisibleForTesting
    void configureHarvestables(long reportPeriodInMillis, int maxSamplesStored) {
        for (Harvestable h : harvestables) {
            h.configure(reportPeriodInMillis, maxSamplesStored);
        }
    }

    @VisibleForTesting
    public void harvestHarvestables() {
        for (Harvestable h : harvestables) {
            h.harvest();
        }
    }

    public void harvestPendingEvents() {
        // harvest pending events
        for (String appName : reservoirForApp.keySet()) {
            harvestEvents(appName);
        }
    }

    /**
     * Store a LogEvent instance
     * @param appName application name
     * @param event log event
     */
    @Override
    public void storeEvent(String appName, LogEvent event) {
        if (logEventsDisabled()) {
            return;
        }

        DistributedSamplingPriorityQueue<LogEvent> eventList = getReservoir(appName);
        eventList.add(event);
        Agent.LOG.finest(MessageFormat.format("Added Custom Event of type {0}", event.getType()));
    }

    /**
     * Create and store a LogEvent instance
     * @param appName application name
     * @param attributes Map of attributes to create a LogEvent from
     */
    private void createAndStoreEvent(String appName, Map<String, ?> attributes) {
        if (logEventsDisabled()) {
            return;
        }

        DistributedSamplingPriorityQueue<LogEvent> eventList = getReservoir(appName);
        eventList.add(createValidatedEvent(attributes));
        Agent.LOG.finest(MessageFormat.format("Added event of type {0}", LOG_EVENT_TYPE));
    }

    /**
     * Check if LogEvents are disabled
     *
     * @return true if they are disabled, false if they are enabled
     */
    private boolean logEventsDisabled() {
        if (!enabled) {
            // TODO high security is disabled for now. How should we handle it?
//            if (ServiceFactory.getConfigService().getDefaultAgentConfig().isHighSecurity()) {
//                Agent.LOG.log(Level.FINER, "Event of type {0} not collected due to high security mode being enabled.", eventType);
//            } else {
//                Agent.LOG.log(Level.FINER, "Event of type {0} not collected. log_sending not enabled.", eventType);
//            }

            Agent.LOG.log(Level.FINER, "Event of type {0} not collected. log_sending not enabled.", LOG_EVENT_TYPE);

            return true; // Log Sender events are disabled
        }

        return false; // Log Sender events are enabled
    }

    /**
     * Get the LogEvent reservoir
     *
     * @param appName app name
     * @return Queue of LogEvent instances
     */
    @VisibleForTesting
    public DistributedSamplingPriorityQueue<LogEvent> getReservoir(String appName) {
        DistributedSamplingPriorityQueue<LogEvent> result = reservoirForApp.get(appName);
        while (result == null) {
            // I don't think this loop can actually execute more than once, but it's prudent to assume it can.
            reservoirForApp.putIfAbsent(appName, new DistributedSamplingPriorityQueue<>(appName, LOG_SENDER_SERVICE, maxSamplesStored));
            result = reservoirForApp.get(appName);
        }
        return result;
    }

    /**
     * Harvest and send the LogEvents
     *
     * @param appName the application to harvest for
     */
    public void harvestEvents(final String appName) {
        if (!getIsEnabledForApp(ServiceFactory.getConfigService().getAgentConfig(appName), appName)) {
            reservoirForApp.remove(appName);
            return;
        }
        if (maxSamplesStored <= 0) {
            clearReservoir(appName);
            return;
        }

        long startTimeInNanos = System.nanoTime();

        final DistributedSamplingPriorityQueue<LogEvent> reservoir = this.reservoirForApp.put(appName,
                new DistributedSamplingPriorityQueue<>(appName, LOG_SENDER_SERVICE, maxSamplesStored));

        if (reservoir != null && reservoir.size() > 0) {
            try {
                // Send LogEvents
                ServiceFactory.getRPMServiceManager()
                        .getOrCreateRPMService(appName)
                        .sendLogEvents(maxSamplesStored, reservoir.getNumberOfTries(), Collections.unmodifiableList(reservoir.asList()));

                final long durationInNanos = System.nanoTime() - startTimeInNanos;
                ServiceFactory.getStatsService().doStatsWork(new StatsWork() {
                    @Override
                    public void doWork(StatsEngine statsEngine) {
                        recordSupportabilityMetrics(statsEngine, durationInNanos, reservoir);
                    }

                    @Override
                    public String getAppName() {
                        return appName;
                    }
                });

                if (reservoir.size() < reservoir.getNumberOfTries()) {
                    int dropped = reservoir.getNumberOfTries() - reservoir.size();
                    Agent.LOG.log(Level.FINE, "Dropped {0} log events out of {1}.", dropped, reservoir.getNumberOfTries());
                }
            } catch (HttpError e) {
                if (!e.discardHarvestData()) {
                    Agent.LOG.log(Level.FINE, "Unable to send log events. Unsent events will be included in the next harvest.", e);
                    // Save unsent data by merging it with current data using reservoir algorithm
                    DistributedSamplingPriorityQueue<LogEvent> currentReservoir = reservoirForApp.get(appName);
                    currentReservoir.retryAll(reservoir);
                } else {
                    // discard harvest data
                    reservoir.clear();
                    Agent.LOG.log(Level.FINE, "Unable to send log events. Unsent events will be dropped.", e);
                }
            } catch (Exception e) {
                // discard harvest data
                reservoir.clear();
                Agent.LOG.log(Level.FINE, "Unable to send log events. Unsent events will be dropped.", e);
            }
        }
    }

    @Override
    public String getEventHarvestIntervalMetric() {
        return MetricNames.SUPPORTABILITY_LOG_SENDER_SERVICE_EVENT_HARVEST_INTERVAL;
    }

    @Override
    public String getReportPeriodInSecondsMetric() {
        return MetricNames.SUPPORTABILITY_LOG_SENDER_SERVICE_REPORT_PERIOD_IN_SECONDS;
    }

    @Override
    public String getEventHarvestLimitMetric() {
        return MetricNames.SUPPORTABILITY_LOG_EVENT_DATA_HARVEST_LIMIT;
    }

    private void recordSupportabilityMetrics(StatsEngine statsEngine, long durationInNanoseconds,
                                             DistributedSamplingPriorityQueue<LogEvent> reservoir) {
        statsEngine.getStats(MetricNames.SUPPORTABILITY_LOG_SENDER_SERVICE_CUSTOMER_SENT)
                .incrementCallCount(reservoir.size());
        statsEngine.getStats(MetricNames.SUPPORTABILITY_LOG_SENDER_SERVICE_CUSTOMER_SEEN)
                .incrementCallCount(reservoir.getNumberOfTries());
        statsEngine.getResponseTimeStats(MetricNames.SUPPORTABILITY_LOG_SENDER_SERVICE_EVENT_HARVEST_TRANSMIT)
                .recordResponseTime(durationInNanoseconds, TimeUnit.NANOSECONDS);
    }

    private boolean getIsEnabledForApp(AgentConfig config, String currentAppName) {
        Boolean appEnabled = currentAppName == null ? null : isEnabledForApp.get(currentAppName);
        if (appEnabled == null) {
            appEnabled = config.getLogSenderConfig().isEnabled();
            isEnabledForApp.put(currentAppName, appEnabled);
        }
        return appEnabled;
    }

    /**
     * We put Strings that occur in events in a map so that we're only ever holding a reference to one byte array for
     * any given string. It's basically like interning the string without using a global map.
     *
     * @param value the string to "intern"
     * @return the interned string
     */
    private static String mapInternString(String value) {
        // Note that the interning occurs on the *input* to the validation code. If the validation code truncates or
        // otherwise replaces the "interned" string, the new string will not be "interned" by this cache. See the
        // comment below for more information.
        return stringCache.get(value);
    }

    /**
     * Create a validated LogEvent
     * @param attributes Map of attributes to create a LogEvent from
     * @return LogEvent instance
     */
    private static LogEvent createValidatedEvent(Map<String, ?> attributes) {
        Map<String, Object> userAttributes = new HashMap<>(attributes.size());
        // FIXME LogEvent constructor only needs the timestamp for the AnalyticsEvent super class but it won't
        //  actually be added to the LogEvent as it isn't needed. We use the timestamp captured from the log library.
        LogEvent event = new LogEvent(System.currentTimeMillis(), userAttributes, DistributedTraceServiceImpl.nextTruncatedFloat());

        // Now add the attributes from the argument map to the event using an AttributeSender.
        // An AttributeSender is the way to reuse all the existing attribute validations. We
        // also locally "intern" Strings because we anticipate a lot of reuse of the keys and,
        // possibly, the values. But there's an interaction: if the key or value is chopped
        // within the attribute sender, the modified value won't be "interned" in our map.
        AttributeSender sender = new LogEventAttributeSender(userAttributes);

        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // key or value is null, skip it with a log message and iterate to next entry in attributes.entrySet()
            if (key == null || value == null) {
                Agent.LOG.log(Level.WARNING, "Log event with invalid attributes key or value of null was reported for a transaction but ignored."
                        + " Each key should be a String and each value should be a String, Number, or Boolean.");
                continue;
            }

            mapInternString(key);

            if (value instanceof String) {
                sender.addAttribute(key, mapInternString((String) value), METHOD);
            } else if (value instanceof Number) {
                sender.addAttribute(key, (Number) value, METHOD);
            } else if (value instanceof Boolean) {
                sender.addAttribute(key, (Boolean) value, METHOD);
            } else {
                // Java Agent specific - toString the value. This allows for e.g. enums as arguments.
                sender.addAttribute(key, mapInternString(value.toString()), METHOD);
            }
        }

        return event;
    }

    /**
     * Validate attributes and add them to LogEvents
     */
    private static class LogEventAttributeSender extends AttributeSender {

        private static final String ATTRIBUTE_TYPE = "log";

        private final Map<String, Object> userAttributes;

        public LogEventAttributeSender(Map<String, Object> userAttributes) {
            super(new AttributeValidator(ATTRIBUTE_TYPE));
            this.userAttributes = userAttributes;
            setTransactional(false);
        }

        @Override
        protected String getAttributeType() {
            return ATTRIBUTE_TYPE;
        }

        @Override
        protected Map<String, Object> getAttributeMap() {
            // FIXME skip this check for now as it isn't clear what to do with Log data if LASP or high security are enabled
//            if (ServiceFactory.getConfigService().getDefaultAgentConfig().isCustomParametersAllowed()) {
//                return userAttributes;
//            }
//            return null;
            return userAttributes;
        }
    }

    @Override
    public Logs getTransactionLogs(AgentConfig config) {
        return new TransactionLogs(config);
    }

    /**
     * Used to record LogEvents on Transactions
     */
    public static final class TransactionLogs implements Logs {
        final LinkedBlockingQueue<LogEvent> events;

        TransactionLogs(AgentConfig config) {
            int maxSamplesStored = config.getLogSenderConfig().getMaxSamplesStored();
            events = new LinkedBlockingQueue<>(maxSamplesStored);
        }

        @Override
        public void recordLogEvent(Map<String, ?> attributes) {
            // TODO ignore high security for now
//            if (ServiceFactory.getConfigService().getDefaultAgentConfig().isHighSecurity()) {
//                Agent.LOG.log(Level.FINER, "Event of type {0} not collected due to high security mode being enabled.", LOG_EVENT_TYPE);
//                return;
//            }

            LogEvent event = createValidatedEvent(attributes);
            if (events.offer(event)) {
                Agent.LOG.finest(MessageFormat.format("Added event of type {0} in Transaction.", LOG_EVENT_TYPE));
            } else {
                // Too many events are cached on the transaction, send directly to the reservoir.
                String applicationName = ServiceFactory.getRPMService().getApplicationName();
                ServiceFactory.getServiceManager().getLogSenderService().storeEvent(applicationName, event);
            }
        }

        @VisibleForTesting
        public List<LogEvent> getEventsForTesting() {
            return new ArrayList<>(events);
        }
    }
}
