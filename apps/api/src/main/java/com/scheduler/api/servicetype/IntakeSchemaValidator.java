// TASK: ATOM-SERVICE-003 / ATOM-BOOKING-010
package com.scheduler.api.servicetype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.scheduler.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * JSON Schema (draft-07) gatekeeper. Two duties:
 *
 * <ol>
 *   <li>At service-type write time: the tenant-supplied {@code intakeSchema}
 *       must itself be a valid draft-07 schema (422 INVALID_JSON_SCHEMA).</li>
 *   <li>At booking confirmation: the customer's {@code extensionData} must
 *       satisfy that schema (422 EXTENSION_SCHEMA_VIOLATION).</li>
 * </ol>
 *
 * The schema document and the extension payload remain opaque to all other
 * core logic (ADR-005) — this component is the single, generic touch point.
 */
@Component
@RequiredArgsConstructor
public class IntakeSchemaValidator {

    private static final JsonSchemaFactory FACTORY =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private final ObjectMapper objectMapper;

    /** Validates that {@code schemaJson} is a structurally valid draft-07 schema. */
    public void requireValidSchemaDocument(JsonNode schemaNode) {
        try {
            JsonSchema metaSchema = FACTORY.getSchema(SchemaLocation.of(SchemaId.V7));
            Set<ValidationMessage> violations = metaSchema.validate(schemaNode);
            if (!violations.isEmpty()) {
                throw invalidSchema(toMessages(violations));
            }
            // Must also compile without errors.
            FACTORY.getSchema(schemaNode);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalidSchema(List.of(e.getMessage() == null ? "Schema failed to compile."
                : e.getMessage()));
        }
    }

    /** Validates confirmation extension data against a stored intake schema. */
    public void requireDataMatchesSchema(String schemaJson, JsonNode data) {
        JsonNode schemaNode = readTree(schemaJson);
        if (schemaNode == null || schemaNode.isEmpty()) {
            return; // no intake schema defined — nothing to enforce
        }
        Set<ValidationMessage> violations;
        try {
            violations = FACTORY.getSchema(schemaNode).validate(data);
        } catch (RuntimeException e) {
            // A stored-but-broken schema must not block confirmation with a 500.
            return;
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "EXTENSION_SCHEMA_VIOLATION",
                "extensionData does not satisfy the service intake schema.",
                null, toMessages(violations));
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static List<String> toMessages(Set<ValidationMessage> violations) {
        return violations.stream().map(ValidationMessage::getMessage).toList();
    }

    private static ApiException invalidSchema(List<String> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_JSON_SCHEMA",
            "intakeSchema is not a valid JSON Schema draft-07 document.", "intakeSchema", errors);
    }
}
