package com.flipkart.gap.usl.core.store.dimension.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.gap.usl.core.config.EventProcessorConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.CompressionType;

import java.util.Properties;

@Singleton
@Slf4j
public class ExternalKafkaPublisherDAOImpl extends KafkaPublisherDao{

    @Inject
    @Named("externalKafkaConfig")
    private EventProcessorConfig externalKafkaConfig;

    @Inject
    public void init() {
        Properties props = new Properties();

        props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, externalKafkaConfig.getKafkaBrokerConnection());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        props.put(org.apache.kafka.clients.producer.ProducerConfig.RETRIES_CONFIG, externalKafkaConfig.getRetry());
//        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, externalKafkaConfig.getRetryBackoffMs());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.BATCH_SIZE_CONFIG, externalKafkaConfig.getBatchSize());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.LINGER_MS_CONFIG, externalKafkaConfig.getLingerTimeInMs());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, externalKafkaConfig.getRequestTimeout());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, externalKafkaConfig.getMaxBlockMS());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, externalKafkaConfig.getMaxIdleTime());
//        props.put(org.apache.kafka.clients.producer.ProducerConfig.BUFFER_MEMORY_CONFIG, maxBytesInBuffer / externalKafkaConfig.getProducersCount());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put(org.apache.kafka.clients.producer.ProducerConfig.COMPRESSION_TYPE_CONFIG, CompressionType.GZIP.name);


        try {
            producer = new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(props);
        } catch (Exception e) {
            log.error("Exception making producer: {}", ExceptionUtils.getMessage(e), e);
        }
    }


}
