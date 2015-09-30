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
package org.apache.camel.component.splunk;

import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.splunk.event.SplunkEvent;
import org.junit.Test;

import java.io.InputStream;
import java.util.Map;

import com.splunk.Job;
import com.splunk.JobArgs;
import com.splunk.JobCollection;
import com.splunk.JobResultsArgs;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SplunkQueryProducerTest extends SplunkMockTestSupport {

    @EndpointInject(uri = "splunk://normal")
    protected SplunkEndpoint searchEndpoint;

    @Test
    public void testSearch() throws Exception {
        //setup camel expectations
        MockEndpoint searchMock = getMockEndpoint("mock:search-result");
        searchMock.expectedMessageCount(1);
        searchMock.expectedHeaderReceived(SplunkConstants.ROW_COUNT, 3);

        MockEndpoint splitSearchMock = getMockEndpoint("mock:split-search-result");
        splitSearchMock.expectedMessageCount(3);

        //setup splunk API mocks
        JobCollection jobCollection = mock(JobCollection.class);
        Job jobMock = mock(Job.class);
        when(service.getJobs()).thenReturn(jobCollection);
        when(jobCollection.create(anyString(), any(JobArgs.class))).thenReturn(jobMock);
        when(jobMock.isDone()).thenReturn(Boolean.TRUE);
        InputStream stream = ProducerTest.class.getResourceAsStream("/resultsreader_test_data.json");
        when(jobMock.getResults(any(JobResultsArgs.class))).thenReturn(stream);

        //verify expectations
        template.sendBody("direct:search", null);
        assertMockEndpointsSatisfied();
        //verify data type
        SplunkEvent received = splitSearchMock.getReceivedExchanges().get(0).getIn().getBody(SplunkEvent.class);
        assertNotNull(received);
        Map<String, String> data = received.getEventData();
        assertEquals("indexertpool", data.get("name"));
//        assertEquals(true, searchMock.getReceivedExchanges().get(2).getProperty(Exchange.BATCH_COMPLETE, Boolean.class));
        stream.close();
    }

    /* additional tests
        - DONE run search as producer
        - OK return search as batch (list of map)
        - return _to cutoff time in header
        - initialize with cutoff time from datasource
        - idempotent test of overlapping queries
        - return over 100 results!
     */

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            public void configure() {
                from("direct:search").to("splunk://normal?delay=5s&username=foo&password=bar&initEarliestTime=-10s&latestTime=now&search=search index=myindex&sourceType=testSource")
                        .to("mock:search-result")
                        .split(body())
                        .to("mock:split-search-result");
            }
        };
    }
}
