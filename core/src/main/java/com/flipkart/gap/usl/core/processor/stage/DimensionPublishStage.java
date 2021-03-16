package com.flipkart.gap.usl.core.processor.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.gap.usl.core.config.v2.ApplicationConfiguration;
import com.flipkart.gap.usl.core.helper.ObjectMapperFactory;
import com.flipkart.gap.usl.core.model.DimensionMutateRequest;
import com.flipkart.gap.usl.core.model.dimension.Dimension;
import com.flipkart.gap.usl.core.processor.stage.model.ProcessingStageData;
import com.flipkart.gap.usl.core.store.dimension.kafka.KafkaPublisherDao;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
@Slf4j
public class DimensionPublishStage extends ProcessingStage {

    @Inject
    private KafkaPublisherDao kafkaPublisherDao;

    @Inject
    ApplicationConfiguration configuration;

    @Override
    protected void process(ProcessingStageData processingStageData) throws StageProcessingException {
        try {

            final Set<Dimension> dimensionSet = processingStageData.getDimensionMutateRequests().stream().map(DimensionMutateRequest::getDimension).collect(Collectors.toSet());

            List<ProducerRecord<String,byte[]>> producerRecordList = new ArrayList();
            Iterator<Dimension> dimensionIterator = dimensionSet.iterator();
            while (dimensionIterator.hasNext()) {
                Dimension dimension = dimensionIterator.next();
                if (configuration.getDimensionsToBePublished() != null && configuration.getDimensionsToBePublished().contains(dimension.getDimensionSpecs().name()))
                    producerRecordList.add(createProducerRecord(dimension));
            }
            kafkaPublisherDao.sendEventsSync(producerRecordList);

        } catch (Exception e) {
            throw new StageProcessingException(e);
        }
    }

    private ProducerRecord<String,byte[]> createProducerRecord(Dimension dimension) throws JsonProcessingException {

        String dimensionName = dimension.getDimensionSpecs().name();
        String partitionKey = dimensionName + "##" + dimension.getEntityId();
        byte[] value = ObjectMapperFactory.getMapper().writeValueAsBytes(dimension);
        return new ProducerRecord<>(dimensionName, partitionKey, value);
    }


}
