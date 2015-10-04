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
package org.apache.camel.component.splunk.support;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import com.splunk.Job;
import com.splunk.JobArgs;
import com.splunk.JobArgs.ExecutionMode;
import com.splunk.JobArgs.SearchMode;
import com.splunk.JobResultsArgs;
import com.splunk.JobResultsArgs.OutputMode;
import com.splunk.ResultsReader;
import com.splunk.ResultsReaderJson;
import com.splunk.SavedSearch;
import com.splunk.SavedSearchCollection;
import com.splunk.SavedSearchDispatchArgs;
import com.splunk.Service;
import com.splunk.ServiceArgs;

import org.apache.camel.component.splunk.ConsumerType;
import org.apache.camel.component.splunk.SplunkEndpoint;
import org.apache.camel.component.splunk.event.SplunkEvent;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SplunkDataReader {
    private static final Logger LOG = LoggerFactory.getLogger(SplunkDataReader.class);
    private static final String DATE_FORMAT = "MM/dd/yy HH:mm:ss:SSS";
    private static final String SPLUNK_TIME_FORMAT = "%m/%d/%y %H:%M:%S:%3N";

    private transient Calendar lastSuccessfulReadTime;
    private SplunkEndpoint endpoint;
    private ConsumerType consumerType;

    public SplunkDataReader(SplunkEndpoint endpoint, ConsumerType consumerType) {
        this.endpoint = endpoint;
        this.consumerType = consumerType;
    }

    public int getCount() {
        return endpoint.getConfiguration().getCount();
    }

    public Calendar getLastSuccesfulReadTime() {
        return lastSuccessfulReadTime;
    }

    public String getSearch() {
        return endpoint.getConfiguration().getSearch();
    }

    public String getEarliestTime() {
        return endpoint.getConfiguration().getEarliestTime();
    }

    public String getLatestTime() {
        return endpoint.getConfiguration().getLatestTime();
    }

    public String getInitEarliestTime() {
        return endpoint.getConfiguration().getInitEarliestTime();
    }

    private String getSavedSearch() {
        return endpoint.getConfiguration().getSavedSearch();
    }

    public List<SplunkEvent> read() throws Exception {
        // Read without callback
        return read(null);
    }

    public List<SplunkEvent> read(SplunkResultProcessor callback) throws Exception {
        switch (consumerType) {
        case NORMAL: {
            return nonBlockingSearch(callback);
        }
        case REALTIME: {
            return realtimeSearch(callback);
        }
        case SAVEDSEARCH: {
            return savedSearch(callback);
        }
        default: {
            throw new RuntimeException("Unknown search mode " + consumerType);
        }
        }
    }

    /**
     * Get the earliestTime of range search.
     * 
     * @param startTime the time where search start
     * @param realtime if this is realtime search
     * @return The time of last successful read if not realtime; Time difference
     *         between last successful read and start time;
     */
    private String calculateEarliestTime(Calendar startTime, boolean realtime) {
        String result;
        if (realtime) {
            result = calculateEarliestTimeForRealTime(startTime);
        } else {
            DateFormat df = new SimpleDateFormat(DATE_FORMAT);
            //TODO this needs to pull from a property and ignore if not specified
            //TODO restore df.setTimeZone(TimeZone.getTimeZone("GMT"));
            result = df.format(lastSuccessfulReadTime.getTime());
        }
        return result;
    }

    /**
     * Gets earliest time for realtime search
     */
    private String calculateEarliestTimeForRealTime(Calendar startTime) {
        String result;
        long diff = startTime.getTimeInMillis() - lastSuccessfulReadTime.getTimeInMillis();
        result = "-" + diff / 1000 + "s";
        return result;
    }

    private void populateArgs(JobArgs queryArgs, Calendar startTime, boolean realtime) {
        String earliestTime = getEarliestTime(startTime, realtime);
        if (ObjectHelper.isNotEmpty(earliestTime)) {
            queryArgs.setEarliestTime(earliestTime);
        }

        String latestTime = getLatestTime(startTime, realtime);
        if (ObjectHelper.isNotEmpty(latestTime)) {
            queryArgs.setLatestTime(latestTime);
        }

        queryArgs.setTimeFormat(SPLUNK_TIME_FORMAT);

    }

    private String getLatestTime(Calendar startTime, boolean realtime) {
        String lTime = null;
        if (ObjectHelper.isNotEmpty(getLatestTime())) {
            lTime = getLatestTime();
        } else {
            if (realtime) {
                lTime = "rt";
            }
            /*TODO set no rolling latest time unless it was initially set
                consider purpose, if an initial rolling time is set, we should roll in some increment
                until we get to now
            */
            /*
            else {
                DateFormat df = new SimpleDateFormat(DATE_FORMAT);
                lTime = df.format(startTime.getTime());
            }*/
        }
        return lTime;
    }

    private String getEarliestTime(Calendar startTime, boolean realtime) {
        String eTime = null;

        if (lastSuccessfulReadTime == null) {
            eTime = getInitEarliestTime();
        } else {
            if (ObjectHelper.isNotEmpty(getEarliestTime())) {
                eTime = getEarliestTime();
            } else {
                String calculatedEarliestTime = calculateEarliestTime(startTime, realtime);
                if (calculatedEarliestTime != null) {
                    if (realtime) {
                        eTime = "rt" + calculatedEarliestTime;
                    } else {
                        eTime = calculatedEarliestTime;
                    }
                }
            }
        }
        return eTime;
    }

    private List<SplunkEvent> savedSearch(SplunkResultProcessor callback) throws Exception {
        LOG.trace("saved search start");

        ServiceArgs queryArgs = new ServiceArgs();
        queryArgs.setApp("search");
        if (ObjectHelper.isNotEmpty(endpoint.getConfiguration().getOwner())) {
            queryArgs.setOwner(endpoint.getConfiguration().getOwner());
        }
        if (ObjectHelper.isNotEmpty(endpoint.getConfiguration().getApp())) {
            queryArgs.setApp(endpoint.getConfiguration().getApp());
        }

        Calendar startTime = Calendar.getInstance();

        SavedSearch search = null;
        Job job = null;
        String latestTime = getLatestTime(startTime, false);
        String earliestTime = getEarliestTime(startTime, false);

        Service service = endpoint.getService();
        SavedSearchCollection savedSearches = service.getSavedSearches(queryArgs);
        for (SavedSearch s : savedSearches.values()) {
            if (s.getName().equals(getSavedSearch())) {
                search = s;
                break;
            }
        }
        if (search != null) {
            SavedSearchDispatchArgs args = new SavedSearchDispatchArgs();
            args.setForceDispatch(true);
            args.setDispatchEarliestTime(earliestTime);
            args.setDispatchLatestTime(latestTime);
            job = search.dispatch(args);
        } else {
            throw new RuntimeException("Unable to find saved search '" + getSavedSearch() + "'.");
        }
        while (!job.isDone()) {
            Thread.sleep(2000);
        }
        List<SplunkEvent> data = extractData(job, false, callback);
        this.lastSuccessfulReadTime = startTime;
        return data;

    }

    private List<SplunkEvent> nonBlockingSearch(SplunkResultProcessor callback) throws Exception {
        LOG.debug("non block search start");

        JobArgs queryArgs = new JobArgs();
        queryArgs.setExecutionMode(ExecutionMode.NORMAL);
        Calendar startTime = Calendar.getInstance();
        populateArgs(queryArgs, startTime, false);
        //TODO needed? queryArgs.setMaximumCount(0); //return all results

        List<SplunkEvent> data = runQuery(queryArgs, false, callback);
        lastSuccessfulReadTime = startTime;
        return data;
    }

    private List<SplunkEvent> realtimeSearch(SplunkResultProcessor callback) throws Exception {
        LOG.debug("realtime search start");

        JobArgs queryArgs = new JobArgs();
        queryArgs.setExecutionMode(ExecutionMode.NORMAL);
        queryArgs.setSearchMode(SearchMode.REALTIME);
        Calendar startTime = Calendar.getInstance();
        populateArgs(queryArgs, startTime, true);

        List<SplunkEvent> data = runQuery(queryArgs, true, callback);
        lastSuccessfulReadTime = startTime;
        return data;
    }

    private List<SplunkEvent> runQuery(JobArgs queryArgs, boolean realtime, SplunkResultProcessor callback) throws Exception {
        Service service = endpoint.getService();
        Job job = service.getJobs().create(getSearch(), queryArgs);
        LOG.debug("Running search : {} with queryArgs : {}", getSearch(), queryArgs);
        if (realtime) {
            while (!job.isReady()) {
                Thread.sleep(500);
                LOG.debug("waiting for isReady");
            }
            // Besides job.isReady there must be some delay before real time job
            // is ready
            // TODO seems that the realtime stream is not quite isReady to be
            // read
            Thread.sleep(4000);
        } else {
            while (!job.isDone()) {
                Thread.sleep(500);
            }
        }
        LOG.debug("search complete: latest={}, search_latest={}",job.getLatestTime(), job.getSearchLatestTime());
        return extractData(job, realtime, callback);
    }

    private List<SplunkEvent> extractData(Job job, boolean realtime, SplunkResultProcessor callback) throws Exception {
        List<SplunkEvent> result = new ArrayList<SplunkEvent>();
        HashMap<String, String> data;
        SplunkEvent splunkData;
        ResultsReader resultsReader = null;
        int total = 0;
        if (realtime) {
            total = job.getResultPreviewCount();
        } else {
            total = job.getResultCount();
            LOG.debug("job has {} results", total);
        }
        //if getCount is 0 get all the results, otherwise limit the results
        //TODO the messages should be limited on the query, not on the retrieval.  Oldest messages may/maynot be returned earliest
        int maxCount = (getCount() == 0) ? total : getCount();
        LOG.debug("extractData(): job is ready/done, total={}, endpoint.count={}", total, getCount());

        int offset = 0;

        //TODO a test is to prime the system with over 100 messages
        while (offset < total) {
            InputStream stream;
            JobResultsArgs outputArgs = new JobResultsArgs();
            outputArgs.setOutputMode(OutputMode.JSON);
            outputArgs.setCount(maxCount);
            outputArgs.setOffset(offset);
            if (realtime) {
                //TODO grok this
                if (getCount() > 0) {
                    outputArgs.setCount(getCount());
                }
                stream = job.getResultsPreview(outputArgs);
            } else {
                stream = job.getResults(outputArgs);
            }
            resultsReader = new ResultsReaderJson(stream);
            while ((data = resultsReader.getNextEvent()) != null) {
                splunkData = new SplunkEvent(data);
                if (callback != null) {
                    callback.process(splunkData);
                } else {
                    result.add(splunkData);
                }
                offset ++;
            }
            IOHelper.close(stream);
        }
        if (resultsReader != null) {
            resultsReader.close();
        }
        job.cancel();
        return result;
    }

}
