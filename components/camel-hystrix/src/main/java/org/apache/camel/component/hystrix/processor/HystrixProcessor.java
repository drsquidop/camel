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
package org.apache.camel.component.hystrix.processor;

import java.util.ArrayList;
import java.util.List;

import com.netflix.hystrix.HystrixCommand;
import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Navigate;
import org.apache.camel.Processor;
import org.apache.camel.spi.IdAware;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.AsyncProcessorConverterHelper;
import org.apache.camel.util.AsyncProcessorHelper;

/**
 * Implementation of the Hystrix EIP.
 */
public class HystrixProcessor extends ServiceSupport implements AsyncProcessor, Navigate<Processor>, org.apache.camel.Traceable, IdAware {

    private String id;
    private final HystrixCommand.Setter setter;
    private final AsyncProcessor processor;
    private final AsyncProcessor fallback;

    public HystrixProcessor(String id, HystrixCommand.Setter setter, Processor processor, Processor fallback) {
        this.id = id;
        this.setter = setter;
        this.processor = AsyncProcessorConverterHelper.convert(processor);
        this.fallback = AsyncProcessorConverterHelper.convert(fallback);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getTraceLabel() {
        return "hystrix";
    }

    @Override
    public List<Processor> next() {
        if (!hasNext()) {
            return null;
        }
        List<Processor> answer = new ArrayList<Processor>();
        answer.add(processor);
        if (fallback != null) {
            answer.add(fallback);
        }
        return answer;
    }

    @Override
    public boolean hasNext() {
        return true;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AsyncProcessorHelper.process(this, exchange);
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        HystrixProcessorCommand command = new HystrixProcessorCommand(setter, exchange, callback, processor, fallback);
        try {
            command.queue();
        } catch (Throwable e) {
            // error adding to queue, so set as error and we are done
            exchange.setException(e);
            callback.done(true);
            return true;
        }

        return false;
    }

    @Override
    protected void doStart() throws Exception {
        // noop
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }

}