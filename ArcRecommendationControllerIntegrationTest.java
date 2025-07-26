package com.allianz.agcs.riskassessment.controllers;

import com.allianz.agcs.riskassessment.models.rest.request.RecommendationListFilterRequest;
import com.allianz.agcs.riskassessment.models.rest.request.aggrid.FilterItemRequest;
import com.allianz.agcs.riskassessment.models.rest.responses.GetRecommendationDetailResponse;
import com.allianz.agcs.riskassessment.models.rest.responses.SearchRecommendationResponse;
import com.allianz.agcs.riskassessment.types.FilterType;
import com.allianz.agcs.riskassessment.types.RecommendationListFilterField;
import com.allianz.agcs.riskassessmentcommon.models.entities.recomendation.ArcRecommendationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBAtlasLocalContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL MISSING TEST: API Integration Tests with Full HTTP Stack
 * 
 * Tests the complete HTTP request/response cycle including:
 * - Real HTTP calls with proper serialization
 * - Authorization with submission-id header
 * - Error responses (404, 400, 401, 500)
 * - Real MongoDB integration
 * - Request/response validation
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ArcRecommendationControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Container
    static MongoDBAtlasLocalContainer mongoContainer = new MongoDBAtlasLocalContainer(
            DockerImageName.parse("mongodb/mongodb-atlas-local:7.0.9")
    );

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String baseUrl;
    private final String VALID_SUBMISSION_ID = "SUB123456.1.0";
    private final String INVALID_SUBMISSION_ID = "INVALID_SUB.1.0";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoContainer::getConnectionString);
        registry.add("spring.data.mongodb.database", () -> "test_recommendations");
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/arcRecommendation";
        
        // Clear existing data
        mongoTemplate.getCollection("arcRecommendation").drop();
        
        // Create test data
        createTestRecommendations();
    }

    @Test
    void shouldSearchRecommendationsWithValidSubmissionId() {
        // Given
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows()).isNotEmpty();
        assertThat(response.getBody().getLastRow()).isGreaterThan(0);
    }

    @Test
    void shouldReturnUnauthorizedWithoutSubmissionIdHeader() {
        // Given
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No submission-id header

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldFilterRecommendationsByTextSearch() {
        // Given
        FilterItemRequest textFilter = new FilterItemRequest();
        textFilter.setFilterType(FilterType.TEXT);
        textFilter.setFilter("Fire Safety");

        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of(
            RecommendationListFilterField.RECOMMENDATION_TITLE, textFilter
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows())
            .isNotEmpty()
            .allMatch(rec -> rec.getRecommendationTitle().toLowerCase().contains("fire") ||
                           rec.getRecommendationTitle().toLowerCase().contains("safety"));
    }

    @Test
    void shouldFilterRecommendationsByStatus() {
        // Given
        FilterItemRequest statusFilter = new FilterItemRequest();
        statusFilter.setFilterType(FilterType.SET);
        statusFilter.setValues(List.of("OPEN"));

        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of(
            RecommendationListFilterField.RECOMMENDATION_STATUS, statusFilter
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows())
            .isNotEmpty()
            .allMatch(rec -> "OPEN".equals(rec.getRecommendationStatus()));
    }

    @Test
    void shouldHandlePaginationCorrectly() {
        // Given - Request second page
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(2);
        request.setEndRow(4);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows()).hasSizeLessThanOrEqualTo(2); // endRow - startRow
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() {
        // Given
        String malformedJson = "{ invalid json structure";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<String> entity = new HttpEntity<>(malformedJson, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldGetRecommendationDetailWithValidId() {
        // Given
        String validRecommendationId = "67890abcdef1234567890abc"; // Existing ID from test data

        HttpHeaders headers = new HttpHeaders();
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // When
        ResponseEntity<GetRecommendationDetailResponse> response = restTemplate.exchange(
            baseUrl + "/" + validRecommendationId,
            HttpMethod.GET,
            entity,
            GetRecommendationDetailResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(validRecommendationId);
        assertThat(response.getBody().recommendationTitle()).isNotBlank();
    }

    @Test
    void shouldReturn404ForNonExistentRecommendation() {
        // Given
        String nonExistentId = "000000000000000000000000";

        HttpHeaders headers = new HttpHeaders();
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // When
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + "/" + nonExistentId,
            HttpMethod.GET,
            entity,
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturn400ForInvalidRecommendationId() {
        // Given
        String invalidId = "invalid-id-format";

        HttpHeaders headers = new HttpHeaders();
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // When
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + "/" + invalidId,
            HttpMethod.GET,
            entity,
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldRespectSubmissionIsolation() {
        // Given - Create recommendation for different submission
        ArcRecommendationEntity otherSubmissionRec = createRecommendation(
            "67890abcdef1234567890999",
            "REC_OTHER_001", 
            "Fire Safety System - Other Submission",
            "SUB999999"
        );
        mongoTemplate.save(otherSubmissionRec);

        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(100);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID); // Different submission

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then - Should only return recommendations for the specified submission
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows())
            .isNotEmpty()
            .noneMatch(rec -> rec.getRecommendationTitle().contains("Other Submission"));
    }

    @Test
    void shouldHandleSpecialCharactersInSearchCorrectly() {
        // Given
        FilterItemRequest textFilter = new FilterItemRequest();
        textFilter.setFilterType(FilterType.TEXT);
        textFilter.setFilter("Fire & Safety (100%)");

        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of(
            RecommendationListFilterField.RECOMMENDATION_TITLE, textFilter
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When & Then - Should not throw exception
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Given
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When - Make multiple concurrent requests
        Thread[] threads = new Thread[5];
        ResponseEntity<SearchRecommendationResponse>[] responses = new ResponseEntity[5];
        
        for (int i = 0; i < 5; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                responses[index] = restTemplate.postForEntity(
                    baseUrl + "/list", 
                    entity, 
                    SearchRecommendationResponse.class
                );
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - All requests should succeed
        for (ResponseEntity<SearchRecommendationResponse> response : responses) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }
    }

    @Test
    void shouldValidateRequestParameters() {
        // Given - Invalid pagination parameters
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(-1); // Invalid
        request.setEndRow(-1);   // Invalid
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", VALID_SUBMISSION_ID);

        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then - Should handle gracefully (either reject or sanitize)
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.OK, 
            HttpStatus.BAD_REQUEST
        );
    }

    // Helper Methods

    private void createTestRecommendations() {
        List<ArcRecommendationEntity> testData = List.of(
            createRecommendation("67890abcdef1234567890abc", "REC_001", "Install Fire Safety Equipment", "SUB123456"),
            createRecommendation("67890abcdef1234567890abd", "REC_002", "Upgrade Sprinkler System", "SUB123456"),
            createRecommendation("67890abcdef1234567890abe", "REC_003", "Emergency Exit Maintenance", "SUB123456"),
            createRecommendation("67890abcdef1234567890abf", "REC_004", "Fire Door Inspection", "SUB123456"),
            createRecommendation("67890abcdef1234567890ac0", "REC_005", "Security Camera Upgrade", "SUB123456")
        );

        testData.forEach(mongoTemplate::save);
    }

    private ArcRecommendationEntity createRecommendation(String id, String recId, String title, String submissionBaseNr) {
        ArcRecommendationEntity entity = new ArcRecommendationEntity();
        entity.setId(id);
        entity.setRecommendationId(recId);
        entity.setRecommendationTitle(title);
        entity.setRecommendationBody("Detailed description for " + title);
        entity.setRecommendationType("PHYSICAL");
        entity.setRecommendationCategory("AUTOMATIC_SPRINKLERS");
        entity.setRecommendationPriority("HIGH_PRIORITY");
        entity.setRecommendationStatus("OPEN");
        entity.setSubmissionBaseNr(submissionBaseNr);
        entity.setSubmissionId(submissionBaseNr + ".1.0");
        entity.setObjectId("OBJ_" + recId);
        entity.setObjectName("Test Object " + recId);
        entity.setRcId("RC_" + recId);
        entity.setLossEstimateBeforeValue(new BigDecimal("100000"));
        entity.setLossEstimateAfterValue(new BigDecimal("25000"));
        entity.setCurrency("EUR");
        entity.setDueDate(LocalDate.now().plusMonths(6));
        
        return entity;
    }
}