/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.recovery;

import com.google.common.base.Predicate;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.shard.DocsStats;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;

public class RecoveryWhileUnderLoadTests extends ElasticsearchIntegrationTest {

    private final ESLogger logger = Loggers.getLogger(RecoveryWhileUnderLoadTests.class);


    public class BackgroundIndexer implements AutoCloseable {

        final Thread[] writers;
        final CountDownLatch stopLatch;
        final CopyOnWriteArrayList<Throwable> failures;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong idGenerator = new AtomicLong();
        final AtomicLong indexCounter = new AtomicLong();
        final CountDownLatch startLatch = new CountDownLatch(1);

        public BackgroundIndexer() {
            this(scaledRandomIntBetween(3, 10));
        }

        public BackgroundIndexer(int writerCount) {
            this(writerCount, true);
        }

        public BackgroundIndexer(int writerCount, boolean autoStart) {

            failures = new CopyOnWriteArrayList<>();
            writers = new Thread[writerCount];
            stopLatch = new CountDownLatch(writers.length);
            logger.info("--> starting {} indexing threads", writerCount);
            for (int i = 0; i < writers.length; i++) {
                final int indexerId = i;
                final Client client = client();
                writers[i] = new Thread() {
                    @Override
                    public void run() {
                        long id = -1;
                        try {
                            startLatch.await();
                            logger.info("**** starting indexing thread {}", indexerId);
                            while (!stop.get()) {
                                id = idGenerator.incrementAndGet();
                                client.prepareIndex("test", "type1", Long.toString(id) + "-" + indexerId)
                                        .setSource(MapBuilder.<String, Object>newMapBuilder().put("test", "value" + id).map()).execute().actionGet();
                                indexCounter.incrementAndGet();
                            }
                            logger.info("**** done indexing thread {}", indexerId);
                        } catch (Throwable e) {
                            failures.add(e);
                            logger.warn("**** failed indexing thread {} on doc id {}", e, indexerId, id);
                        } finally {
                            stopLatch.countDown();
                        }
                    }
                };
                writers[i].start();
            }

            if (autoStart) {
                startLatch.countDown();
            }
        }

        public void start() {
            startLatch.countDown();
        }

        public void stop() throws InterruptedException {
            if (stop.get()) {
                return;
            }
            stop.set(true);

            assertThat("timeout while waiting for indexing threads to stop", stopLatch.await(6, TimeUnit.MINUTES), equalTo(true));
            assertNoFailures();
        }

        public long totalIndexedDocs() {
            return indexCounter.get();
        }

        public Throwable[] getFailures() {
            return failures.toArray(new Throwable[failures.size()]);
        }

        public void assertNoFailures() {
            assertThat(failures, emptyIterable());
        }

        @Override
        public void close() throws Exception {
            stop();
        }
    }

    @Test
    @TestLogging("action.search.type:TRACE,action.admin.indices.refresh:TRACE")
    @Slow
    public void recoverWhileUnderLoadAllocateBackupsTest() throws Exception {
        logger.info("--> creating test index ...");
        int numberOfShards = numberOfShards();
        assertAcked(prepareCreate("test", 1, settingsBuilder().put(SETTING_NUMBER_OF_SHARDS, numberOfShards).put(SETTING_NUMBER_OF_REPLICAS, 1)));

        final int totalNumDocs = scaledRandomIntBetween(200, 20000);
        try (BackgroundIndexer indexer = new BackgroundIndexer()) {
            int waitFor = totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);

            logger.info("--> flushing the index ....");
            // now flush, just to make sure we have some data in the index, not just translog
            client().admin().indices().prepareFlush().execute().actionGet();

            waitFor += totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);

            logger.info("--> allow 2 nodes for index [test] ...");
            // now start another node, while we index
            allowNodes("test", 2);

            logger.info("--> waiting for GREEN health status ...");
            // make sure the cluster state is green, and all has been recovered
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().setWaitForNodes(">=2").execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> waiting for {} docs to be indexed ...", totalNumDocs);
            waitForDocs(totalNumDocs);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed", totalNumDocs);

            logger.info("--> marking and waiting for indexing threads to stop ...");
            indexer.stop();
            logger.info("--> indexing threads stopped");

