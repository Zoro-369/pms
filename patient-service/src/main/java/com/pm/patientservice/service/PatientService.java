package com.pm.patientservice.service;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.InvalidQueryException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import com.pm.patientservice.mapper.PatientMapper;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.PatientRepository;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

  // Entity field names clients may sort by. Anything else is rejected with 400 instead of
  // reaching Hibernate and failing with a 500.
  private static final Set<String> SORTABLE_FIELDS =
          Set.of("name", "email", "dateOfBirth", "registeredDate");
  private static final int MAX_SEARCH_LENGTH = 100;

  private final PatientRepository patientRepository;
  private final BillingServiceGrpcClient billingServiceGrpcClient;
  private final OutboxService outboxService;

  public PatientService(PatientRepository patientRepository,
                        BillingServiceGrpcClient billingServiceGrpcClient,
                        OutboxService outboxService) {
    this.patientRepository = patientRepository;
    this.billingServiceGrpcClient = billingServiceGrpcClient;
    this.outboxService = outboxService;
  }

  @Transactional(readOnly = true)
  public PagedResponse<PatientResponseDTO> getPatients(String search, Pageable pageable) {
    Pageable safePageable = withValidatedSort(pageable);

    Page<Patient> page;
    if (search == null || search.isBlank()) {
      page = patientRepository.findAll(safePageable);
    } else {
      String term = search.trim();
      if (term.length() > MAX_SEARCH_LENGTH) {
        throw new InvalidQueryException(
                "Search text cannot exceed " + MAX_SEARCH_LENGTH + " characters");
      }
      page = patientRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
              term, term, safePageable);
    }

    return PagedResponse.from(page.map(PatientMapper::toDTO));
  }

  private Pageable withValidatedSort(Pageable pageable) {
    for (Sort.Order order : pageable.getSort()) {
      if (!SORTABLE_FIELDS.contains(order.getProperty())) {
        throw new InvalidQueryException("Unsupported sort field '" + order.getProperty()
                + "'. Allowed: " + String.join(", ", new TreeSet<>(SORTABLE_FIELDS)));
      }
    }
    // Tie-breaker on id so pages are deterministic when many rows share the same sort value.
    Sort stableSort = pageable.getSort().and(Sort.by("id"));
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), stableSort);
  }

  public PatientResponseDTO getPatient(UUID id) {
    return patientRepository.findById(id)
            .map(PatientMapper::toDTO)
            .orElseThrow(() -> new PatientNotFoundException("Patient not found with ID: " + id));
  }

  /**
   * One database transaction covers the patient row and its outbox event row, so they commit or
   * roll back together. The event is NOT sent to Kafka here; the outbox relay publishes it after
   * commit. The billing call is the last step before commit: if billing fails, the exception
   * rolls back the patient and the outbox row, so no half-created patient is left behind.
   * (Holding a transaction open across a remote call is a temporary trade-off; the billing
   * reconciler later replaces this call with an asynchronous step.)
   */
  @Transactional
  public PatientResponseDTO createPatient(PatientRequestDTO patientRequestDTO) {
    if (patientRepository.existsByEmail(patientRequestDTO.getEmail())) {
      throw new EmailAlreadyExistsException(
              "A patient with this email already exists: " + patientRequestDTO.getEmail());
    }

    // saveAndFlush so a unique-constraint race on email fails here, before any later step runs
    Patient newPatient = patientRepository.saveAndFlush(
            PatientMapper.toModel(patientRequestDTO));

    outboxService.recordPatientCreated(newPatient);

    billingServiceGrpcClient.createBillingAccount(newPatient.getId().toString(),
            newPatient.getName(), newPatient.getEmail());

    return PatientMapper.toDTO(newPatient);
  }

  public PatientResponseDTO updatePatient(UUID id,
                                          PatientRequestDTO patientRequestDTO) {

    Patient patient = patientRepository.findById(id).orElseThrow(
            () -> new PatientNotFoundException("Patient not found with ID: " + id));

    if (patientRepository.existsByEmailAndIdNot(patientRequestDTO.getEmail(),
            id)) {
      throw new EmailAlreadyExistsException(
              "A patient with this email already exists: " + patientRequestDTO.getEmail());
    }

    patient.setName(patientRequestDTO.getName());
    patient.setAddress(patientRequestDTO.getAddress());
    patient.setEmail(patientRequestDTO.getEmail());
    patient.setDateOfBirth(patientRequestDTO.getDateOfBirth());

    Patient updatedPatient = patientRepository.save(patient);
    return PatientMapper.toDTO(updatedPatient);
  }

  public void deletePatient(UUID id) {
    if (!patientRepository.existsById(id)) {
      throw new PatientNotFoundException("Patient not found with ID: " + id);
    }
    patientRepository.deleteById(id);
  }
}