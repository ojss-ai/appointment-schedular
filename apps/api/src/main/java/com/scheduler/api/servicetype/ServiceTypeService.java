// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.common.ApiException;
import com.scheduler.api.servicetype.dto.ServiceTypeRequest;
import com.scheduler.api.servicetype.dto.ServiceTypeResponse;
import com.scheduler.api.tenant.TenantScoped;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service type CRUD with intake-schema storage. The intake JSON Schema is
 * validated as a schema document at write time and stored verbatim; only
 * {@link IntakeSchemaValidator} ever interprets it. Soft-delete blocks new
 * holds without touching existing CONFIRMED bookings (AC-05/AC-06).
 */
@Service
@TenantScoped
@RequiredArgsConstructor
public class ServiceTypeService {

    private final ServiceTypeRepository serviceTypeRepository;
    private final IntakeSchemaValidator intakeSchemaValidator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<ServiceTypeResponse> list(UUID tenantId, boolean includeInactive,
                                          Pageable pageable) {
        Page<ServiceType> page = includeInactive
            ? serviceTypeRepository.findByTenantId(tenantId, pageable)
            : serviceTypeRepository.findByTenantIdAndStatus(
                tenantId, ServiceType.STATUS_ACTIVE, pageable);
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ServiceTypeResponse get(UUID tenantId, UUID serviceTypeId) {
        return toResponse(require(tenantId, serviceTypeId));
    }

    @Transactional
    public ServiceTypeResponse create(UUID tenantId, ServiceTypeRequest req) {
        String schemaJson = validateAndSerializeSchema(req.intakeSchema());
        ServiceType serviceType = ServiceType.builder()
            .tenantId(tenantId)
            .name(req.name())
            .description(req.description())
            .durationMinutes(req.durationMinutes())
            .bufferBeforeMin(req.bufferBeforeMin())
            .bufferAfterMin(req.bufferAfterMin())
            .allowedResourceTypes(req.allowedResourceTypes() == null
                ? List.of() : req.allowedResourceTypes())
            .intakeSchema(schemaJson)
            .status(ServiceType.STATUS_ACTIVE)
            .build();
        return toResponse(serviceTypeRepository.save(serviceType));
    }

    @Transactional
    public ServiceTypeResponse update(UUID tenantId, UUID serviceTypeId, ServiceTypeRequest req) {
        String schemaJson = validateAndSerializeSchema(req.intakeSchema());
        ServiceType serviceType = require(tenantId, serviceTypeId);
        serviceType.setName(req.name());
        serviceType.setDescription(req.description());
        serviceType.setDurationMinutes(req.durationMinutes());
        serviceType.setBufferBeforeMin(req.bufferBeforeMin());
        serviceType.setBufferAfterMin(req.bufferAfterMin());
        serviceType.setAllowedResourceTypes(req.allowedResourceTypes() == null
            ? List.of() : req.allowedResourceTypes());
        serviceType.setIntakeSchema(schemaJson);
        return toResponse(serviceTypeRepository.save(serviceType));
    }

    /** Soft-delete: blocks new holds; existing CONFIRMED bookings unaffected. */
    @Transactional
    public void softDelete(UUID tenantId, UUID serviceTypeId) {
        ServiceType serviceType = require(tenantId, serviceTypeId);
        serviceType.setStatus(ServiceType.STATUS_INACTIVE);
        serviceTypeRepository.save(serviceType);
    }

    // ------------------------------------------------------------------

    private ServiceType require(UUID tenantId, UUID serviceTypeId) {
        return serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId)
            .orElseThrow(() ->
                ApiException.notFound("SERVICE_TYPE_NOT_FOUND", "Service type not found."));
    }

    private String validateAndSerializeSchema(JsonNode intakeSchema) {
        if (intakeSchema == null || intakeSchema.isNull() || intakeSchema.isEmpty()) {
            return "{}";
        }
        intakeSchemaValidator.requireValidSchemaDocument(intakeSchema);
        return intakeSchema.toString();
    }

    private ServiceTypeResponse toResponse(ServiceType s) {
        JsonNode schemaNode;
        try {
            schemaNode = objectMapper.readTree(
                s.getIntakeSchema() == null ? "{}" : s.getIntakeSchema());
        } catch (JsonProcessingException e) {
            schemaNode = objectMapper.createObjectNode();
        }
        return new ServiceTypeResponse(
            s.getId(), s.getName(), s.getDescription(), s.getDurationMinutes(),
            s.getBufferBeforeMin(), s.getBufferAfterMin(), s.getAllowedResourceTypes(),
            schemaNode, s.getStatus());
    }
}
