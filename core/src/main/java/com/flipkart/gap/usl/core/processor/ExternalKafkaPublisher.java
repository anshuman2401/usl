package com.flipkart.gap.usl.core.processor;

import com.flipkart.gap.usl.core.client.KafkaClient;
import com.flipkart.gap.usl.core.client.OffsetManager;
import com.flipkart.gap.usl.core.config.EventProcessorConfig;
import com.flipkart.gap.usl.core.config.ExternalKafkaConfigurationModule;
import com.flipkart.gap.usl.core.config.v2.ExternalKafkaApplicationConfiguration;
import com.flipkart.gap.usl.core.constant.Constants;
import com.flipkart.gap.usl.core.helper.SparkHelper;
import com.flipkart.gap.usl.core.processor.exception.ProcessingException;
import com.flipkart.gap.usl.core.processor.stage.model.KafkaProducerRecord;
import com.flipkart.gap.usl.core.store.dimension.kafka.KafkaPublisherDao;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.HasOffsetRanges;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.apache.spark.streaming.kafka010.OffsetRange;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by amarjeet.singh on 18/10/16.
 */
@Slf4j
@Singleton
public class ExternalKafkaPublisher implements Serializable {

    @Inject
    private ExternalKafkaApplicationConfiguration applicationConfiguration;
    @Inject
    private transient OffsetManager offsetManager;
    @Inject
    private transient KafkaClient kafkaClient;
    @Inject
    @Named("externalKafkaConfig")
    private EventProcessorConfig externalKafkaConfig;

    @Inject
    @Named("eventProcessorConfig")
    private EventProcessorConfig internalKafkaConfig;

    private transient SparkConf sparkConf;
    private transient HashMap<String, Object> kafkaParams;
    private transient JavaStreamingContext javaStreamingContext;

    @Inject
    public void init() {
        log.info("Initialising configs");
        EventProcessorConfig eventProcessorConfig = applicationConfiguration.getEventProcessorConfig();
        sparkConf = new SparkConf().setMaster(eventProcessorConfig.getSparkMasterWithPort()).setAppName(Constants.Stream.GROUP_ID);
        sparkConf.set("spark.streaming.backpressure.initialRate", eventProcessorConfig.getBackPressureInitialRate());
        sparkConf.set("spark.dynamicAllocation.enabled", "false");
        sparkConf.set("spark.streaming.receiver.maxRate", eventProcessorConfig.getBatchSize() + "");
        sparkConf.set("spark.streaming.stopGracefullyOnShutdown", "true");
        sparkConf.set("spark.executor.extraJavaOptions", eventProcessorConfig.getExecutorExtraJavaOpts());
        sparkConf.set("spark.executor.cores", eventProcessorConfig.getExecutorCores() + "");
        sparkConf.set("spark.executor.memory", eventProcessorConfig.getExecutorMemory());
        sparkConf.set("spark.job.interruptOnCancel", "true");
        int maxRate = eventProcessorConfig.getBatchSize();
        log.info("fetching partition count configs");
        int partitionCount = kafkaClient.getPartitionCount(internalKafkaConfig.getTopicNames());
        log.info("fetched {} partition count configs",partitionCount);
        int maxRatePerPartition = maxRate / (partitionCount * eventProcessorConfig.getBatchDurationInSeconds());
        sparkConf.set("spark.streaming.kafka.maxRatePerPartition", maxRatePerPartition + "");
        log.info("Using spark config {}", sparkConf);
        kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", eventProcessorConfig.getKafkaBrokerConnection());
        kafkaParams.put("key.deserializer", ByteArrayDeserializer.class);
        kafkaParams.put("value.deserializer", ByteArrayDeserializer.class);
        kafkaParams.put("group.id", eventProcessorConfig.getKafkaConfig().getGroupId());
        kafkaParams.put("auto.offset.reset", eventProcessorConfig.getKafkaConfig().getAutoOffsetReset());
        kafkaParams.put("enable.auto.commit", eventProcessorConfig.getKafkaConfig().isEnableAutoCommit());
        kafkaParams.put("fetch.max.wait.ms", eventProcessorConfig.getKafkaConfig().getFetchMaxWait());
        kafkaParams.put("fetch.min.bytes", eventProcessorConfig.getKafkaConfig().getFetchMinBytes());
        kafkaParams.put("heartbeat.interval.ms", eventProcessorConfig.getKafkaConfig().getHeartBeatIntervalMS());
        kafkaParams.put("session.timeout.ms", eventProcessorConfig.getKafkaConfig().getSessionTimeoutMS());
        kafkaParams.put("request.timeout.ms", eventProcessorConfig.getKafkaConfig().getRequestTimeoutMS());
        log.info("Using kafka params config {}", sparkConf);
    }

