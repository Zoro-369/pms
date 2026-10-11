package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;

public class PatientMapper {
  public static PatientResponseDTO toDTO(Patient patient) {
    PatientResponseDTO patientDTO = new PatientResponseDTO();
    patientDTO.setId(patient.getId().toString());
    patientDTO.setName(patient.getName());
    patientDTO.setAddress(patient.getAddress());
    patientDTO.setEmail(patient.getEmail());
    patientDTO.setDateOfBirth(patient.getDateOfBirth().toString());

    return patientDTO;
  }

  public static Patient toModel(PatientRequestDTO patientRequestDTO) {
    Patient patient = new Patient();
    patient.setName(patientRequestDTO.getName());
    patient.setAddress(patientRequestDTO.getAddress());
    patient.setEmail(patientRequestDTO.getEmail());
    patient.setDateOfBirth(patientRequestDTO.getDateOfBirth());
    patient.setRegisteredDate(patientRequestDTO.getRegisteredDate());
    return patient;
  }
}