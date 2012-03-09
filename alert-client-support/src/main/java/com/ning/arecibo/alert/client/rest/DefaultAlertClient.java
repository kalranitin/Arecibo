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

package com.ning.arecibo.alert.client.rest;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.ning.arecibo.alert.client.AlertClient;
import com.ning.arecibo.alert.client.discovery.AlertFinder;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import org.codehaus.jackson.jaxrs.JacksonJsonProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.spice.jersey.client.ahc.config.DefaultAhcConfig;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DefaultAlertClient implements AlertClient
{
    private static final Logger log = LoggerFactory.getLogger(DefaultAlertClient.class);
    private static final String USER_AGENT = "NING-AlertClient/1.0";
    private static final String RESOURCE_PATH = "xn/rest/1.0";
    private static final Splitter PATH_SPLITTER = Splitter.on("/");
    private static final String PERSON_PATH = "Person";

    private final AlertFinder alertFinder;

    private Client client;

    @Inject
    public DefaultAlertClient(final AlertFinder alertFinder)
    {
        this.alertFinder = alertFinder;
        createClient();
    }

    @Override
    public int createPerson(final String firstName, final String lastName, final String nickName) throws UniformInterfaceException
    {
        final Map<String, String> person = ImmutableMap.of(
            "firstName", firstName,
            "lastName", lastName,
            "label", nickName,
            "isGroupAlias", "false");

        final URI location = doPost(PERSON_PATH, person);
        return extractIdFromURI(location);
    }

    @Override
    public int createGroup(final String name)
    {
        final Map<String, ?> group = ImmutableMap.of(
            "label", name,
            "isGroupAlias", Boolean.TRUE);

        final URI location = doPost(PERSON_PATH, group);
        return extractIdFromURI(location);
    }

    @Override
    public Map<String, Object> findPersonOrGroupById(final int id) throws UniformInterfaceException
    {
        return doGet(PERSON_PATH + "/" + id);
    }

    @Override
    public void deletePersonOrGroupById(final int id) throws UniformInterfaceException
    {
        doDelete(PERSON_PATH + "/" + id);
    }

    private Map<String, Object> doGet(final String path)
    {
        final WebResource resource = createWebResource().path(path);

        log.info("Calling: GET {}", resource.toString());
        final ClientResponse response = resource.type(MediaType.APPLICATION_JSON).get(ClientResponse.class);

        if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return null;
        }
        else if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            final String message = extractErrorMessageFromResponse(response);
            throw new UniformInterfaceException(message, response);
        }
        else {
            return response.getEntity(new GenericType<Map<String, Object>>()
            {
            });
        }
    }

    private URI doPost(final String path, final Map<String, ?> payload)
    {
        final WebResource resource = createWebResource().path(path);

        log.info("Calling: POST {}", resource.toString());
        final ClientResponse response = resource.type(MediaType.APPLICATION_JSON).post(ClientResponse.class, payload);

        if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
            final String message = extractErrorMessageFromResponse(response);
            throw new UniformInterfaceException(message, response);
        }
        else {
            return response.getLocation();
        }
    }

    private void doDelete(final String path)
    {
        final WebResource resource = createWebResource().path(path);

        log.info("Calling: DELETE {}", resource.toString());
        final ClientResponse response = resource.type(MediaType.APPLICATION_JSON).delete(ClientResponse.class);

        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            final String message = extractErrorMessageFromResponse(response);
            throw new UniformInterfaceException(message, response);
        }
    }

    private int extractIdFromURI(final URI location)
    {
        final Iterable<String> explodedPath = PATH_SPLITTER.split(location.getPath());
        final Iterator<String> iterator = explodedPath.iterator();
        if (!iterator.hasNext()) {
            return -1;
        }
        else {
            String lastSplit = null;
            while (iterator.hasNext()) {
                lastSplit = iterator.next();
            }
            return Integer.valueOf(lastSplit);
        }
    }

    private String extractErrorMessageFromResponse(final ClientResponse response)
    {
        String message = response.toString();

        // Extract Warning header, if any
        final MultivaluedMap<String, String> headers = response.getHeaders();
        if (headers != null) {
            final List<String> warnings = headers.get("Warning");
            if (warnings != null && warnings.size() > 0) {
                message = message + " - Warning " + warnings.get(0);
            }
        }
        return message;
    }

    private void createClient()
    {
        final DefaultAhcConfig config = new DefaultAhcConfig();
        config.getClasses().add(JacksonJsonProvider.class);
        client = Client.create(config);
    }

    private WebResource createWebResource()
    {
        String collectorUri = alertFinder.getAlertUri();
        if (!collectorUri.endsWith("/")) {
            collectorUri += "/";
        }
        collectorUri += RESOURCE_PATH;

        final WebResource resource = client.resource(collectorUri);
        resource
            .accept(MediaType.APPLICATION_JSON)
            .header("User-Agent", USER_AGENT);

        return resource;
    }
}