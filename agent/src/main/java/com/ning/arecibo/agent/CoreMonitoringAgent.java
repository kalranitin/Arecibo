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

package com.ning.arecibo.agent;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;

import org.eclipse.jetty.server.Server;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.guice.MBeanModule;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.google.inject.name.Named;
import com.ning.arecibo.agent.config.ConfigException;
import com.ning.arecibo.agent.eventapireceiver.EventProcessorImpl;
import com.ning.arecibo.agent.guice.AgentConfig;
import com.ning.arecibo.agent.guice.AgentModule;
import com.ning.arecibo.agent.status.StatusPageHandler;
import com.ning.arecibo.event.publisher.EventPublisherModule;
import com.ning.arecibo.event.publisher.EventSenderType;
import com.ning.arecibo.event.receiver.RESTEventReceiverModule;
import com.ning.arecibo.event.receiver.UDPEventReceiverModule;
import com.ning.arecibo.event.transport.EventService;
import com.ning.arecibo.eventlogger.EventPublisher;
import com.ning.arecibo.util.EmbeddedJettyConfig;
import com.ning.arecibo.util.EmbeddedJettyJerseyModule;
import com.ning.arecibo.util.Logger;
import com.ning.arecibo.util.galaxy.GalaxyModule;
import com.ning.arecibo.util.lifecycle.Lifecycle;
import com.ning.arecibo.util.lifecycle.LifecycleEvent;
import com.ning.arecibo.util.lifecycle.LifecycleModule;
import com.ning.arecibo.util.service.DummyServiceLocatorModule;
import com.ning.arecibo.util.service.ServiceDescriptor;
import com.ning.arecibo.util.service.ServiceLocator;

public class CoreMonitoringAgent
{
	private static final Logger log = Logger.getLogger(CoreMonitoringAgent.class);

	private final AgentConfig agentConfig;
	private final AgentDataCollectorManager dataCollector;
	private final EventPublisher eventPublisher;
	private final Server server;
    private final Lifecycle lifecycle;
    private final ServiceLocator serviceLocator;
    private final EmbeddedJettyConfig jettyConfig;
    private final int udpPort;

	@Inject
	private CoreMonitoringAgent(AgentConfig agentConfig,
	                            EmbeddedJettyConfig jettyConfig,
                                @Named("UDPServerPort") int udpPort,
                                Lifecycle lifecycle,
                                ServiceLocator serviceLocator,
                                Server server,
                                AgentDataCollectorManager dataCollector,
                                EventPublisher eventPublisher) throws IOException, ConfigException
	{
	    this.agentConfig = agentConfig;
        this.lifecycle = lifecycle;
        this.serviceLocator = serviceLocator;
		this.server = server;
		this.eventPublisher = eventPublisher;
		this.dataCollector = dataCollector;
        this.jettyConfig = jettyConfig;
        this.udpPort = udpPort;
	}

	private void run()
	{
		try {

            if (agentConfig.isAdvertiseReceiverOnBeacon()) {
                // advertise event endpoints
                Map<String, String> map = new HashMap<String, String>();
                map.put(EventService.HOST, jettyConfig.getHost());
                map.put(EventService.JETTY_PORT, String.valueOf(jettyConfig.getPort()));
                map.put(EventService.UDP_PORT, String.valueOf(udpPort));
                ServiceDescriptor self = new ServiceDescriptor(agentConfig.getRelayServiceName(), map);

                // advertise on beacon
                serviceLocator.advertiseLocalService(self);
            }

            // start lifecycle
            lifecycle.fire(LifecycleEvent.START);

            this.eventPublisher.start();
			this.dataCollector.start();
			this.server.start();
		}
		catch (IOException e) {
			log.error(e);
			return;
		}
		catch (Exception e) {
			log.error(e);
			return;
		}

		final Thread t = Thread.currentThread();
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			public void run()
			{
				t.interrupt();
			}
		});	

		try {
			Thread.currentThread().join();
		}
		catch (InterruptedException ex) {
			// continue
		}

		// shut down
		try {
		    log.info("Shutting Down Core Monitoring Agent");
		    
			log.info("Stopping eventPublisher");
			this.eventPublisher.stop(1, TimeUnit.MINUTES);
			
			log.info("Stopping dataCollectorManager");
			this.dataCollector.stop();
			
		    log.info("Stopping jetty server");
			this.server.stop();
			
			// this log line doesn't get executed for some reason
			log.info("Shut Down Complete");
		}
		catch (Throwable e) {
			log.warn(e);
		}
	}


	/**
	 * @param args
	 */
	public static void main(String[] args)
	{
		Injector injector = Guice.createInjector(Stage.PRODUCTION, 
		                                         new MBeanModule(),
		                                         new AbstractModule() {
                                                     @Override
                                                     protected void configure() {
                                                         bind(MBeanServer.class).toInstance(ManagementFactory.getPlatformMBeanServer());
                                                         bind(MBeanExporter.class).toInstance(MBeanExporter.withPlatformMBeanServer());
                                                         bind(StatusPageHandler.class).asEagerSingleton();
                                                         // TODO: the embedded jetty below is configured with the default servlet
                                                         //       but it might not serve them from /static, maybe need to tweak it
                                                         //       or add a resource or filter specifically for them
                                                     }
                                                 },
                                                 new LifecycleModule(),
                                                 // TODO: need to bind a real implementation of ServiceLocator
                                                 new DummyServiceLocatorModule(),
                                                 new EventPublisherModule(EventSenderType.CLIENT),
                                                 new EmbeddedJettyJerseyModule(),
                                                 new RESTEventReceiverModule(EventProcessorImpl.class, "arecibo.agent:name=EventAPI"),
                                                 new UDPEventReceiverModule(),
												 new GalaxyModule(),
												 new AgentModule());

		CoreMonitoringAgent coreMonitor = injector.getInstance(CoreMonitoringAgent.class);
		try {
			coreMonitor.run();
		}
		catch (Exception e) {
			log.error(e, "Unable to start. Exiting.");
			System.exit(-1);
		}
	}

}
