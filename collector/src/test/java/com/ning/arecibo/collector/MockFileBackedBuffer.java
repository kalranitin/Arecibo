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

package com.ning.arecibo.collector;

import com.ning.arecibo.util.timeline.HostSamplesForTimestamp;
import com.ning.arecibo.util.timeline.persistent.FileBackedBuffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MockFileBackedBuffer extends FileBackedBuffer
{
    private final List<HostSamplesForTimestamp> hostSamplesForTimestamps = new ArrayList<HostSamplesForTimestamp>();

    public MockFileBackedBuffer() throws IOException
    {
        // Kepp it small - 50 bytes. Will be allocated but not used
        super(String.valueOf(System.nanoTime()), "test", 50, 1);
    }

    @Override
    public boolean append(final HostSamplesForTimestamp hostSamplesForTimestamp)
    {
        hostSamplesForTimestamps.add(hostSamplesForTimestamp);
        return true;
    }

    /**
     * Discard in-memory and on-disk data
     */
    @Override
    public void discard()
    {
        hostSamplesForTimestamps.clear();
    }

    @Override
    public long getBytesInMemory()
    {
        return -1;
    }

    @Override
    public long getBytesOnDisk()
    {
        return 0;
    }

    @Override
    public long getFilesCreated()
    {
        return 0;
    }

    @Override
    public long getInMemoryAvailableSpace()
    {
        return -1;
    }

    public List<HostSamplesForTimestamp> getHostSamplesForTimestamps()
    {
        return hostSamplesForTimestamps;
    }
}
