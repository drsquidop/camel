/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.splunk.integration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.splunk.event.SplunkEvent;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class NormalSearchIT extends SplunkTest {
    @Test
    public void testSearch() throws Exception {
        log.debug("testSearch()");

        assertNotNull(source);

        MockEndpoint searchMock = getMockEndpoint("mock:search-result");
        searchMock.expectedMessageCount(1);
        getMockEndpoint("mock:submit-result").expectedMessageCount(1);

        assertMockEndpointsSatisfied(20, TimeUnit.SECONDS);
        SplunkEvent received = searchMock.getReceivedExchanges().get(0).getIn().getBody(SplunkEvent.class);
        assertNotNull(received);
        Map<String, String> data = received.getEventData();
        assertEquals("value1", data.get("key1"));
        assertEquals("value2", data.get("key2"));
        assertEquals("value3", data.get("key3"));
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        log.debug("createRouteBuilder()");
        //routeBuilder is called before @Before.  since source is set in the endpoint, it must be set here
        source = UUID.randomUUID().toString();

        return new RouteBuilder() {
            public void configure() {
                from("direct:submit").to("splunk://submit?username=" + SPLUNK_USERNAME + "&password=" + SPLUNK_PASSWORD + "&index=" + INDEX + "&sourceType=testSource&source=" + source)
                        .to("mock:submit-result");

                from("splunk://normal?delay=5s&username=" + SPLUNK_USERNAME + "&password=" + SPLUNK_PASSWORD + "&initEarliestTime=-60s" + "&search=search index="
                                + INDEX + " sourcetype=testSource source=" + source + " | fields key1, key2, key3").to("mock:search-result");
            }
        };
    }
}
