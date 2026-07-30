// TASK: PHASE-2 (atoms 01-13)
package com.scheduler.api.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Pagination envelope per API-SPEC conventions:
 * {@code { content, totalElements, page, size }}.
 */
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int page,
        int size
) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
            page.getContent().stream().map(mapper).toList(),
            page.getTotalElements(),
            page.getNumber(),
            page.getSize());
    }
}
