package com.flipkart.gap.usl.core.store.dimension.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.gap.usl.core.config.EventProcessorConfig;
import com.flipkart.gap.usl.core.helper.ObjectMapperFactory;
import com.flipkart.gap.usl.core.model.dimension.Dimension;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.Getter;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

@Singleton
public class KafkaPublisherDAO {

    private final Integer KAFKA_MESSAGE_BATCH_SIZE_MAX = 100;
    private final Integer KAFKA_LINGER_SIZE_MS = 10;

    @Inject
    @Named("eventProcessorConfig")
    private EventProcessorConfig eventProcessorConfig;
    @Getter
    private ProducerConfig producerConfig;
    private Producer<String, byte[]> producer;

    @Inject
    public void init(ProducerConfig producerConfig) {
        this.producerConfig = producerConfig;
        Properties props = new Properties();
        props.put("bootstrap.servers", eventProcessorConfig.getKafkaBrokerConnection());
        props.put("key.deserializer", ByteArrayDeserializer.class);
        props.put("value.deserializer", ByteArrayDeserializer.class);
        props.put("group.id", eventProcessorConfig.getKafkaConfig().getGroupId());
        props.put("auto.offset.reset", eventProcessorConfig.getKafkaConfig().getAutoOffsetReset());
        props.put("enable.auto.commit", eventProcessorConfig.getKafkaConfig().isEnableAutoCommit());
        props.put("fetch.max.wait.ms", eventProcessorConfig.getKafkaConfig().getFetchMaxWait());
        props.put("fetch.min.bytes", eventProcessorConfig.getKafkaConfig().getFetchMinBytes());
        props.put("heartbeat.interval.ms", eventProcessorConfig.getKafkaConfig().getHeartBeatIntervalMS());
        props.put("session.timeout.ms", eventProcessorConfig.getKafkaConfig().getSessionTimeoutMS());
        props.put("request.timeout.ms", eventProcessorConfig.getKafkaConfig().getRequestTimeoutMS());
        props.put("batch.size", KAFKA_MESSAGE_BATCH_SIZE_MAX);
        props.put("linger.ms", KAFKA_LINGER_SIZE_MS);

        producer = new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(props);
    }


    private void sendEvent(Dimension dimension) throws Exception {
        producer.send(createProducerRecord(dimension)).get();
    }

    private ProducerRecord<String,byte[]> createProducerRecord(Dimension dimension) throws JsonProcessingException {

        return new ProducerRecord<>(dimension.getDimensionSpecs().name(),
                ObjectMapperFactory.getMapper().writeValueAsBytes(dimension)
        );
    }

    public void bulkPublish(Set<Dimension> dimensions) throws Exception {
        
        Iterator<Dimension> dimensionIterator = dimensions.iterator();
        while (dimensionIterator.hasNext()) {
            sendEvent(dimensionIterator.next());
        }

    }


}
