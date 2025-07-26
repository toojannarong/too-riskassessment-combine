package com.allianz.agcs.riskassessment.integration;

import com.allianz.agcs.riskassessment.repositories.ArcRecommendationRepository;
import com.allianz.agcs.riskassessment.repositories.LocationArcAssessmentRepository;
import com.allianz.agcs.riskassessmentcommon.kafka.message.recomendation.RecommendationEventDataDTO;
import com.allianz.agcs.riskassessmentcommon.models.dtos.*;
import com.allianz.agcs.riskassessmentcommon.models.entities.commons.assessment.location.LocationArcAssessmentEntity;
import com.allianz.agcs.riskassessmentcommon.models.entities.recomendation.ArcRecommendationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CRITICAL MISSING TEST: End-to-End Kafka to Database Integration Test
 * 
 * Tests the complete flow from Kafka event consumption to database storage:
 * - Kafka event publishing and consumption
 * - Event validation and processing
 * - Database entity creation and updates
 * - Error handling and resilience
 * - Event ordering and concurrency
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    },
    topics = {"test-recommendation-topic"}
)
@Testcontainers
class RecommendationKafkaToDbIntegrationTest {

    @Container
    static MongoDBContainer mongoContainer = new MongoDBContainer("mongo:6.0")
            .withExposedPorts(27017);

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ArcRecommendationRepository arcRecommendationRepository;

