# Test Validation Guide

## Issue Fixed: FilterOperatorType NullPointerException

### Problem
The tests were failing with:
```
java.lang.NullPointerException: Cannot invoke "com.allianz.agcs.riskassessment.types.FilterOperatorType.ordinal()" because "op" is null
```

### Root Cause
The `FilterItemRequest` requires both:
1. `filterType` (FilterType enum)
2. `type` (FilterOperatorType enum) - **This was missing!**

### Solution Applied
Updated all `FilterItemRequest` creation from:
```java
// OLD - Missing FilterOperatorType
FilterItemRequest textFilter = new FilterItemRequest();
textFilter.setFilterType(FilterType.TEXT);
textFilter.setFilter("search term");
```

To:
```java
// NEW - Includes FilterOperatorType
FilterItemRequest textFilter = FilterItemRequest.builder()
    .filterType(FilterType.TEXT)
    .type(FilterOperatorType.CONTAINS)  // Added this!
    .filter("search term")
    .build();
```

## Available FilterOperatorType Values

Based on the enum definition:
- `EQUALS` - Exact match using Atlas Search "text" operator
- `CONTAINS` - Partial match using Atlas Search "autocomplete" operator
- `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL` - For number/date fields
- `IN_RANGE` - For range queries

## Test Validation Commands

### 1. Quick Syntax Check
```bash
mvn compile test-compile
```

### 2. Run Atlas Search Test Only
```bash
mvn test -Dtest="MongoDBAtlasSearchIntegrationTest#shouldFindRecommendationsByTextSearch_FireSafety"
```

### 3. Run All Critical Tests
```bash
mvn test -Dtest="*AtlasSearch*,*ControllerIntegration*,*KafkaToDb*,*Security*"
```

### 4. Verify Test Output
Look for:
- ✅ "Successfully created Atlas Search index for arcRecommendation collection"
- ✅ No FilterOperatorType NullPointerException
- ✅ Atlas Search queries executing properly

## Expected Test Flow

1. **Container Startup**: MongoDB Atlas Local container starts
2. **Index Creation**: Atlas Search index created using Mongock migration logic
3. **Test Data**: Recommendation entities inserted
4. **Search Execution**: Atlas Search queries with proper FilterOperatorType
5. **Assertions**: Results validated for correctness

## Debugging Tips

### If tests still fail:
```bash
# Check Docker containers
docker ps | grep mongodb

# View container logs
docker logs $(docker ps -q --filter ancestor=mongodb/mongodb-atlas-local:7.0.9)

# Run with debug logging
mvn test -Dtest="MongoDBAtlasSearchIntegrationTest" -Dlogging.level.com.allianz=DEBUG
```

### Common Issues:
1. **Docker not running**: Ensure Docker Desktop/Engine is started
2. **Port conflicts**: TestContainers handles ports automatically
3. **Memory issues**: Ensure 4GB+ RAM available for containers
4. **Image pull**: May take time on first run to download mongodb/mongodb-atlas-local:7.0.9

## Test Coverage Validation

After successful execution, verify these scenarios work:
- ✅ Text search with CONTAINS operator
- ✅ Text search with EQUALS operator  
- ✅ Combined filters (text + status)
- ✅ Special characters in search
- ✅ Unicode text handling
- ✅ Empty/null search terms
- ✅ Submission isolation
- ✅ Performance with large datasets

The fix ensures that the Atlas Search integration tests now properly construct filter requests with the required FilterOperatorType, allowing the AgGridToMongoFilterHelper to correctly build Atlas Search queries.