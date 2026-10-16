package com.pm.patientservice.controller;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.dto.validators.CreatePatientValidationGroup;
import com.pm.patientservice.service.IdempotentPatientService;
import com.pm.patientservice.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.groups.Default;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/patients")
@Tag(name = "Patient", description = "API for managing Patients")
public class PatientController {

  private final PatientService patientService;
  private final IdempotentPatientService idempotentPatientService;

  public PatientController(PatientService patientService,
                           IdempotentPatientService idempotentPatientService) {
    this.patientService = patientService;
    this.idempotentPatientService = idempotentPatientService;
  }

  @GetMapping
  @Operation(summary = "Get Patients (paged, sortable, searchable)",
          description = "page (0-based), size (default 20, max 100), sort=field,asc|desc "
                  + "(name, email, dateOfBirth, registeredDate), search = text in name or email")
  public ResponseEntity<PagedResponse<PatientResponseDTO>> getPatients(
          @RequestParam(required = false) String search,
          @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
    return ResponseEntity.ok(patientService.getPatients(search, pageable));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a Patient by id")
  public ResponseEntity<PatientResponseDTO> getPatient(@PathVariable UUID id) {
    return ResponseEntity.ok(patientService.getPatient(id));
  }

  @PostMapping
  @Operation(summary = "Create a new Patient",
          description = "Send an Idempotency-Key header (1-255 printable ASCII characters) to make "
                  + "retries safe: repeating the same request returns the original response with an "
                  + "Idempotent-Replayed: true header and creates nothing new.")
  public ResponseEntity<PatientResponseDTO> createPatient(
          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
          @Validated({Default.class, CreatePatientValidationGroup.class})
          @RequestBody PatientRequestDTO patientRequestDTO) {

    if (idempotencyKey == null) {
      return created(patientService.createPatient(patientRequestDTO), false);
    }

    IdempotentPatientService.Result result =
            idempotentPatientService.createPatient(idempotencyKey, patientRequestDTO);
    return created(result.patient(), result.replayed());
  }

  // Location uses the public path exposed by the gateway (/api/v1/patients), not this service's
  // internal /patients path, because clients only ever see the gateway.
  private static ResponseEntity<PatientResponseDTO> created(PatientResponseDTO patient,
                                                            boolean replayed) {
    URI location = URI.create("/api/v1/patients/" + patient.getId());
    ResponseEntity.BodyBuilder builder = ResponseEntity.created(location);
    if (replayed) {
      builder.header("Idempotent-Replayed", "true");
    }
    return builder.body(patient);
  }

  @PutMapping("/{id}")
  @Operation(summary = "Update a Patient")
  public ResponseEntity<PatientResponseDTO> updatePatient(@PathVariable UUID id,
                                                          @Validated({Default.class}) @RequestBody PatientRequestDTO patientRequestDTO) {

    PatientResponseDTO patientResponseDTO = patientService.updatePatient(id,
            patientRequestDTO);

    return ResponseEntity.ok().body(patientResponseDTO);
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a Patient")
  public ResponseEntity<Void> deletePatient(@PathVariable UUID id) {
    patientService.deletePatient(id);
    return ResponseEntity.noContent().build();
  }
}