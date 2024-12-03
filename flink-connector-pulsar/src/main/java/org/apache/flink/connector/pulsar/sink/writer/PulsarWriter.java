/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.sink.writer;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.operators.ProcessingTimeService;
import org.apache.flink.api.common.serialization.SerializationSchema.InitializationContext;
import org.apache.flink.api.connector.sink2.Sink.InitContext;
import org.apache.flink.api.connector.sink2.TwoPhaseCommittingSink.PrecommittingSinkWriter;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.pulsar.common.crypto.PulsarCrypto;
import org.apache.flink.connector.pulsar.sink.committer.PulsarCommittable;
import org.apache.flink.connector.pulsar.sink.config.SinkConfiguration;
import org.apache.flink.connector.pulsar.sink.writer.context.PulsarSinkContext;
import org.apache.flink.connector.pulsar.sink.writer.context.PulsarSinkContextImpl;
import org.apache.flink.connector.pulsar.sink.writer.delayer.MessageDelayer;
import org.apache.flink.connector.pulsar.sink.writer.message.PulsarMessage;
import org.apache.flink.connector.pulsar.sink.writer.router.TopicRouter;
import org.apache.flink.connector.pulsar.sink.writer.serializer.PulsarSerializationSchema;
import org.apache.flink.connector.pulsar.sink.writer.topic.MetadataListener;
import org.apache.flink.connector.pulsar.sink.writer.topic.ProducerRegister;
import org.apache.flink.connector.pulsar.source.enumerator.topic.TopicPartition;
import org.apache.flink.metrics.groups.SinkWriterMetricGroup;
import org.apache.flink.util.FlinkRuntimeException;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.type.TypeReference;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import com.tencent.bk.base.dataflow.flink.streaming.checkpoint.AbstractFlinkStreamingCheckpointManager;
import com.tencent.bk.base.dataflow.flink.streaming.checkpoint.CheckpointValue.OutputCheckpoint;
import com.tencent.bk.base.dataflow.flink.streaming.checkpoint.types.AbstractCheckpointKey;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.client.impl.TypedMessageBuilderImpl;
import org.apache.pulsar.shade.com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.emptyList;
import static org.apache.flink.util.IOUtils.closeAll;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * This class is responsible to write records in a Pulsar topic and to handle the different delivery
 * {@link DeliveryGuarantee}s.
 *
 * @param <IN> The type of the input elements.
 */
@Internal
public class PulsarWriter<IN> implements PrecommittingSinkWriter<IN, PulsarCommittable> {
    private static final Logger LOG = LoggerFactory.getLogger(PulsarWriter.class);

    private static final long DEFAULT_CHECK_INTERVAL_MS = 1000L;

    private final PulsarSerializationSchema<IN> serializationSchema;
    private final MetadataListener metadataListener;
    private final TopicRouter<IN> topicRouter;
    private final MessageDelayer<IN> messageDelayer;
    private final DeliveryGuarantee deliveryGuarantee;
    private final PulsarSinkContext sinkContext;
    private final ProducerRegister producerRegister;
    private final MailboxExecutor mailboxExecutor;
    private final AtomicLong pendingMessages;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AbstractFlinkStreamingCheckpointManager checkpointManager;
    private final Boolean isOffset;
    private Map<AbstractCheckpointKey, OutputCheckpoint> windowSinkingCheckpoint;
    private long lastSendTime;
    private final ProcessingTimeService processingTimeService;