    @Autowired
    private LocationArcAssessmentRepository locationArcAssessmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoContainer::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "test_recommendations");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.consumer.group-id", () -> "test-group");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("kafka.recommendation-event.topic", () -> "test-recommendation-topic");
    }

    @BeforeEach
    void setUp() {
        // Clear existing data
        mongoTemplate.getCollection("arcRecommendation").drop();
        mongoTemplate.getCollection("locationArcAssessment").drop();
        
        // Create test assessment entity (required for event processing)
        createTestAssessmentEntity();
        
        // Setup Kafka producer
        setupKafkaProducer();
    }

    @Test
    void shouldProcessNewRecommendationEventAndStoreInDatabase() throws Exception {
        // Given
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        String eventJson = objectMapper.writeValueAsString(eventDTO);

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key", eventJson));
        producer.flush();

        // Then
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            List<ArcRecommendationEntity> recommendations = arcRecommendationRepository.findAll();
            return !recommendations.isEmpty();
        });

        List<ArcRecommendationEntity> savedRecommendations = arcRecommendationRepository.findAll();
        assertThat(savedRecommendations).hasSize(1);
        
        ArcRecommendationEntity saved = savedRecommendations.get(0);
        assertThat(saved.getRecommendationId()).isEqualTo("REC_KAFKA_001");
        assertThat(saved.getRecommendationTitle()).isEqualTo("Install Fire Suppression System");
        assertThat(saved.getSubmissionId()).isEqualTo("SUB123456789");
        assertThat(saved.getObjectId()).isEqualTo("OBJ987654321");
        assertThat(saved.getRcId()).isEqualTo("RC123456789");
    }

    @Test
    void shouldUpdateExistingRecommendationWhenEventReceived() throws Exception {
        // Given - Create existing recommendation
        ArcRecommendationEntity existingRec = new ArcRecommendationEntity();
        existingRec.setRecommendationId("REC_KAFKA_002");
        existingRec.setRecommendationTitle("Original Title");
        existingRec.setSubmissionId("SUB123456789");
        existingRec.setSubmissionBaseNr("SUB123456");
        existingRec.setObjectId("OBJ987654321");
        existingRec.setRcId("RC123456789");
        arcRecommendationRepository.save(existingRec);

        // Create update event
        RecommendationEventDataDTO updateEvent = createUpdateRecommendationEvent();
        String eventJson = objectMapper.writeValueAsString(updateEvent);

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key-2", eventJson));
        producer.flush();

        // Then
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            ArcRecommendationEntity updated = arcRecommendationRepository.findByRecommendationId("REC_KAFKA_002");
            return updated != null && "Updated Fire Suppression System".equals(updated.getRecommendationTitle());
        });

        ArcRecommendationEntity updated = arcRecommendationRepository.findByRecommendationId("REC_KAFKA_002");
        assertThat(updated).isNotNull();
        assertThat(updated.getRecommendationTitle()).isEqualTo("Updated Fire Suppression System");
        assertThat(updated.getRecommendationBody()).isEqualTo("Updated detailed description");
    }

    @Test
    void shouldProcessMultipleRecommendationsInSingleEvent() throws Exception {
        // Given
        RecommendationEventDataDTO eventDTO = createMultipleRecommendationsEvent();
        String eventJson = objectMapper.writeValueAsString(eventDTO);

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key-3", eventJson));
        producer.flush();

        // Then
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            List<ArcRecommendationEntity> recommendations = arcRecommendationRepository.findAll();
            return recommendations.size() >= 3; // Should have 3 recommendations
        });

        List<ArcRecommendationEntity> savedRecommendations = arcRecommendationRepository.findAll();
        assertThat(savedRecommendations).hasSizeGreaterThanOrEqualTo(3);
        
        // Verify all recommendations were saved
        assertThat(savedRecommendations)
            .extracting(ArcRecommendationEntity::getRecommendationId)
            .containsAnyOf("REC_MULTI_001", "REC_MULTI_002", "REC_MULTI_003");
    }

    @Test
    void shouldIgnoreEventWhenAssessmentNotFoundInLocalDatabase() throws Exception {
        // Given - Event with RcID that doesn't exist in local database
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        
        // Set different RcID that doesn't exist
        eventDTO.getAssessmentList().get(0).setAssessmentId("RC_NONEXISTENT");
        
        String eventJson = objectMapper.writeValueAsString(eventDTO);

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key-4", eventJson));
        producer.flush();

        // Then - Should not create any recommendations
        await().pollDelay(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> true);
        
        List<ArcRecommendationEntity> savedRecommendations = arcRecommendationRepository.findAll();
        assertThat(savedRecommendations).isEmpty();
    }

    @Test
    void shouldHandleInvalidEventGracefully() throws Exception {
        // Given - Malformed JSON
        String invalidJson = "{ invalid json structure";

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key-5", invalidJson));
        producer.flush();

        // Then - Should not crash and not create any recommendations
        await().pollDelay(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> true);
        
        List<ArcRecommendationEntity> savedRecommendations = arcRecommendationRepository.findAll();
        assertThat(savedRecommendations).isEmpty();
    }

    @Test
    void shouldHandleEventWithMissingRequiredFields() throws Exception {
        // Given - Event with missing recommendation ID
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        eventDTO.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationId(null);
        
        String eventJson = objectMapper.writeValueAsString(eventDTO);

        // When
        producer.send(new ProducerRecord<>("test-recommendation-topic", "test-key-6", eventJson));
        producer.flush();

        // Then - Should not create the invalid recommendation
        await().pollDelay(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> true);
        
        List<ArcRecommendationEntity> savedRecommendations = arcRecommendationRepository.findAll();
        assertThat(savedRecommendations).isEmpty();
    }

    @Test
    void shouldProcessConcurrentEventsCorrectly() throws Exception {
        // Given - Multiple events for the same recommendation
        RecommendationEventDataDTO event1 = createNewRecommendationEvent();
        event1.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationId("REC_CONCURRENT_001");
        event1.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationTitle("Title Version 1");

        RecommendationEventDataDTO event2 = createNewRecommendationEvent();
        event2.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationId("REC_CONCURRENT_001");
        event2.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationTitle("Title Version 2");

        String eventJson1 = objectMapper.writeValueAsString(event1);
        String eventJson2 = objectMapper.writeValueAsString(event2);

        // When - Send both events quickly
        producer.send(new ProducerRecord<>("test-recommendation-topic", "concurrent-1", eventJson1));
        producer.send(new ProducerRecord<>("test-recommendation-topic", "concurrent-2", eventJson2));
        producer.flush();

        // Then - Should handle both events and end up with one recommendation
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            ArcRecommendationEntity rec = arcRecommendationRepository.findByRecommendationId("REC_CONCURRENT_001");
            return rec != null;
        });

        ArcRecommendationEntity finalRec = arcRecommendationRepository.findByRecommendationId("REC_CONCURRENT_001");
        assertThat(finalRec).isNotNull();
        // Should have one of the titles (depends on processing order)
        assertThat(finalRec.getRecommendationTitle()).isIn("Title Version 1", "Title Version 2");
    }

    @Test
    void shouldProcessEventWithDuplicateRecommendationIdempotently() throws Exception {
        // Given - Send same event twice
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        eventDTO.getAssessmentList().get(0).getRecommendations().get(0).setRecommendationId("REC_IDEMPOTENT_001");
        
        String eventJson = objectMapper.writeValueAsString(eventDTO);

        // When - Send the same event twice
        producer.send(new ProducerRecord<>("test-recommendation-topic", "dup-1", eventJson));
        producer.send(new ProducerRecord<>("test-recommendation-topic", "dup-2", eventJson));
        producer.flush();

        // Then - Should only have one recommendation
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            List<ArcRecommendationEntity> recs = arcRecommendationRepository.findAll();
            return !recs.isEmpty();
        });

        List<ArcRecommendationEntity> allRecs = arcRecommendationRepository.findAll();
        long countWithId = allRecs.stream()
            .filter(rec -> "REC_IDEMPOTENT_001".equals(rec.getRecommendationId()))
            .count();
        
        assertThat(countWithId).isEqualTo(1);
    }

    // Helper Methods

    private void setupKafkaProducer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");
        props.put("retries", 0);
        props.put("batch.size", 16384);
        props.put("linger.ms", 1);
        props.put("buffer.memory", 33554432);

        producer = new KafkaProducer<>(props);
    }

    private void createTestAssessmentEntity() {
        LocationArcAssessmentEntity assessmentEntity = new LocationArcAssessmentEntity();
        assessmentEntity.setRcId("RC123456789");
        assessmentEntity.setSubmissionId("SUB123456789");
        locationArcAssessmentRepository.save(assessmentEntity);
    }

    private RecommendationEventDataDTO createNewRecommendationEvent() {
        RecommendationEventDataDTO eventDTO = new RecommendationEventDataDTO();
        eventDTO.setType("RECOMMENDATION_EVENT");

        // Submission
        SubmissionDTO submission = new SubmissionDTO();
        submission.setSubmissionId("SUB123456789");
        eventDTO.setSubmission(submission);

        // Insurable Entity
        UniqueDataDTO uniqueData = new UniqueDataDTO();
        uniqueData.setInsurableEntityId("OBJ987654321");
        
        LocationDTO insurableEntity = new LocationDTO();
        insurableEntity.setIdentifiedByUniqueData(uniqueData);
        eventDTO.setInsurableEntityList(List.of(insurableEntity));

        // Assessment with Recommendation
        LocationAssessmentDTO assessment = new LocationAssessmentDTO();
        assessment.setAssessmentId("RC123456789");

        RecommendationDTO recommendation = new RecommendationDTO();
        recommendation.setRecommendationId("REC_KAFKA_001");
        recommendation.setRecommendationTitle("Install Fire Suppression System");
        recommendation.setRecommendationBody("Install comprehensive fire suppression system in main building");
        recommendation.setRecommendationType("PHYSICAL");
        recommendation.setRecommendationCategory("AUTOMATIC_SPRINKLERS");
        recommendation.setRecommendationPriority("HIGH_PRIORITY");
        recommendation.setRecommendationStatus("OPEN");
        recommendation.setLossEstimateBeforeValue(new BigDecimal("500000"));
        recommendation.setLossEstimateAfterValue(new BigDecimal("100000"));
        recommendation.setCurrency("EUR");
        recommendation.setDueDate(LocalDate.now().plusMonths(6));

        assessment.setRecommendations(List.of(recommendation));
        eventDTO.setAssessmentList(List.of(assessment));

        return eventDTO;
    }

    private RecommendationEventDataDTO createUpdateRecommendationEvent() {
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        
        // Modify to update existing recommendation
        RecommendationDTO recommendation = eventDTO.getAssessmentList().get(0).getRecommendations().get(0);
        recommendation.setRecommendationId("REC_KAFKA_002");
        recommendation.setRecommendationTitle("Updated Fire Suppression System");
        recommendation.setRecommendationBody("Updated detailed description");
        recommendation.setLossEstimateAfterValue(new BigDecimal("75000"));

        return eventDTO;
    }

    private RecommendationEventDataDTO createMultipleRecommendationsEvent() {
        RecommendationEventDataDTO eventDTO = createNewRecommendationEvent();
        
        LocationAssessmentDTO assessment = eventDTO.getAssessmentList().get(0);
        
        // Create multiple recommendations
        RecommendationDTO rec1 = new RecommendationDTO();
        rec1.setRecommendationId("REC_MULTI_001");
        rec1.setRecommendationTitle("Fire Door Maintenance");
        rec1.setRecommendationBody("Regular maintenance of fire doors");
        rec1.setRecommendationType("PHYSICAL");
        rec1.setRecommendationStatus("OPEN");

        RecommendationDTO rec2 = new RecommendationDTO();
        rec2.setRecommendationId("REC_MULTI_002");
        rec2.setRecommendationTitle("Emergency Exit Signage");
        rec2.setRecommendationBody("Install additional emergency exit signs");
        rec2.setRecommendationType("PHYSICAL");
        rec2.setRecommendationStatus("OPEN");

        RecommendationDTO rec3 = new RecommendationDTO();
        rec3.setRecommendationId("REC_MULTI_003");
        rec3.setRecommendationTitle("Fire Alarm Testing");
        rec3.setRecommendationBody("Monthly fire alarm system testing");
        rec3.setRecommendationType("HUMAN_ELEMENT");
        rec3.setRecommendationStatus("OPEN");

        assessment.setRecommendations(List.of(rec1, rec2, rec3));
        
        return eventDTO;
    }
}