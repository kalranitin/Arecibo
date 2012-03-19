/*
 * Copyright 2010-2012 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.arecibo.collector.persistent;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Shorts;
import com.google.inject.Inject;
import com.mogwee.executors.Executors;
import com.ning.arecibo.collector.guice.CollectorConfig;
import com.ning.arecibo.collector.process.EventHandler;
import com.ning.arecibo.event.MapEvent;
import com.ning.arecibo.event.MonitoringEvent;
import com.ning.arecibo.eventlogger.Event;
import com.ning.arecibo.util.jmx.MonitorableManaged;
import com.ning.arecibo.util.jmx.MonitoringType;
import com.ning.arecibo.util.timeline.HostSamplesForTimestamp;
import com.ning.arecibo.util.timeline.SampleOpcode;
import com.ning.arecibo.util.timeline.ScalarSample;
import com.ning.arecibo.util.timeline.TimelineChunk;
import com.ning.arecibo.util.timeline.TimelineChunkAccumulator;
import com.ning.arecibo.util.timeline.TimelineChunkAndTimes;
import com.ning.arecibo.util.timeline.TimelineDAO;
import com.ning.arecibo.util.timeline.TimelineHostEventAccumulator;
import com.ning.arecibo.util.timeline.TimelineTimes;
import com.ning.arecibo.util.timeline.persistent.FileBackedBuffer;
import com.ning.arecibo.util.timeline.persistent.Replayer;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weakref.jmx.Managed;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TimelineEventHandler implements EventHandler
{
    private static final Logger log = LoggerFactory.getLogger(TimelineEventHandler.class);

    private final AtomicLong eventsDiscarded = new AtomicLong(0L);
    private final LoadingCache<Integer, TimelineHostEventAccumulator> accumulators;

    private final TimelineDAO timelineDAO;
    private final FileBackedBuffer backingBuffer;

    @Inject
    public TimelineEventHandler(final CollectorConfig config, final TimelineDAO timelineDAO, final FileBackedBuffer fileBackedBuffer) throws IOException
    {
        this.timelineDAO = timelineDAO;
        this.backingBuffer = fileBackedBuffer;

        accumulators = CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .maximumSize(config.getMaxHosts())
            .removalListener(new RemovalListener<Integer, TimelineHostEventAccumulator>()
            {
                @Override
                public void onRemoval(final RemovalNotification<Integer, TimelineHostEventAccumulator> removedObjectNotification)
                {
                    final TimelineHostEventAccumulator accumulator = removedObjectNotification.getValue();
                    if (accumulator == null) {
                        // TODO How often will that happen?
                        log.error("Accumulator already GCed - data lost!");
                    }
                    else {
                        final Integer hostId = removedObjectNotification.getKey();
                        if (hostId == null) {
                            log.info("Saving Timeline");
                        }
                        else {
                            log.info("Saving Timeline for hostId: " + hostId);
                        }
                        accumulator.extractAndSaveTimelineChunks();
                    }
                }
            })
            .build(new CacheLoader<Integer, TimelineHostEventAccumulator>()
            {
                @Override
                public TimelineHostEventAccumulator load(final Integer hostId) throws Exception
                {
                    log.info("Creating new Timeline for hostId: " + hostId);
                    return new TimelineHostEventAccumulator(timelineDAO, hostId);
                }
            });

        Executors.newSingleThreadScheduledExecutor("TimelinesCommiter").scheduleWithFixedDelay(new Runnable()
        {
            @Override
            public void run()
            {
                // Ideally we would use the CachBuilder and do:
                //  .expireAfterWrite(config.getTimelineLength().getMillis(), TimeUnit.MILLISECONDS)
                // Unfortunately, this is won't work as eviction only occurs at access time.
                forceCommit();
            }
        }, config.getTimelineLength().getMillis(), config.getTimelineLength().getMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void handle(final Event event)
    {
        try {
            final Map<Integer, ScalarSample> scalarSamples = new LinkedHashMap<Integer, ScalarSample>();

            // Lookup the host id
            final int hostId = getHostIdFromEvent(event);

            // Extract samples
            if (event instanceof MapEvent) {
                final Map<String, Object> samplesMap = ((MapEvent) event).getMap();
                convertSamplesToScalarSamples(hostId, samplesMap, scalarSamples);
            }
            else if (event instanceof MonitoringEvent) {
                final Map<String, Object> samplesMap = ((MonitoringEvent) event).getMap();
                convertSamplesToScalarSamples(hostId, samplesMap, scalarSamples);
            }
            else {
                log.warn("I don't understand event: " + event);
                eventsDiscarded.getAndIncrement();
            }

            final HostSamplesForTimestamp hostSamples = new HostSamplesForTimestamp(hostId, event.getEventType(), new DateTime(event.getTimestamp(), DateTimeZone.UTC), scalarSamples);
            // Start by saving locally the samples
            backingBuffer.append(hostSamples);
            // Then add them to the in-memory accumulator
            processSamples(hostSamples);
        }
        catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void processSamples(final HostSamplesForTimestamp hostSamples) throws ExecutionException
    {
        final TimelineHostEventAccumulator accumulator = accumulators.get(hostSamples.getHostId());
        accumulator.addHostSamples(hostSamples);
    }

    public Collection<? extends TimelineChunkAndTimes> getInMemoryTimelineChunkAndTimes(final String filterHostName, @Nullable final DateTime filterStartTime, @Nullable final DateTime filterEndTime) throws IOException, ExecutionException
    {
        final ImmutableList<String> sampleKinds = ImmutableList.copyOf(timelineDAO.getSampleKindsByHostName(filterHostName));
        return getInMemoryTimelineChunkAndTimes(filterHostName, sampleKinds, filterStartTime, filterEndTime);
    }

    public Collection<? extends TimelineChunkAndTimes> getInMemoryTimelineChunkAndTimes(final String filterHostName, final String filterSampleKind, @Nullable final DateTime filterStartTime, @Nullable final DateTime filterEndTime) throws IOException, ExecutionException
    {
        return getInMemoryTimelineChunkAndTimes(filterHostName, ImmutableList.of(filterSampleKind), filterStartTime, filterEndTime);
    }

    public Collection<? extends TimelineChunkAndTimes> getInMemoryTimelineChunkAndTimes(final String filterHostName, final List<String> filterSampleKinds, @Nullable final DateTime filterStartTime, @Nullable final DateTime filterEndTime) throws IOException, ExecutionException
    {
        // Check first if there is an in-memory accumulator for this host
        final Integer hostId = timelineDAO.getHostId(filterHostName);
        if (hostId == null) {
            return ImmutableList.of();
        }
        final TimelineHostEventAccumulator hostEventAccumulator = accumulators.getIfPresent(hostId);
        if (hostEventAccumulator == null) {
            return ImmutableList.of();
        }

        // Yup, there is. Check now if the filters apply
        final List<DateTime> accumulatorTimes = hostEventAccumulator.getTimes();
        final DateTime accumulatorStartTime = hostEventAccumulator.getStartTime();
        final DateTime accumulatorEndTime = hostEventAccumulator.getEndTime();

        if ((filterStartTime != null && accumulatorEndTime.isBefore(filterStartTime)) || (filterStartTime != null && accumulatorStartTime.isAfter(filterEndTime))) {
            // Ignore this accumulator
            return ImmutableList.of();
        }

        // We have a timeline match, return the samples matching the sample kinds
        final List<TimelineChunkAndTimes> samplesByHostName = new ArrayList<TimelineChunkAndTimes>();
        for (final TimelineChunkAccumulator chunkAccumulator : hostEventAccumulator.getTimelines().values()) {
            // Extract the timeline for this chunk by copying it and reading encoded bytes
            final TimelineChunkAccumulator accumulator = chunkAccumulator.deepCopy();
            final TimelineChunk timelineChunk = accumulator.extractTimelineChunkAndReset(-1);
            // NOTE! Further filtering needs to be done in the processing function
            final TimelineTimes timelineTimes = new TimelineTimes(-1, hostId, accumulatorStartTime, accumulatorEndTime, accumulatorTimes);

            final String sampleKind = timelineDAO.getSampleKind(timelineChunk.getSampleKindId());
            if (!filterSampleKinds.contains(sampleKind)) {
                // We don't care about this sample kind
                continue;
            }

            samplesByHostName.add(new TimelineChunkAndTimes(filterHostName, sampleKind, timelineChunk, timelineTimes));
        }

        return samplesByHostName;
    }

    private int getHostIdFromEvent(final Event event)
    {
        String hostUUID = event.getSourceUUID().toString();
        if (event instanceof MonitoringEvent) {
            hostUUID = ((MonitoringEvent) event).getHostName();
        }
        else if (event instanceof MapEvent) {
            final Object hostName = ((MapEvent) event).getMap().get("hostName");
            if (hostName != null) {
                hostUUID = hostName.toString();
            }
        }
        return timelineDAO.getOrAddHost(hostUUID);
    }

    @VisibleForTesting
    void convertSamplesToScalarSamples(final int hostId, final Map<String, Object> inputSamples, final Map<Integer, ScalarSample> outputSamples)
    {
        if (inputSamples == null) {
            return;
        }

        for (final String sampleKind : inputSamples.keySet()) {
            final int sampleKindId = timelineDAO.getOrAddSampleKind(hostId, sampleKind);
            final Object sample = inputSamples.get(sampleKind);

            if (sample == null) {
                outputSamples.put(sampleKindId, new ScalarSample<Void>(SampleOpcode.NULL, null));
            }
            else if (sample instanceof Byte) {
                outputSamples.put(sampleKindId, new ScalarSample<Byte>(SampleOpcode.BYTE, (Byte) sample));
            }
            else if (sample instanceof Short) {
                outputSamples.put(sampleKindId, new ScalarSample<Short>(SampleOpcode.SHORT, (Short) sample));
            }
            else if (sample instanceof Integer) {
                try {
                    // Can it fit in a short?
                    final short optimizedShort = Shorts.checkedCast(Long.valueOf(sample.toString()));
                    outputSamples.put(sampleKindId, new ScalarSample<Short>(SampleOpcode.SHORT, optimizedShort));
                }
                catch (IllegalArgumentException e) {
                    outputSamples.put(sampleKindId, new ScalarSample<Integer>(SampleOpcode.INT, (Integer) sample));
                }
            }
            else if (sample instanceof Long) {
                try {
                    // Can it fit in a short?
                    final short optimizedShort = Shorts.checkedCast(Long.valueOf(sample.toString()));
                    outputSamples.put(sampleKindId, new ScalarSample<Short>(SampleOpcode.SHORT, optimizedShort));
                }
                catch (IllegalArgumentException e) {
                    try {
                        // Can it fit in an int?
                        final int optimizedLong = Ints.checkedCast(Long.valueOf(sample.toString()));
                        outputSamples.put(sampleKindId, new ScalarSample<Integer>(SampleOpcode.INT, optimizedLong));
                    }
                    catch (IllegalArgumentException ohWell) {
                        outputSamples.put(sampleKindId, new ScalarSample<Long>(SampleOpcode.LONG, (Long) sample));
                    }
                }
            }
            else if (sample instanceof Float) {
                outputSamples.put(sampleKindId, new ScalarSample<Float>(SampleOpcode.FLOAT, (Float) sample));
            }
            else if (sample instanceof Double) {
                outputSamples.put(sampleKindId, new ScalarSample<Double>(SampleOpcode.DOUBLE, (Double) sample));
            }
            else {
                outputSamples.put(sampleKindId, new ScalarSample<String>(SampleOpcode.STRING, sample.toString()));
            }
        }
    }

    public void replay(final String spoolDir)
    {
        log.info("Starting replay of files in {}", spoolDir);
        final Replayer replayer = new Replayer(spoolDir);

        try {
            // Read all files in the spool directory and delete them after process
            replayer.readAll(new Function<HostSamplesForTimestamp, Void>()
            {
                @Override
                public Void apply(@Nullable final HostSamplesForTimestamp input)
                {
                    if (input != null) {
                        try {
                            processSamples(input);
                        }
                        catch (ExecutionException e) {
                            log.warn("Got exception replaying sample, data potentially lost! {}", input.toString());
                        }
                    }

                    return null;
                }
            });

            log.info("Replay completed");
        }
        catch (RuntimeException e) {
            // Catch the exception to make the collector start properly
            log.error("Ignoring error when replaying the data", e);
        }
    }

    @MonitorableManaged(monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getEventsDiscarded()
    {
        return eventsDiscarded.get();
    }

    @MonitorableManaged(monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getInMemoryTimelines()
    {
        return accumulators.size();
    }

    @Managed
    public void forceCommit()
    {
        accumulators.invalidateAll();

        // All the samples have been saved, discard the local buffer
        // There is a window of doom here but it is fine if we end up storing dups at replay time
        // TODO: make replayer discard dups
        backingBuffer.discard();
    }

    @MonitorableManaged(description = "Returns the number of times a host accumulator lookup methods have returned a cached value", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsHitCount()
    {
        return accumulators.stats().hitCount();
    }

    @MonitorableManaged(description = "Returns the ratio of host accumulator requests which were hits", monitored = true, monitoringType = {MonitoringType.VALUE})
    public double getAccumulatorsHitRate()
    {
        return accumulators.stats().hitRate();
    }

    @MonitorableManaged(description = "Returns the number of times a new host accumulator was created", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsMissCount()
    {
        return accumulators.stats().missCount();
    }

    @MonitorableManaged(description = "Returns the ratio of requests resulting in creating a new host accumulator", monitored = true, monitoringType = {MonitoringType.VALUE})
    public double getAccumulatorsMissRate()
    {
        return accumulators.stats().missRate();
    }

    @MonitorableManaged(description = "Returns the number of times a new host accumulator was successfully created", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsLoadSuccessCount()
    {
        return accumulators.stats().loadSuccessCount();
    }

    @MonitorableManaged(description = "Returns the number of times an exception was thrown while creating a new host accumulator", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsLoadExceptionCount()
    {
        return accumulators.stats().loadExceptionCount();
    }

    @MonitorableManaged(description = "Returns the ratio of host accumulator creation attempts which threw exceptions", monitored = true, monitoringType = {MonitoringType.VALUE})
    public double getAccumulatorsLoadExceptionRate()
    {
        return accumulators.stats().loadExceptionRate();
    }

    @MonitorableManaged(description = "Returns the total number of nanoseconds spent creating new host accumulators", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsTotalLoadTime()
    {
        return accumulators.stats().totalLoadTime();
    }

    @MonitorableManaged(description = "Returns the average time spent creating new host accumulators", monitored = true, monitoringType = {MonitoringType.VALUE})
    public double getAccumulatorsAverageLoadPenalty()
    {
        return accumulators.stats().averageLoadPenalty();
    }

    @MonitorableManaged(description = "Returns the number of times a host accumulator was stored in the database", monitored = true, monitoringType = {MonitoringType.COUNTER, MonitoringType.RATE})
    public long getAccumulatorsEvictionCount()
    {
        return accumulators.stats().evictionCount();
    }

    @VisibleForTesting
    public Collection<TimelineHostEventAccumulator> getAccumulators()
    {
        return accumulators.asMap().values();
    }

    @VisibleForTesting
    public FileBackedBuffer getBackingBuffer()
    {
        return backingBuffer;
    }
}
