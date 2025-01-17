/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.tests.queryablestate;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.util.Collector;

import org.apache.flink.shaded.guava30.com.google.common.collect.Iterables;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * Streaming application that creates an {@link Email} pojo with random ids and increasing
 * timestamps and passes it to a stateful {@link
 * org.apache.flink.api.common.functions.FlatMapFunction}, where it is exposed as queryable state.
 */
public class QsStateProducer {

    public static void main(final String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        ParameterTool tool = ParameterTool.fromArgs(args);
        String tmpPath = tool.getRequired("tmp-dir");
        String stateBackendType = tool.getRequired("state-backend");

        StateBackend stateBackend;
        switch (stateBackendType) {
            case "rocksdb":
                stateBackend = new RocksDBStateBackend(tmpPath);
                break;
            case "fs":
                stateBackend = new FsStateBackend(tmpPath);
                break;
            case "memory":
                stateBackend = new MemoryStateBackend();
                break;
            default:
                throw new RuntimeException("Unsupported state backend " + stateBackendType);
        }

        env.setStateBackend(stateBackend);
        env.enableCheckpointing(1000L);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(0);

        env.addSource(new EmailSource())
                .keyBy(
                        new KeySelector<Email, String>() {

                            private static final long serialVersionUID = -1480525724620425363L;

                            @Override
                            public String getKey(Email value) throws Exception {
                                return QsConstants.KEY;
                            }
                        })
                .flatMap(new TestFlatMap());

        env.execute();
    }

    private static class EmailSource extends RichSourceFunction<Email> {

        private static final long serialVersionUID = -7286937645300388040L;

        private transient volatile boolean isRunning;

        private transient Random random;

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            this.random = new Random();
            this.isRunning = true;
        }

        @Override
        public void run(SourceContext<Email> ctx) throws Exception {
            // Sleep for 10 seconds on start to allow time to copy jobid
            Thread.sleep(10000L);

            int types = LabelSurrogate.Type.values().length;

            while (isRunning) {
                int r = random.nextInt(100);

                final EmailId emailId = new EmailId(Integer.toString(random.nextInt()));
                final Instant timestamp = Instant.now().minus(Duration.ofDays(1L));
                final String foo = String.format("foo #%d", r);
                final LabelSurrogate label =
                        new LabelSurrogate(LabelSurrogate.Type.values()[r % types], "bar");

                synchronized (ctx.getCheckpointLock()) {
                    ctx.collect(new Email(emailId, timestamp, foo, label));
                }

                Thread.sleep(30L);
            }
        }

        @Override
        public void cancel() {
            isRunning = false;
        }
    }

    private static class TestFlatMap extends RichFlatMapFunction<Email, Object>
            implements CheckpointListener {

        private static final long serialVersionUID = 7821128115999005941L;

        private static final Logger LOG = LoggerFactory.getLogger(TestFlatMap.class);

        private transient MapState<EmailId, EmailInformation> state;
        private transient int count;

        @Override
        public void open(Configuration parameters) throws Exception {
            MapStateDescriptor<EmailId, EmailInformation> stateDescriptor =
                    new MapStateDescriptor<>(
                            QsConstants.STATE_NAME,
                            TypeInformation.of(new TypeHint<EmailId>() {}),
                            TypeInformation.of(new TypeHint<EmailInformation>() {}));

            stateDescriptor.setQueryable(QsConstants.QUERY_NAME);
            state = getRuntimeContext().getMapState(stateDescriptor);
            updateCount();

            LOG.info("Open {} with a count of {}.", getClass().getSimpleName(), count);
        }

        private void updateCount() throws Exception {
            count = Iterables.size(state.keys());
        }

        @Override
        public void flatMap(Email value, Collector<Object> out) throws Exception {
            state.put(value.getEmailId(), new EmailInformation(value));
            updateCount();
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            System.out.println("Count on snapshot: " + count); // we look for it in the test
        }
    }
}
