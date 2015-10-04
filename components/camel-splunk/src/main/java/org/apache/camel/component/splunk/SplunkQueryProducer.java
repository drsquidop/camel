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

import org.apache.camel.Exchange;
import org.apache.camel.component.splunk.event.SplunkEvent;
import org.apache.camel.component.splunk.support.*;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * In a manner similiar to the camel sql component. this is a producer capable of doing queries so that splunk queries can be triggered by timers, wrapped in idempotent,
 * and dynamically configured with the 'Recipient List' pattern.
 */
public class SplunkQueryProducer extends DefaultProducer {
    private static final transient Logger LOG = LoggerFactory.getLogger(SplunkQueryProducer.class);
    private static final String DATE_FORMAT = "MM/dd/yy HH:mm:ss:SSS";

    private SplunkEndpoint endpoint;
    private SplunkDataReader dataReader;

    public SplunkQueryProducer(SplunkEndpoint endpoint, ConsumerType producerType) {
        super(endpoint);
        this.endpoint = endpoint;

        //TODO streaming parameter not supported
        //TODO move below to common validate configuration

        if (producerType.equals(ConsumerType.NORMAL) || producerType.equals(ConsumerType.REALTIME)) {
            if (ObjectHelper.isEmpty(endpoint.getConfiguration().getSearch())) {
                throw new RuntimeException("Missing option 'search' with normal or realtime search");
            }
        }
        if (producerType.equals(ConsumerType.SAVEDSEARCH) && ObjectHelper.isEmpty(endpoint.getConfiguration().getSavedSearch())) {
            throw new RuntimeException("Missing option 'savedSearch' with saved search");
        }
        dataReader = new SplunkDataReader(endpoint, producerType);
    }

    public void process(Exchange exchange) throws Exception {
        try {
            //extract additional parameters from exchange headers
            //if a LAST_SEARCH_TIME is provided, it takes precedence over the initEarliestTime parameter
            Calendar lastReadTime;
            DateFormat df = new SimpleDateFormat(DATE_FORMAT);
            //TODO restore df.setTimeZone(TimeZone.getTimeZone("GMT"));

            if (!ObjectHelper.isEmpty(exchange.getIn().getHeader(SplunkConstants.LAST_READ_TIME))) {
                lastReadTime = Calendar.getInstance();
                lastReadTime.setTime(df.parse(exchange.getIn().getHeader(SplunkConstants.LAST_READ_TIME).toString()));
                endpoint.getConfiguration().setInitEarliestTime(exchange.getIn().getHeader(SplunkConstants.LAST_READ_TIME).toString());
                //dataReader.setLastSuccessfulReadTime(lastReadTime);
                log.debug("Splunk exchange configured with lastReadTime={}",lastReadTime);
            }

            List<SplunkEvent> events = dataReader.read();
            exchange.getOut().setBody(events);

            lastReadTime = dataReader.getLastSuccesfulReadTime();

            exchange.getOut().setHeaders(exchange.getIn().getHeaders());
            exchange.getOut().setHeader(SplunkConstants.LAST_READ_TIME, df.format(lastReadTime.getTime()));
            exchange.getOut().setHeader(SplunkConstants.ROW_COUNT, events.size());

        } catch (Exception e) {
            endpoint.reset(e);
            throw e;
        }
    }

}
