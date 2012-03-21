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

package com.ning.arecibo.util.timeline;

import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.annotate.JsonView;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TimelineTimes extends CachedObject
{
    public static final ResultSetMapper<TimelineTimes> mapper = new ResultSetMapper<TimelineTimes>()
    {
        @Override
        public TimelineTimes map(final int index, final ResultSet rs, final StatementContext ctx) throws SQLException
        {
            final int timelineIntervalId = rs.getInt("timeline_interval_id");
            final int hostId = rs.getInt("host_id");
            final DateTime startTime = dateTimeFromUnixSeconds(rs.getInt("start_time"));
            final DateTime endTime = dateTimeFromUnixSeconds(rs.getInt("end_time"));
            final int count = rs.getInt("count");
            final Blob blobTimes = rs.getBlob("timeline_times");
            final byte[] samples = blobTimes.getBytes(1, (int) blobTimes.length());

            return new TimelineTimes(timelineIntervalId, hostId, startTime, endTime, samples, count);
        }
    };

    private final int hostId;
    private final DateTime startTime;
    private final DateTime endTime;
    @JsonProperty
    @JsonView(TimelineChunksAndTimesViews.Compact.class)
    private final List<Integer> times;

    public TimelineTimes(final long timelineIntervalId, final int hostId, final DateTime startTime, final DateTime endTime, final List<DateTime> dateTimes)
    {
        super(timelineIntervalId);
        this.hostId = hostId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.times = new ArrayList<Integer>(dateTimes.size());
        for (DateTime dateTime : dateTimes) {
            times.add(unixSeconds(dateTime));
        }
    }

    public TimelineTimes(final long timelineIntervalId, final int hostId, final DateTime startTime, final DateTime endTime, final byte[] times, final int count)
    {
        this(timelineIntervalId, hostId, startTime, endTime, new ArrayList<DateTime>());

        final ByteBuffer byteBuffer = ByteBuffer.wrap(times);
        final IntBuffer intBuffer = byteBuffer.asIntBuffer();
        for (int i = 0; i < count; i++) {
            this.times.add(intBuffer.get(i));
        }
    }

    public int getHostId()
    {
        return hostId;
    }

    public DateTime getStartTime()
    {
        return startTime;
    }

    public DateTime getEndTime()
    {
        return endTime;
    }

    public int getSampleCount()
    {
        return times.size();
    }

    public DateTime getSampleTimestamp(final int sampleNumber)
    {
        if (sampleNumber < 0 || sampleNumber >= times.size()) {
            return null;
        }
        else {
            return new DateTime(dateTimeFromUnixSeconds(times.get(sampleNumber)));
        }
    }

    public int getSampleNumberForTimestamp(final DateTime timestamp)
    {
        // TODO: do the binary search
        throw new IllegalArgumentException("NYI");
    }

    public byte[] getTimeArray()
    {
        final int[] unixTimes = new int[times.size()];

        for (int i = 0; i < times.size(); i++) {
            unixTimes[i] = times.get(i);
        }

        final ByteBuffer byteBuffer = ByteBuffer.allocate(unixTimes.length * 4);
        final IntBuffer intBuffer = byteBuffer.asIntBuffer();
        intBuffer.put(unixTimes);

        return byteBuffer.array();
    }

    public static DateTime dateTimeFromUnixSeconds(final int unixTime)
    {
        return new DateTime(((long) unixTime) * 1000L, DateTimeZone.UTC);
    }

    public static int unixSeconds(final DateTime dateTime)
    {
        final long millis = dateTime.toDateTime(DateTimeZone.UTC).getMillis();
        return (int) (millis / 1000L);
    }
}
