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

package com.ning.arecibo.event.publisher;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.google.inject.Inject;
import com.ning.arecibo.eventlogger.AbstractEventPublisher;
import com.ning.arecibo.eventlogger.Event;
import com.ning.arecibo.util.Logger;
import com.ning.arecibo.util.jmx.MonitorableManaged;
import com.ning.arecibo.util.jmx.MonitoringType;
import com.ning.arecibo.util.service.RandomSelector;
import com.ning.arecibo.util.service.Selector;
import com.ning.arecibo.util.service.ServiceLocator;

public class HdfsEventPublisher extends AbstractEventPublisher
{
	private static final Logger log = Logger.getLogger(HdfsEventPublisher.class);
	private final EventServiceChooser chooser;
	private final Spool spool;
	private final AtomicLong eventsDiscarded = new AtomicLong(0);
	private final AtomicLong restEventsDelivered = new AtomicLong(0);

    @Inject
	public HdfsEventPublisher(EventPublisherConfig config,
	                          EventServiceChooser chooser,
							  ServiceLocator cluster,
							  @RandomSelector Selector selector,
							  @PublisherExecutor ExecutorService globalExecutor
	)
	{
		this.chooser = chooser;
		File spoolRoot = config.getLocalSpoolRoot().getAbsoluteFile();
		File publisherRoot = new File(spoolRoot, "publisher");
		this.spool = new Spool("hdfs-collector",
		                       publisherRoot,
		                       cluster,
		                       selector,
		                       globalExecutor,
		                       config.getSpooledEventExpiration()) {
			protected void sendViaREST(Event evt) throws IOException
			{
				sendREST(evt);
			}
		};
	}

	@Override
	public void publish(final Event event, PublishMode publishMode) throws IOException
	{
		switch (publishMode) {
			case ASYNCHRONOUS:
			case SYNCHRONOUS_CLUSTER:
			case SYNCHRONOUS_LOCAL:
				if (spool.hasCluster()) {
					try {
						sendREST(event);
					}
					catch (IOException e) {
						log.error(e, "attempt to send sync event failed");
						spool.retry(event);
					}
				}
				else {
					log.debug("no hdfs collector available, spooling to disk ...");
					spool.spool(event);
				}
				break;
		}
	}

	private void sendREST(Event event) throws IOException
	{
		chooser.choose(event.getSourceUUID()).sendREST(event);
		restEventsDelivered.incrementAndGet();
	}

	public void start() throws IOException
	{
		this.chooser.start();
		this.spool.start();
	}

	public void stop(long l, TimeUnit timeUnit) throws IOException, InterruptedException
	{
		this.chooser.stop();
		this.spool.stop(l, timeUnit);
	}

    @MonitorableManaged(monitored = true)
    public int getSpoolQueueSize()
    {
        return spool.getSpoolQueueSize();
    }

    @MonitorableManaged(monitored = true)
    public int getRetryQueueSize()
    {
        return spool.getRetryQueueSize();
    }

    @MonitorableManaged(monitored = true, monitoringType = { MonitoringType.COUNTER, MonitoringType.RATE })
    public long getEventsDiscarded()
    {
        return eventsDiscarded.get();
    }

    @MonitorableManaged(monitored = true, monitoringType = { MonitoringType.COUNTER, MonitoringType.RATE })
	public long getRestEventsDelivered()
	{
		return restEventsDelivered.get();
	}
}
