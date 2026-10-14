package com.pm.patientservice.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Our own stable JSON shape for paged results. We deliberately do not serialize Spring's
 * PageImpl directly: its JSON structure is not a supported, stable contract.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}