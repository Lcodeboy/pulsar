/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.systopic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.apache.pulsar.client.internal.DefaultImplementation;
import org.apache.pulsar.common.events.ActionType;
import org.apache.pulsar.common.events.PulsarEvent;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.naming.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System topic for topic policy.
 */
public class TopicPoliciesSystemTopicClient extends SystemTopicClientBase<PulsarEvent> {

    static Schema<PulsarEvent> avroSchema = DefaultImplementation.getDefaultImplementation()
            .newAvroSchema(SchemaDefinition.builder().withPojo(PulsarEvent.class).build());

    public TopicPoliciesSystemTopicClient(PulsarClient client, TopicName topicName) {
        super(client, topicName);

    }

    @Override
    protected  CompletableFuture<Writer<PulsarEvent>> newWriterAsyncInternal() {
        return client.newProducer(avroSchema)
                .topic(topicName.toString())
                .enableBatching(false)
                .createAsync()
                .thenApply(producer -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] A new writer is created", topicName);
                    }
                    return new TopicPolicyWriter(producer, TopicPoliciesSystemTopicClient.this);
                });
    }

    @Override
    protected CompletableFuture<Reader<PulsarEvent>> newReaderAsyncInternal() {
        return client.newReader(avroSchema)
                .topic(topicName.toString())
                .subscriptionRolePrefix(SystemTopicNames.SYSTEM_READER_PREFIX)
                .startMessageId(MessageId.earliest)
                .readCompacted(true)
                .poolMessages(true)
                .createAsync()
                .thenApply(reader -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] A new reader is created", topicName);
                    }
                    return new TopicPolicyReader(reader, TopicPoliciesSystemTopicClient.this);
                });
    }

    private static class TopicPolicyWriter implements Writer<PulsarEvent> {

        private final Producer<PulsarEvent> producer;
        private final SystemTopicClient<PulsarEvent> systemTopicClient;

        private TopicPolicyWriter(Producer<PulsarEvent> producer, SystemTopicClient<PulsarEvent> systemTopicClient) {
            this.producer = producer;
            this.systemTopicClient = systemTopicClient;
        }

        @Override
        public MessageId write(String key, PulsarEvent event) throws PulsarClientException {
            TypedMessageBuilder<PulsarEvent> builder = producer.newMessage().key(key).value(event);
            setReplicateCluster(event, builder);
            return builder.send();
        }

        @Override
        public CompletableFuture<MessageId> writeAsync(String key, PulsarEvent event) {
            TypedMessageBuilder<PulsarEvent> builder = producer.newMessage().key(key).value(event);
            setReplicateCluster(event, builder);
            return builder.sendAsync();
        }

        @Override
        public MessageId delete(String key, PulsarEvent event) throws PulsarClientException {
            TypedMessageBuilder<PulsarEvent> builder = producer.newMessage().key(key).value(null);
            setReplicateCluster(event, builder);
            return builder.send();
        }

        @Override
        public CompletableFuture<MessageId> deleteAsync(String key, PulsarEvent event) {
            validateActionType(event);
            TypedMessageBuilder<PulsarEvent> builder = producer.newMessage().key(key).value(null);
            setReplicateCluster(event, builder);
            return builder.sendAsync();
        }



        @Override
        public void close() throws IOException {
            try {
                closeAsync().get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else {
                    throw new PulsarServerException(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            return producer.closeAsync().whenComplete((r, ex) -> {
                systemTopicClient.getWriters().remove(TopicPolicyWriter.this);
            });
        }

        @Override
        public SystemTopicClient<PulsarEvent> getSystemTopicClient() {
            return systemTopicClient;
        }
    }

    private static void setReplicateCluster(PulsarEvent event, TypedMessageBuilder<PulsarEvent> builder) {
        if (event.getReplicateTo() != null) {
            builder.replicationClusters(new ArrayList<>(event.getReplicateTo()));
        }
    }

    private static void validateActionType(PulsarEvent event) {
        if (event == null || !ActionType.DELETE.equals(event.getActionType())) {
            throw new UnsupportedOperationException("The only supported ActionType is DELETE");
        }
    }

    private static class TopicPolicyReader implements Reader<PulsarEvent> {

        private final org.apache.pulsar.client.api.Reader<PulsarEvent> reader;
        private final TopicPoliciesSystemTopicClient systemTopic;

        private TopicPolicyReader(org.apache.pulsar.client.api.Reader<PulsarEvent> reader,
                                  TopicPoliciesSystemTopicClient systemTopic) {
            this.reader = reader;
            this.systemTopic = systemTopic;
        }

        @Override
        public Message<PulsarEvent> readNext() throws PulsarClientException {
            return reader.readNext();
        }

        @Override
        public CompletableFuture<Message<PulsarEvent>> readNextAsync() {
            return reader.readNextAsync();
        }

        @Override
        public boolean hasMoreEvents() throws PulsarClientException {
            return reader.hasMessageAvailable();
        }

        @Override
        public CompletableFuture<Boolean> hasMoreEventsAsync() {
            return reader.hasMessageAvailableAsync();
        }

        @Override
        public void close() throws IOException {
            try {
                closeAsync().get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else {
                    throw new PulsarServerException(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            return reader.closeAsync().whenComplete((r, ex) -> {
                systemTopic.getReaders().remove(TopicPolicyReader.this);
            });
        }

        @Override
        public SystemTopicClient<PulsarEvent> getSystemTopic() {
            return systemTopic;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TopicPoliciesSystemTopicClient.class);
}
