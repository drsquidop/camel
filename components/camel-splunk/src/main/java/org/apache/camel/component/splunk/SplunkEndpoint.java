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

import java.net.ConnectException;
import java.net.SocketException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

import com.splunk.Service;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.ScheduledPollEndpoint;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a Splunk endpoint.
 */
@UriEndpoint(scheme = "splunk", title = "Splunk", syntax = "splunk:name", consumerClass = SplunkConsumer.class, label = "monitoring")
public class SplunkEndpoint extends ScheduledPollEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(SplunkEndpoint.class);

    private Service service;
    @UriParam
    private SplunkConfiguration configuration;

    public SplunkEndpoint() {
    }

    public SplunkEndpoint(String uri, SplunkComponent component, SplunkConfiguration configuration) {
        super(uri, component);
        this.configuration = configuration;
    }

    public Producer createProducer() throws Exception {
        String[] uriSplit = splitUri(getEndpointUri());
        if (uriSplit.length > 0) {
            ProducerType producerType = null;
            try {
                producerType = ProducerType.fromUri(uriSplit[0]);
                return new SplunkProducer(this, producerType);
            } catch (RuntimeException e) {}
            if (producerType == null) {
                //if its not a pure producer, see if its a search
                ConsumerType consumerType = ConsumerType.fromUri(uriSplit[0]);
                SplunkQueryProducer producer = new SplunkQueryProducer(this, consumerType);
                //configureConsumer(producer);
                return producer;
            }
        }
        throw new IllegalArgumentException("Cannot create any producer with uri " + getEndpointUri() + ". A producer type was not provided (or an incorrect pairing was used).");
    }

    public Consumer createConsumer(Processor processor) throws Exception {
        if (configuration.getInitEarliestTime() == null) {
            throw new IllegalArgumentException("Required initialEarliestTime option could not be found");
        }
        String[] uriSplit = splitUri(getEndpointUri());
        if (uriSplit.length > 0) {
            ConsumerType consumerType = ConsumerType.fromUri(uriSplit[0]);
            SplunkConsumer consumer = new SplunkConsumer(this, processor, consumerType);
            configureConsumer(consumer);
            return consumer;
        }
        throw new IllegalArgumentException("Cannot create any consumer with uri " + getEndpointUri() + ". A consumer type was not provided (or an incorrect pairing was used).");
    }

    public boolean isSingleton() {
        return true;
    }

    @Override
    protected void doStop() throws Exception {
        service = null;
        super.doStop();
    }

    public Service getService() {
        if (service == null) {
            this.service = configuration.getConnectionFactory().createService(getCamelContext());
        }
        return service;
    }

    private static String[] splitUri(String uri) {
        Pattern p1 = Pattern.compile("splunk:(//)*");
        Pattern p2 = Pattern.compile("\\?.*");

        uri = p1.matcher(uri).replaceAll("");
        uri = p2.matcher(uri).replaceAll("");

        return uri.split("/");
    }

    public SplunkConfiguration getConfiguration() {
        return configuration;
    }

    public synchronized boolean reset(Exception e) {
        boolean answer = false;
        if ((e instanceof RuntimeException && ((RuntimeException)e).getCause() instanceof ConnectException) || ((e instanceof SocketException) || (e instanceof SSLException))) {
            LOG.warn("Got exception from Splunk. Service will be reset.");
            this.service = null;
            answer = true;
        }
        return answer;
    }
}
