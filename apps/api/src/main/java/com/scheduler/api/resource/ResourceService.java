// TASK: ATOM-RESOURCE-002 / ATOM-SLOT-008
package com.scheduler.api.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.common.ApiException;
import com.scheduler.api.config.CacheConfig;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.dto.BreakEntry;
import com.scheduler.api.resource.dto.CreateResourceRequest;
import com.scheduler.api.resource.dto.ResourceResponse;
import com.scheduler.api.resource.dto.ScheduleEntry;
import com.scheduler.api.tenant.TenantScoped;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resource registration plus atomic schedule/break replacement. Both nested
 * collections are deleted and reinserted inside one {@code @Transactional}
 * boundary — no partial state can survive a failure (AC-04). Any schedule
 * or break mutation evicts the Redis slot-input caches (ATOM-SLOT-008).
 */
@Service
@TenantScoped
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final ResourceScheduleRepository scheduleRepository;
    private final ResourceBreakRepository breakRepository;
    private final LocationRepository locationRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<Resource> list(UUID tenantId, UUID locationId, boolean includeInactive,
                               Pageable pageable) {
        requireLocation(tenantId, locationId);
        return includeInactive
            ? resourceRepository.findByTenantIdAndLocationId(tenantId, locationId, pageable)
            : resourceRepository.findByTenantIdAndLocationIdAndStatus(
                tenantId, locationId, Resource.STATUS_ACTIVE, pageable);
    }

    @Transactional(readOnly = true)
    public ResourceResponse get(UUID tenantId, UUID resourceId) {
        Resource resource = requireResource(tenantId, resourceId);
        return toResponse(resource,
            scheduleRepository.findByTenantIdAndResourceId(tenantId, resourceId),
            breakRepository.findByTenantIdAndResourceId(tenantId, resourceId));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_RESOURCE_SCHEDULES, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RESOURCE_BREAKS, allEntries = true)
    })
    public ResourceResponse create(UUID tenantId, UUID locationId, CreateResourceRequest req) {
        requireLocation(tenantId, locationId);
        List<ScheduleEntry> schedule = req.schedule() == null ? List.of() : req.schedule();
        List<BreakEntry> breaks = req.breaks() == null ? List.of() : req.breaks();
        validateScheduleEntries(schedule);
        validateBreakEntries(breaks);

        Resource resource = resourceRepository.save(Resource.builder()
            .tenantId(tenantId)
            .locationId(locationId)
            .name(req.name())
            .resourceType(req.resourceType())
            .extension(writeExtension(req.extension()))
            .status(Resource.STATUS_ACTIVE)
            .build());

        List<ResourceSchedule> schedules = schedule.stream()
            .map(e -> toScheduleEntity(tenantId, resource.getId(), e))
            .toList();
        List<ResourceBreak> breakRows = breaks.stream()
            .map(e -> toBreakEntity(tenantId, resource.getId(), e))
            .toList();
        scheduleRepository.saveAll(schedules);
        breakRepository.saveAll(breakRows);

        return toResponse(resource, schedules, breakRows);
    }

    /** Atomic replace: delete-all + reinsert in one transaction (AC-04). */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_RESOURCE_SCHEDULES, allEntries = true)
    public List<ScheduleEntry> replaceSchedule(UUID tenantId, UUID resourceId,
                                               List<ScheduleEntry> entries) {
        requireResource(tenantId, resourceId);
        validateScheduleEntries(entries);
        scheduleRepository.deleteByTenantIdAndResourceId(tenantId, resourceId);
        scheduleRepository.saveAll(entries.stream()
            .map(e -> toScheduleEntity(tenantId, resourceId, e))
            .toList());
        return entries;
    }

    /** Atomic replace: delete-all + reinsert in one transaction (AC-04). */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_RESOURCE_BREAKS, allEntries = true)
    public List<BreakEntry> replaceBreaks(UUID tenantId, UUID resourceId,
                                          List<BreakEntry> entries) {
        requireResource(tenantId, resourceId);
        validateBreakEntries(entries);
        breakRepository.deleteByTenantIdAndResourceId(tenantId, resourceId);
        breakRepository.saveAll(entries.stream()
            .map(e -> toBreakEntity(tenantId, resourceId, e))
            .toList());
        return entries;
    }

    /** Soft-delete: status flips to inactive, preserving booking history. */
    @Transactional
    public void softDelete(UUID tenantId, UUID resourceId) {
        Resource resource = requireResource(tenantId, resourceId);
        resource.setStatus(Resource.STATUS_INACTIVE);
        resourceRepository.save(resource);
    }

    // ------------------------------------------------------------------

    private Resource requireResource(UUID tenantId, UUID resourceId) {
        return resourceRepository.findByIdAndTenantId(resourceId, tenantId)
            .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "Resource not found."));
    }

    private void requireLocation(UUID tenantId, UUID locationId) {
        locationRepository.findByIdAndTenantId(locationId, tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
    }

    private void validateScheduleEntries(List<ScheduleEntry> entries) {
        for (ScheduleEntry e : entries) {
            if (!e.endTime().isAfter(e.startTime())) {
                throw ApiException.badRequest("INVALID_TIME_WINDOW",
                    "endTime must be after startTime.", "endTime");
            }
        }
        List<ScheduleEntry> sorted = entries.stream()
            .sorted(Comparator.comparingInt(ScheduleEntry::dayOfWeek)
                .thenComparing(ScheduleEntry::startTime))
            .toList();
        for (int i = 1; i < sorted.size(); i++) {
            ScheduleEntry prev = sorted.get(i - 1);
            ScheduleEntry curr = sorted.get(i);
            if (prev.dayOfWeek() == curr.dayOfWeek()
                    && curr.startTime().isBefore(prev.endTime())) {
                throw ApiException.unprocessable("OVERLAPPING_SCHEDULE",
                    "Schedule windows overlap on dayOfWeek " + curr.dayOfWeek() + ".");
            }
        }
    }

    private void validateBreakEntries(List<BreakEntry> entries) {
        for (BreakEntry e : entries) {
            if (!e.breakEnd().isAfter(e.breakStart())) {
                throw ApiException.badRequest("INVALID_TIME_WINDOW",
                    "breakEnd must be after breakStart.", "breakEnd");
            }
        }
    }

    private ResourceSchedule toScheduleEntity(UUID tenantId, UUID resourceId, ScheduleEntry e) {
        return ResourceSchedule.builder()
            .tenantId(tenantId)
            .resourceId(resourceId)
            .dayOfWeek(e.dayOfWeek())
            .startTime(e.startTime())
            .endTime(e.endTime())
            .isActive(true)
            .build();
    }

    private ResourceBreak toBreakEntity(UUID tenantId, UUID resourceId, BreakEntry e) {
        return ResourceBreak.builder()
            .tenantId(tenantId)
            .resourceId(resourceId)
            .dayOfWeek(e.dayOfWeek())
            .breakStart(e.breakStart())
            .breakEnd(e.breakEnd())
            .label(e.label())
            .build();
    }

    /** Serializes the opaque extension map verbatim — never interpreted. */
    private String writeExtension(Map<String, Object> extension) {
        if (extension == null || extension.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(extension);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("INVALID_EXTENSION",
                "extension must be a valid JSON object.", "extension");
        }
    }

    private JsonNode readExtension(String extension) {
        try {
            return objectMapper.readTree(extension == null ? "{}" : extension);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private ResourceResponse toResponse(Resource resource,
                                        List<ResourceSchedule> schedules,
                                        List<ResourceBreak> breaks) {
        return new ResourceResponse(
            resource.getId(),
            resource.getLocationId(),
            resource.getName(),
            resource.getResourceType(),
            resource.getStatus(),
            readExtension(resource.getExtension()),
            schedules.stream()
                .map(s -> new ScheduleEntry(s.getDayOfWeek(), s.getStartTime(), s.getEndTime()))
                .toList(),
            breaks.stream()
                .map(b -> new BreakEntry(b.getDayOfWeek(), b.getBreakStart(), b.getBreakEnd(),
                    b.getLabel()))
                .toList());
    }
}
