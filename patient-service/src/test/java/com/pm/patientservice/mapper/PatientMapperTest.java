package com.pm.patientservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientMapperTest {

    @Test
    void toDTO_mapsEntityFields() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(id);
        patient.setName("Jane Doe");
        patient.setEmail("jane@example.com");
        patient.setAddress("1 Main St");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setRegisteredDate(LocalDate.of(2024, 1, 1));

        PatientResponseDTO dto = PatientMapper.toDTO(patient);

        assertThat(dto.getId()).isEqualTo(id.toString());
        assertThat(dto.getName()).isEqualTo("Jane Doe");
        assertThat(dto.getEmail()).isEqualTo("jane@example.com");
        assertThat(dto.getAddress()).isEqualTo("1 Main St");
        assertThat(dto.getDateOfBirth()).isEqualTo("1990-01-01");
    }

    @Test
    void toModel_mapsRequestFieldsIncludingDates() {
        PatientRequestDTO request = new PatientRequestDTO();
        request.setName("Jane Doe");
        request.setEmail("jane@example.com");
        request.setAddress("1 Main St");
        request.setDateOfBirth(LocalDate.of(1990, 1, 1));
        request.setRegisteredDate(LocalDate.of(2024, 1, 1));

        Patient patient = PatientMapper.toModel(request);

        assertThat(patient.getId()).isNull();
        assertThat(patient.getName()).isEqualTo("Jane Doe");
        assertThat(patient.getEmail()).isEqualTo("jane@example.com");
        assertThat(patient.getAddress()).isEqualTo("1 Main St");
        assertThat(patient.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(patient.getRegisteredDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }
}