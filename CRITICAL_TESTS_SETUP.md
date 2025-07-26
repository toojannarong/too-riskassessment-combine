# Critical Missing Tests - Setup Guide

## Overview
This document provides setup instructions for the critical integration tests that address the highest priority testing gaps in the recommendation system.

## Prerequisites

### 1. Maven Dependencies
Add these dependencies to your `pom.xml`:

```xml
<dependencies>
    <!-- TestContainers MongoDB Atlas Local -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mongodb</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- Spring Kafka Test -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka-test</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- Awaitility for async testing -->
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- TestContainers JUnit Jupiter -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2. Docker Setup
Ensure Docker is installed and running on your system:
- **Docker Desktop** (Windows/Mac) or **Docker Engine** (Linux)
- Minimum **4GB RAM** available for containers
- **Internet connection** for pulling Docker images

### 3. MongoDB Atlas Local Container
The tests use MongoDB Atlas Local container which includes:
- ✅ **Full Atlas Search functionality**
- ✅ **Search index creation support**
- ✅ **Aggregation pipeline support**
- ✅ **Real Atlas Search behavior**

## Test Files Structure

```
src/test/java/com/allianz/agcs/riskassessment/
├── repositories/
│   └── MongoDBAtlasSearchIntegrationTest.java     # CRITICAL: Atlas Search testing
├── controllers/
│   └── ArcRecommendationControllerIntegrationTest.java  # API integration
├── integration/
│   └── RecommendationKafkaToDbIntegrationTest.java      # Kafka-to-DB flow
├── security/
│   └── RecommendationSecurityIntegrationTest.java       # Security validation
└── CRITICAL_TESTS_SETUP.md                             # This file
```

## Key Features

### 🔍 MongoDB Atlas Search Integration
- **Real Atlas Search index creation** using `Id39ArcRecommendationAddAtlasSearchIndex` migration logic
- **Text search validation** with nGram tokenization
- **Autocomplete functionality** testing
- **Search ranking and relevance** verification

### 🚀 Full HTTP Stack Testing
- **Real HTTP requests** with proper serialization
- **Authorization header validation** (`submission-id`)
- **Error response handling** (400, 404, 401, 500)
- **Concurrent request testing**

### 📨 End-to-End Kafka Flow
- **Event publishing and consumption**
- **Database entity creation/updates**
- **Event validation and error handling**
- **Concurrency and idempotency**

### 🔒 Security Validation
- **Multi-tenant data isolation**
- **Cross-submission access prevention**
- **NoSQL injection prevention**
- **Input sanitization validation**

## Running the Tests

### Individual Test Execution
```bash
# Atlas Search tests (MOST CRITICAL)
mvn test -Dtest="MongoDBAtlasSearchIntegrationTest"

# API Integration tests
mvn test -Dtest="ArcRecommendationControllerIntegrationTest"

# Kafka Integration tests
mvn test -Dtest="RecommendationKafkaToDbIntegrationTest"

# Security tests
mvn test -Dtest="RecommendationSecurityIntegrationTest"
```

### All Critical Tests
```bash
mvn test -Dtest="*AtlasSearch*,*ControllerIntegration*,*KafkaToDb*,*Security*"
```

### Full Test Suite
```bash
mvn test
```

## Atlas Search Index Creation

The tests automatically create the Atlas Search index using the exact same logic as your Mongock migration `Id39ArcRecommendationAddAtlasSearchIndex`:

```java
// Index structure matches production exactly
org.bson.Document searchIndexCommand = new org.bson.Document("createSearchIndexes", "arcRecommendation")
    .append("indexes", List.of(
        new org.bson.Document("name", "default")
            .append("definition", new org.bson.Document("mappings", new org.bson.Document("dynamic", false)
                .append("fields", new org.bson.Document()
                    .append("submissionBaseNr", new org.bson.Document("type", "string"))
                    .append("recommendationTitle", List.of(
                        new org.bson.Document("type", "autocomplete")
                            .append("tokenization", "nGram")
                            .append("minGrams", 1),
                        new org.bson.Document("type", "string")
                    ))
                    // ... additional fields
                )
            ))
    ));
```

## Test Execution Time

- **Atlas Search Tests**: ~30-45 seconds (includes container startup + index creation)
- **API Integration Tests**: ~20-30 seconds
- **Kafka Integration Tests**: ~45-60 seconds (includes Kafka broker startup)
- **Security Tests**: ~25-35 seconds

## Troubleshooting

### Docker Issues
```bash
# Check Docker is running
docker ps

# Pull MongoDB Atlas Local image manually
docker pull mongodb/mongodb-atlas-local:7.0.9

# Check available memory
docker system df
```

### Test Failures
```bash
# Run with debug logging
mvn test -Dtest="MongoDBAtlasSearchIntegrationTest" -Dlogging.level.com.allianz=DEBUG

# Check container logs
docker logs $(docker ps -q --filter ancestor=mongodb/mongodb-atlas-local:7.0.9)
```

### Port Conflicts
If you encounter port conflicts, ensure these ports are available:
- **MongoDB**: Dynamic port (handled by TestContainers)
- **Kafka**: 9092 (for embedded Kafka tests)
- **HTTP**: Random port (handled by @SpringBootTest)

## CI/CD Integration

### GitHub Actions
```yaml
- name: Run Critical Integration Tests
  run: |
    mvn test -Dtest="*AtlasSearch*,*ControllerIntegration*,*KafkaToDb*,*Security*"
  env:
    DOCKER_HOST: unix:///var/run/docker.sock
```

### Jenkins
```groovy
stage('Critical Tests') {
    steps {
        sh 'mvn test -Dtest="*AtlasSearch*,*ControllerIntegration*,*KafkaToDb*,*Security*"'
    }
}
```

## Performance Considerations

### Resource Requirements
- **RAM**: 4-6GB available for containers
- **CPU**: 2+ cores recommended
- **Disk**: 2GB free space for images
- **Network**: Internet connection for image pulls

### Optimization Tips
1. **Reuse containers** between test methods where possible
2. **Parallel execution** for independent test classes
3. **Container caching** in CI/CD pipelines
4. **Resource limits** for containers in CI environments

## Success Criteria

After running these tests, you should see:

✅ **Atlas Search functionality validated**
✅ **Multi-tenant security enforced**
✅ **Full HTTP stack integration verified**
✅ **Kafka event processing confirmed**
✅ **Error handling and edge cases covered**

These tests address the **most critical gaps** in your current test coverage and provide confidence in the recommendation system's reliability and security.

## Next Steps

1. **Add to CI pipeline** for continuous validation
2. **Extend test scenarios** based on specific business requirements
3. **Monitor test execution times** and optimize as needed
4. **Consider load testing** for performance validation
5. **Add frontend E2E tests** to complete the testing pyramid