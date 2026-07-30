// TASK: ATOM-SERVICE-003 / ATOM-BOOKING-010
package com.scheduler.api.servicetype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IntakeSchemaValidator validator = new IntakeSchemaValidator(mapper);

    private static final String VALID_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "primaryConcern": { "type": "string", "title": "Primary Concern" },
            "referenceNumber": { "type": "string" }
          },
          "required": ["primaryConcern"]
        }
        """;

    @Test
    void validDraft07Schema_passes() throws JsonProcessingException {
        assertThatCode(() -> validator.requireValidSchemaDocument(read(VALID_SCHEMA)))
            .doesNotThrowAnyException();
    }

    @Test
    void invalidSchemaDocument_throws422InvalidJsonSchema() throws JsonProcessingException {
        JsonNode broken = read("""
            { "type": 123, "properties": "not-an-object" }
            """);

        assertThatThrownBy(() -> validator.requireValidSchemaDocument(broken))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException ex = (ApiException) e;
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ex.getCode()).isEqualTo("INVALID_JSON_SCHEMA");
                assertThat(ex.getErrors()).isNotEmpty();
            });
    }

    @Test
    void conformingExtensionData_passes() throws JsonProcessingException {
        JsonNode data = read("""
            { "primaryConcern": "General inquiry" }
            """);

        assertThatCode(() -> validator.requireDataMatchesSchema(VALID_SCHEMA, data))
            .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredField_throws422ExtensionSchemaViolation()
            throws JsonProcessingException {
        JsonNode data = read("""
            { "referenceNumber": "R-1" }
            """);

        assertThatThrownBy(() -> validator.requireDataMatchesSchema(VALID_SCHEMA, data))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException ex = (ApiException) e;
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ex.getCode()).isEqualTo("EXTENSION_SCHEMA_VIOLATION");
                assertThat(ex.getErrors()).isNotEmpty();
            });
    }

    @Test
    void emptySchema_enforcesNothing() throws JsonProcessingException {
        assertThatCode(() -> validator.requireDataMatchesSchema("{}",
            read("{ \"anything\": true }")))
            .doesNotThrowAnyException();
    }

    private JsonNode read(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }
}
