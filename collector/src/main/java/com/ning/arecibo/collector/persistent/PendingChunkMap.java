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

import java.util.Map;

import com.ning.arecibo.util.timeline.TimelineChunk;

public class PendingChunkMap
{
    private final TimelineHostEventAccumulator accumulator;
    private final long pendingChunkMapId;
    private final Map<Integer, TimelineChunk> chunkMap;

    public PendingChunkMap(TimelineHostEventAccumulator accumulator, long pendingChunkMapId, Map<Integer, TimelineChunk> chunkMap)
    {
        this.accumulator = accumulator;
        this.pendingChunkMapId = pendingChunkMapId;
        this.chunkMap = chunkMap;
    }

    public TimelineHostEventAccumulator getAccumulator()
    {
        return accumulator;
    }

    public long getPendingChunkMapId()
    {
        return pendingChunkMapId;
    }

    public Map<Integer, TimelineChunk> getChunkMap()
    {
        return chunkMap;
    }

    public int getChunkCount()
    {
        return chunkMap.size();
    }
}