    public void process() throws ProcessingException {
        try {
            javaStreamingContext = getStreamingContext(applicationConfiguration.getEventProcessorConfig());
            javaStreamingContext.start();
            javaStreamingContext.awaitTermination();
        } catch (Throwable e) {
            throw new ProcessingException(e);
        }
    }

    private JavaStreamingContext getStreamingContext(EventProcessorConfig eventProcessorConfig) throws Exception {

        JavaStreamingContext javaStreamingContext = new JavaStreamingContext(sparkConf, Durations.seconds(eventProcessorConfig.getBatchDurationInSeconds()));

        Map<TopicPartition, Long> topicPartitionMap = offsetManager.getMultipleTopicPartitions();
        log.info("Fetched topic and partition map as {}", topicPartitionMap);
        List<TopicPartition> topicPartitionList = new ArrayList<>(topicPartitionMap.keySet());
        JavaInputDStream<ConsumerRecord<byte[], byte[]>> messages = KafkaUtils.createDirectStream(
                javaStreamingContext,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.Assign(topicPartitionList, kafkaParams, topicPartitionMap)
        );

        /*
          This is to track offset ranges to persist it later in zookeeper.
         */

        final AtomicReference<OffsetRange[]> offsetRanges = new AtomicReference<>();
        /*

          Store offsets right after getting kafka stream.
          Transform input events to dimension update events, create a tuple of composite key (entityId, dimensionName) and dimensionUpdate Event.

         */
        log.info("Starting transform to save offsets ");


        messages.foreachRDD((VoidFunction2<JavaRDD<ConsumerRecord<byte[], byte[]>>, Time>) (consumerRecordJavaRDD, v2) -> {
            OffsetRange[] offsets = ((HasOffsetRanges) consumerRecordJavaRDD.rdd()).offsetRanges();
            offsetRanges.set(offsets);
            for (OffsetRange offsetRange : offsets) {
                log.info("Started Batch processing with offsets {},{},{},{}", offsetRange.topic(), offsetRange.partition(), offsetRange.fromOffset(), offsetRange.untilOffset());
            }
        });

        messages.foreachRDD(
                (VoidFunction2<JavaRDD<ConsumerRecord<byte[], byte[]>>, Time>) (consumerRecordJavaRDD, time) -> {

                    JavaRDD<KafkaProducerRecord> publishedRDD = consumerRecordJavaRDD.map(consumerRecord -> {
                        SparkHelper.bootstrap();
                        return new KafkaProducerRecord(externalKafkaConfig.getTopicName(), consumerRecord.value());

                    });

                    try {
                        KafkaPublisherDao kafkaPublisherDao = ExternalKafkaConfigurationModule.getInjector(applicationConfiguration).getInstance(KafkaPublisherDao.class);
                        kafkaPublisherDao.sendRecords(publishedRDD.collect());
                        publishedRDD.unpersist();
                    } catch (Throwable throwable) {
                        log.error("Exception occurred during count ", throwable);
                        javaStreamingContext.stop(true, false);
                        System.exit(0);
                    }
                });

        /*
         * This will group all the input events on entityId,dimensionName combination.
         */
        messages.foreachRDD((VoidFunction2<JavaRDD<ConsumerRecord<byte[], byte[]>>, Time>) (v1, v2) -> {
            try {
                offsetManager.saveOffset(offsetRanges.get());
            } catch (Throwable throwable) {
                log.error("Exception occurred during offset save", throwable);
                javaStreamingContext.stop(true, false);
                System.exit(0);
            }
        });

        return javaStreamingContext;
    }
}