    /**
     * Constructor creating a Pulsar writer.
     *
     * <p>It will throw a {@link RuntimeException} if {@link
     * PulsarSerializationSchema#open(InitializationContext, PulsarSinkContext, SinkConfiguration)}
     * fails.
     *
     * @param sinkConfiguration The configuration to configure the Pulsar producer.
     * @param serializationSchema Transform the incoming records into different message properties.
     * @param metadataListener The listener for querying topic metadata.
     * @param topicRouter Topic router to choose the topic by incoming records.
     * @param pulsarCrypto Used for end-to-end encryption.
     * @param initContext Context to provide information about the runtime environment.
     */
    public PulsarWriter(
            SinkConfiguration sinkConfiguration,
            PulsarSerializationSchema<IN> serializationSchema,
            MetadataListener metadataListener,
            TopicRouter<IN> topicRouter,
            MessageDelayer<IN> messageDelayer,
            PulsarCrypto pulsarCrypto,
            InitContext initContext,
            AbstractFlinkStreamingCheckpointManager checkpointManager,
            Boolean isOffset)
            throws PulsarClientException {
        checkNotNull(sinkConfiguration);
        this.serializationSchema = checkNotNull(serializationSchema);
        this.metadataListener = checkNotNull(metadataListener);
        this.topicRouter = checkNotNull(topicRouter);
        this.messageDelayer = checkNotNull(messageDelayer);
        this.checkpointManager = checkNotNull(checkpointManager);
        this.isOffset = isOffset;
        checkNotNull(initContext);

        this.deliveryGuarantee = sinkConfiguration.getDeliveryGuarantee();
        this.sinkContext =
                new PulsarSinkContextImpl(initContext, sinkConfiguration, metadataListener);

        // Initialize topic metadata listener.
        LOG.debug("Initialize topic metadata after creating Pulsar writer.");
        ProcessingTimeService timeService = initContext.getProcessingTimeService();
        this.metadataListener.open(sinkConfiguration, timeService);
        this.processingTimeService = timeService;

        // Initialize topic router.
        this.topicRouter.open(sinkConfiguration);

        // Initialize the serialization schema.
        try {
            InitializationContext initializationContext =
                    initContext.asSerializationSchemaInitializationContext();
            this.serializationSchema.open(initializationContext, sinkContext, sinkConfiguration);
        } catch (Exception e) {
            throw new FlinkRuntimeException("Cannot initialize schema.", e);
        }

        // Create this producer register after opening serialization schema!
        SinkWriterMetricGroup metricGroup = initContext.metricGroup();
        this.producerRegister = new ProducerRegister(sinkConfiguration, pulsarCrypto, metricGroup);
        this.mailboxExecutor = initContext.getMailboxExecutor();
        this.pendingMessages = new AtomicLong(0);

        // Init checkpoint manager
        this.checkpointManager.open();

        // When using at least once delivery, it's necessary to register a timer to save the
        // timestamp checkpoint
        if (deliveryGuarantee == DeliveryGuarantee.AT_LEAST_ONCE && !isOffset) {
            long currentProcessingTime = processingTimeService.getCurrentProcessingTime();
            long tiggerTime = currentProcessingTime + DEFAULT_CHECK_INTERVAL_MS;
            processingTimeService.registerTimer(tiggerTime, time -> saveCheckpointForWindowEnd());
            lastSendTime = this.processingTimeService.getCurrentProcessingTime();
        }
    }

    @Override
    public void write(IN element, Context context) throws IOException, InterruptedException {
        PulsarMessage<?> message = serializationSchema.serialize(element, sinkContext);

        // Choose the right topic to send.
        String key = message.getKey();
        List<TopicPartition> partitions = metadataListener.availablePartitions();
        TopicPartition partition = topicRouter.route(element, key, partitions, sinkContext);
        String topic = partition.getFullTopicName();

        // Create message builder for sending messages.
        TypedMessageBuilder<?> builder = createMessageBuilder(topic, context, message);

        if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE) {
            String checkpointInfoStr = message.getProperties().get("checkpointInfo");
            HashMap<String, String> tmpCheckpointInfo =
                    objectMapper.readValue(
                            checkpointInfoStr, new TypeReference<HashMap<String, String>>() {});
            Map<AbstractCheckpointKey, OutputCheckpoint> checkpointInfo = new HashMap<>();
            tmpCheckpointInfo.forEach(
                    (keyStr, valueStr) -> {
                        AbstractCheckpointKey checkpointKey =
                                AbstractCheckpointKey.fromString(keyStr);
                        OutputCheckpoint outputCheckpoint =
                                OutputCheckpoint.buildFromDbStr(valueStr);
                        checkpointInfo.put(checkpointKey, outputCheckpoint);
                    });
            producerRegister.updateCheckpointInfo(checkpointInfo);
        }

        // Message Delay delivery.
        long deliverAt = messageDelayer.deliverAt(element, sinkContext);
        if (deliverAt > 0) {
            builder.deliverAt(deliverAt);
        }

        // Perform message sending.
        if (deliveryGuarantee == DeliveryGuarantee.NONE) {
            // We would just ignore the sending exception. This may cause data loss.
            builder.sendAsync();
        } else {
            // Increase the pending message count.
            pendingMessages.incrementAndGet();
            CompletableFuture<MessageId> future = builder.sendAsync();
            future.whenComplete(
                    (id, ex) -> {
                        pendingMessages.decrementAndGet();
                        if (ex != null) {
                            mailboxExecutor.execute(
                                    () -> throwSendingException(topic, ex),
                                    "Failed to send data to Pulsar");
                        } else {
                            LOG.debug("Sent message to Pulsar {} with message id {}", topic, id);
                        }
                    });
        }