            logger.info("--> refreshing the index");
            refreshAndAssert();
            logger.info("--> verifying indexed content");
            iterateAssertCount(numberOfShards, indexer.totalIndexedDocs(), 10);
        }
    }

    @Test
    @TestLogging("action.search.type:TRACE,action.admin.indices.refresh:TRACE")
    @Slow
    public void recoverWhileUnderLoadAllocateBackupsRelocatePrimariesTest() throws Exception {
        logger.info("--> creating test index ...");
        int numberOfShards = numberOfShards();
        assertAcked(prepareCreate("test", 1, settingsBuilder().put(SETTING_NUMBER_OF_SHARDS, numberOfShards).put(SETTING_NUMBER_OF_REPLICAS, 1)));

        final int totalNumDocs = scaledRandomIntBetween(200, 20000);
        try (BackgroundIndexer indexer = new BackgroundIndexer()) {
            int waitFor = totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);

            logger.info("--> flushing the index ....");
            // now flush, just to make sure we have some data in the index, not just translog
            client().admin().indices().prepareFlush().execute().actionGet();

            waitFor += totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);
            logger.info("--> allow 4 nodes for index [test] ...");
            allowNodes("test", 4);

            logger.info("--> waiting for GREEN health status ...");
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().setWaitForNodes(">=4").execute().actionGet().isTimedOut(), equalTo(false));


            logger.info("--> waiting for {} docs to be indexed ...", totalNumDocs);
            waitForDocs(totalNumDocs);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed", totalNumDocs);

            logger.info("--> marking and waiting for indexing threads to stop ...");
            indexer.stop();
            logger.info("--> indexing threads stopped");

            logger.info("--> refreshing the index");
            refreshAndAssert();
            logger.info("--> verifying indexed content");
            iterateAssertCount(numberOfShards, indexer.totalIndexedDocs(), 10);
        }
    }

    @Test
    @TestLogging("action.search.type:TRACE,action.admin.indices.refresh:TRACE")
    @Slow
    public void recoverWhileUnderLoadWithNodeShutdown() throws Exception {
        logger.info("--> creating test index ...");
        int numberOfShards = numberOfShards();
        assertAcked(prepareCreate("test", 2, settingsBuilder().put(SETTING_NUMBER_OF_SHARDS, numberOfShards).put(SETTING_NUMBER_OF_REPLICAS, 1)));

        final int totalNumDocs = scaledRandomIntBetween(200, 20000);
        try (BackgroundIndexer indexer = new BackgroundIndexer()) {
            int waitFor = totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);

            logger.info("--> flushing the index ....");
            // now flush, just to make sure we have some data in the index, not just translog
            client().admin().indices().prepareFlush().execute().actionGet();

            waitFor += totalNumDocs / 10;
            logger.info("--> waiting for {} docs to be indexed ...", waitFor);
            waitForDocs(waitFor);
            indexer.assertNoFailures();
            logger.info("--> {} docs indexed",  waitFor);

            // now start more nodes, while we index
            logger.info("--> allow 4 nodes for index [test] ...");
            allowNodes("test", 4);

            logger.info("--> waiting for GREEN health status ...");
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().setWaitForNodes(">=4").execute().actionGet().isTimedOut(), equalTo(false));


            logger.info("--> waiting for {} docs to be indexed ...", totalNumDocs);
            waitForDocs(totalNumDocs);
            indexer.assertNoFailures();

            logger.info("--> {} docs indexed",  totalNumDocs);
            // now, shutdown nodes
            logger.info("--> allow 3 nodes for index [test] ...");
            allowNodes("test", 3);
            logger.info("--> waiting for GREEN health status ...");
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().setWaitForNodes(">=3").execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> allow 2 nodes for index [test] ...");
            allowNodes("test", 2);
            logger.info("--> waiting for GREEN health status ...");
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().setWaitForNodes(">=2").execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> allow 1 nodes for index [test] ...");
            allowNodes("test", 1);
            logger.info("--> waiting for YELLOW health status ...");
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForYellowStatus().setWaitForNodes(">=1").execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> marking and waiting for indexing threads to stop ...");
            indexer.stop();
            logger.info("--> indexing threads stopped");

            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForYellowStatus().setWaitForNodes(">=1").execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> refreshing the index");
            refreshAndAssert();
            logger.info("--> verifying indexed content");
            iterateAssertCount(numberOfShards, indexer.totalIndexedDocs(), 10);
        }
    }

    @Test
    @TestLogging("action.search.type:TRACE,action.admin.indices.refresh:TRACE,action.index:TRACE,action.support.replication:TRACE,cluster.service:DEBUG")
    @Slow
    public void recoverWhileRelocating() throws Exception {
        final int numShards = between(2, 10);
        final int numReplicas = 0;
        logger.info("--> creating test index ...");
        int allowNodes = 2;
        assertAcked(prepareCreate("test", 3, settingsBuilder().put(SETTING_NUMBER_OF_SHARDS, numShards).put(SETTING_NUMBER_OF_REPLICAS, numReplicas)));

        final int numDocs = scaledRandomIntBetween(200, 50000);

        try (BackgroundIndexer indexer = new BackgroundIndexer()) {

            for (int i = 0; i < numDocs; i += scaledRandomIntBetween(100, Math.min(1000, numDocs))) {
                indexer.assertNoFailures();
                logger.info("--> waiting for {} docs to be indexed ...", i);
                waitForDocs(i);
                logger.info("--> {} docs indexed", i);
                allowNodes = 2 / allowNodes;
                allowNodes("test", allowNodes);
                logger.info("--> waiting for GREEN health status ...");
                assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().execute().actionGet().isTimedOut(), equalTo(false));
            }

            logger.info("--> marking and waiting for indexing threads to stop ...");
            indexer.stop();

            logger.info("--> indexing threads stopped");
            logger.info("--> bump up number of replicas to 1 and allow all nodes to hold the index");
            allowNodes("test", 3);
            assertAcked(client().admin().indices().prepareUpdateSettings("test").setSettings(settingsBuilder().put("number_of_replicas", 1)).get());
            assertThat(client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setTimeout("1m").setWaitForGreenStatus().execute().actionGet().isTimedOut(), equalTo(false));

            logger.info("--> refreshing the index");
            refreshAndAssert();
            logger.info("--> verifying indexed content");
            iterateAssertCount(numShards, indexer.totalIndexedDocs(), 10);
        }
    }

    private void iterateAssertCount(final int numberOfShards, final long numberOfDocs, final int iterations) throws Exception {
        SearchResponse[] iterationResults = new SearchResponse[iterations];
        boolean error = false;
        for (int i = 0; i < iterations; i++) {
            SearchResponse searchResponse = client().prepareSearch().setSearchType(SearchType.COUNT).setQuery(matchAllQuery()).get();
            logSearchResponse(numberOfShards, numberOfDocs, i, searchResponse);
            iterationResults[i] = searchResponse;
            if (searchResponse.getHits().totalHits() != numberOfDocs) {
                error = true;
            }
        }

        if (error) {
            //Printing out shards and their doc count
            IndicesStatsResponse indicesStatsResponse = client().admin().indices().prepareStats().get();
            for (ShardStats shardStats : indicesStatsResponse.getShards()) {
                DocsStats docsStats = shardStats.getStats().docs;
                logger.info("shard [{}] - count {}, primary {}", shardStats.getShardId(), docsStats.getCount(), shardStats.getShardRouting().primary());
            }

            //if there was an error we try to wait and see if at some point it'll get fixed
            logger.info("--> trying to wait");
            assertThat(awaitBusy(new Predicate<Object>() {
                @Override
                public boolean apply(Object o) {
                    boolean error = false;
                    for (int i = 0; i < iterations; i++) {
                        SearchResponse searchResponse = client().prepareSearch().setSearchType(SearchType.COUNT).setQuery(matchAllQuery()).get();
                        if (searchResponse.getHits().totalHits() != numberOfDocs) {
                            error = true;
                        }
                    }
                    return !error;
                }
            }, 5, TimeUnit.MINUTES), equalTo(true));
        }

        //lets now make the test fail if it was supposed to fail
        for (int i = 0; i < iterations; i++) {
            assertHitCount(iterationResults[i], numberOfDocs);
        }
    }

    private void logSearchResponse(int numberOfShards, long numberOfDocs, int iteration, SearchResponse searchResponse) {
        logger.info("iteration [{}] - successful shards: {} (expected {})", iteration, searchResponse.getSuccessfulShards(), numberOfShards);
        logger.info("iteration [{}] - failed shards: {} (expected 0)", iteration, searchResponse.getFailedShards());
        if (searchResponse.getShardFailures() != null && searchResponse.getShardFailures().length > 0) {
            logger.info("iteration [{}] - shard failures: {}", iteration, Arrays.toString(searchResponse.getShardFailures()));
        }
        logger.info("iteration [{}] - returned documents: {} (expected {})", iteration, searchResponse.getHits().totalHits(), numberOfDocs);
    }

    private void refreshAndAssert() throws InterruptedException {
        assertThat(awaitBusy(new Predicate<Object>() {
            public boolean apply(Object o) {
                try {
                    RefreshResponse actionGet = client().admin().indices().prepareRefresh().execute().actionGet();
                    assertNoFailures(actionGet);
                    return actionGet.getTotalShards() == actionGet.getSuccessfulShards();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        }, 5, TimeUnit.MINUTES), equalTo(true));
    }

    private void waitForDocs(final long numDocs) throws InterruptedException {
        final long[] lastKnownCount = {-1};
        long lastStartCount = -1;
        Predicate<Object> testDocs = new Predicate<Object>() {
            public boolean apply(Object o) {
                lastKnownCount[0] = client().prepareCount().setQuery(matchAllQuery()).execute().actionGet().getCount();
                logger.debug("[{}] docs visible for search. waiting for [{}]", lastKnownCount[0], numDocs);
                return lastKnownCount[0] > numDocs;
            }
        };
        // 5 minutes seems like a long time but while relocating,    indexing threads can wait for up to ~1m before retrying when
        // they first try to index into a shard which is not STARTED.
        while (!awaitBusy(testDocs, 5, TimeUnit.MINUTES)) {
            if (lastStartCount == lastKnownCount[0]) {
                // we didn't make any progress
                fail("failed to reach " + numDocs + "docs");
            }
            lastStartCount = lastKnownCount[0];
        }
    }
}