        // At least once, save checkpoint to redis
        if (deliveryGuarantee == DeliveryGuarantee.AT_LEAST_ONCE) {
            // Get checkpoint info
            String checkpointInfoStr = message.getProperties().get("checkpointInfo");
            HashMap<String, String> tmpCheckpointInfo =
                    objectMapper.readValue(
                            checkpointInfoStr, new TypeReference<HashMap<String, String>>() {});
            Map<AbstractCheckpointKey, OutputCheckpoint> checkpointInfo = new HashMap<>();
            tmpCheckpointInfo.forEach(
                    (keyStr, valueStr) -> {
                        AbstractCheckpointKey checkpointKey =
                                AbstractCheckpointKey.fromString(keyStr);
                        OutputCheckpoint outputCheckpoint =
                                OutputCheckpoint.buildFromDbStr(valueStr);
                        checkpointInfo.put(checkpointKey, outputCheckpoint);
                    });
            checkpointInfo.forEach(
                    (checkpointKey, checkpointValue) -> {
                        if (!this.isOffset) {
                            checkpointValue = checkpointValue.subtraction(1);
                            if (null == windowSinkingCheckpoint) {
                                windowSinkingCheckpoint = new ConcurrentHashMap<>();
                            }
                            windowSinkingCheckpoint.put(checkpointKey, checkpointValue);
                        }
                        this.checkpointManager.savePoint(checkpointKey, checkpointValue);
                    });
            lastSendTime = this.processingTimeService.getCurrentProcessingTime();
        }
    }

    /**
     * When using at least once, determine the completion of writing output data by checking if no
     * data has been written for one second.
     */
    private void saveCheckpointForWindowEnd() {
        long currentProcessingTime = this.processingTimeService.getCurrentProcessingTime();
        boolean isTime = lastSendTime < currentProcessingTime - DEFAULT_CHECK_INTERVAL_MS;
        if (isTime && null != windowSinkingCheckpoint) {
            windowSinkingCheckpoint.forEach(
                    (checkpointKey, checkpointValue) -> {
                        checkpointManager.savePoint(checkpointKey, checkpointValue.subtraction(-1));
                    });
            windowSinkingCheckpoint.clear();
        }
        long triggerTime =
                this.processingTimeService.getCurrentProcessingTime() + DEFAULT_CHECK_INTERVAL_MS;
        processingTimeService.registerTimer(triggerTime, time -> saveCheckpointForWindowEnd());
    }

    private void throwSendingException(String topic, Throwable ex) {
        throw new FlinkRuntimeException("Failed to send data to Pulsar: " + topic, ex);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TypedMessageBuilder<?> createMessageBuilder(
            String topic, Context context, PulsarMessage<?> message) throws PulsarClientException {

        Schema<?> schema = message.getSchema();
        TypedMessageBuilder<?> builder = producerRegister.createMessageBuilder(topic, schema);

        byte[] orderingKey = message.getOrderingKey();
        if (orderingKey != null && orderingKey.length > 0) {
            builder.orderingKey(orderingKey);
        }

        String key = message.getKey();
        if (!Strings.isNullOrEmpty(key)) {
            builder.key(key);
        }

        if (message.isBase64EncodedKey()) {
            // HACK - otherwise we should hold both keys and keyBytes fields which
            // is more confusing.
            ((TypedMessageBuilderImpl<?>) builder)
                    .getMetadataBuilder()
                    .setPartitionKeyB64Encoded(true);
        }

        long eventTime = message.getEventTime();
        if (eventTime > 0) {
            builder.eventTime(eventTime);
        } else {
            // Set default message timestamp if flink has provided one.
            Long timestamp = context.timestamp();
            if (timestamp != null && timestamp > 0L) {
                builder.eventTime(timestamp);
            }
        }

        // Schema evolution would serialize the message by Pulsar Schema in TypedMessageBuilder.
        // The type has been checked in PulsarMessageBuilder#value.
        Object value = message.getValue();
        if (value == null) {
            LOG.warn("Send a message with empty payloads, this is a tombstone message in Pulsar.");
        }
        ((TypedMessageBuilder) builder).value(value);

        Map<String, String> properties = message.getProperties();
        if (properties != null && !properties.isEmpty()) {
            builder.properties(properties);
        }

        Long sequenceId = message.getSequenceId();
        if (sequenceId != null) {
            builder.sequenceId(sequenceId);
        }

        List<String> clusters = message.getReplicationClusters();
        if (clusters != null && !clusters.isEmpty()) {
            builder.replicationClusters(clusters);
        }

        if (message.isDisableReplication()) {
            builder.disableReplication();
        }

        return builder;
    }

    @Override
    public void flush(boolean endOfInput) throws IOException {
        if (endOfInput || deliveryGuarantee != DeliveryGuarantee.NONE) {
            LOG.info("Flush the pending messages to Pulsar.");

            // Try to flush pending messages.
            producerRegister.flush();
            // Make sure all the pending messages should be flushed to Pulsar.
            while (pendingMessages.longValue() > 0) {
                producerRegister.flush();
            }
        }
    }

    @Override
    public Collection<PulsarCommittable> prepareCommit() {
        if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE) {
            return producerRegister.prepareCommit();
        } else {
            return emptyList();
        }
    }

    @Override
    public void close() throws Exception {
        // Close all the resources and throw the exception at last.
        closeAll(metadataListener, producerRegister);
    }
}
