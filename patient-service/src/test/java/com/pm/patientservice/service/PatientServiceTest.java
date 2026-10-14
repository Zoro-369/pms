package com.pm.patientservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.InvalidQueryException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.grpc.BillingServiceGrpcClient;
import com.pm.patientservice.kafka.KafkaProducer;
import com.pm.patientservice.model.Patient;
import com.pm.patientservice.repository.PatientRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Pure unit tests: no Spring context, no database, no Kafka, no gRPC. */
@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Mock private PatientRepository patientRepository;
    @Mock private BillingServiceGrpcClient billingServiceGrpcClient;
    @Mock private KafkaProducer kafkaProducer;

    @InjectMocks private PatientService patientService;

    // ------------------------------------------------------------------ helpers

    private static PatientRequestDTO request(String name, String email) {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setName(name);
        dto.setEmail(email);
        dto.setAddress("1 Main St");
        dto.setDateOfBirth(LocalDate.of(1990, 1, 1));
        dto.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return dto;
    }

    private static Patient patient(UUID id, String name, String email) {
        Patient p = new Patient();
        p.setId(id);
        p.setName(name);
        p.setEmail(email);
        p.setAddress("1 Main St");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setRegisteredDate(LocalDate.of(2020, 5, 5));
        return p;
    }

    // ------------------------------------------------------------------ createPatient

    @Test
    void createPatient_savesThenCallsBillingThenPublishesEvent() {
        when(patientRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> {
            Patient saved = invocation.getArgument(0);
            saved.setId(ID);
            return saved;
        });

        PatientResponseDTO result =
                patientService.createPatient(request("Jane Doe", "jane@example.com"));

        InOrder order = inOrder(patientRepository, billingServiceGrpcClient, kafkaProducer);
        order.verify(patientRepository).save(any(Patient.class));
        order.verify(billingServiceGrpcClient)
                .createBillingAccount(ID.toString(), "Jane Doe", "jane@example.com");
        order.verify(kafkaProducer).sendEvent(any(Patient.class));

        assertThat(result.getId()).isEqualTo(ID.toString());
        assertThat(result.getName()).isEqualTo("Jane Doe");
        assertThat(result.getEmail()).isEqualTo("jane@example.com");
        assertThat(result.getDateOfBirth()).isEqualTo("1990-01-01");
    }

    @Test
    void createPatient_mapsAllRequestFieldsOntoTheSavedEntity() {
        when(patientRepository.existsByEmail(anyString())).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> {
            Patient saved = invocation.getArgument(0);
            saved.setId(ID);
            return saved;
        });

        patientService.createPatient(request("Jane Doe", "jane@example.com"));

        ArgumentCaptor<Patient> captor = ArgumentCaptor.forClass(Patient.class);
        verify(patientRepository).save(captor.capture());
        Patient saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Jane Doe");
        assertThat(saved.getAddress()).isEqualTo("1 Main St");
        assertThat(saved.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(saved.getRegisteredDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void createPatient_duplicateEmail_isRejectedBeforeAnySideEffect() {
        when(patientRepository.existsByEmail("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> patientService.createPatient(request("Jane", "jane@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(patientRepository, never()).save(any(Patient.class));
        verifyNoInteractions(billingServiceGrpcClient, kafkaProducer);
    }

    /**
     * Characterization test: pins the CURRENT, known-flawed behaviour. The patient is committed
     * before billing is called, so a billing failure leaves a saved patient with no billing account
     * and no event. This test should be changed when the transactional outbox and the billing
     * reconciler replace this flow.
     */
    @Test
    void createPatient_billingFailure_currentlyLeavesSavedPatientAndNoEvent() {
        when(patientRepository.existsByEmail(anyString())).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> {
            Patient saved = invocation.getArgument(0);
            saved.setId(ID);
            return saved;
        });
        when(billingServiceGrpcClient.createBillingAccount(anyString(), anyString(), anyString()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> patientService.createPatient(request("Jane", "jane@example.com")))
                .isInstanceOf(StatusRuntimeException.class);

        verify(patientRepository).save(any(Patient.class));
        verifyNoInteractions(kafkaProducer);
    }

    // ------------------------------------------------------------------ getPatient

    @Test
    void getPatient_returnsMappedDto() {
        when(patientRepository.findById(ID))
                .thenReturn(Optional.of(patient(ID, "Jane Doe", "jane@example.com")));

        PatientResponseDTO result = patientService.getPatient(ID);

        assertThat(result.getId()).isEqualTo(ID.toString());
        assertThat(result.getName()).isEqualTo("Jane Doe");
    }

    @Test
    void getPatient_unknownId_throwsNotFound() {
        when(patientRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.getPatient(ID))
                .isInstanceOf(PatientNotFoundException.class);
    }

    // ------------------------------------------------------------------ updatePatient

    @Test
    void updatePatient_updatesEditableFieldsButKeepsRegisteredDate() {
        Patient existing = patient(ID, "Old Name", "old@example.com");
        when(patientRepository.findById(ID)).thenReturn(Optional.of(existing));
        when(patientRepository.existsByEmailAndIdNot("new@example.com", ID)).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).then(returnsFirstArg());

        PatientResponseDTO result =
                patientService.updatePatient(ID, request("New Name", "new@example.com"));

        assertThat(existing.getName()).isEqualTo("New Name");
        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(existing.getRegisteredDate()).isEqualTo(LocalDate.of(2020, 5, 5));
        assertThat(result.getName()).isEqualTo("New Name");
    }

    @Test
    void updatePatient_unknownId_throwsNotFoundAndSavesNothing() {
        when(patientRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.updatePatient(ID, request("A", "a@example.com")))
                .isInstanceOf(PatientNotFoundException.class);

        verify(patientRepository, never()).save(any(Patient.class));
    }

    @Test
    void updatePatient_emailUsedByAnotherPatient_isRejected() {
        when(patientRepository.findById(ID))
                .thenReturn(Optional.of(patient(ID, "Jane", "jane@example.com")));
        when(patientRepository.existsByEmailAndIdNot("taken@example.com", ID)).thenReturn(true);

        assertThatThrownBy(() -> patientService.updatePatient(ID, request("Jane", "taken@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(patientRepository, never()).save(any(Patient.class));
    }

    // ------------------------------------------------------------------ deletePatient

    @Test
    void deletePatient_existing_deletesById() {
        when(patientRepository.existsById(ID)).thenReturn(true);

        patientService.deletePatient(ID);

        verify(patientRepository).deleteById(ID);
    }

    @Test
    void deletePatient_unknownId_throwsNotFoundAndDeletesNothing() {
        when(patientRepository.existsById(ID)).thenReturn(false);

        assertThatThrownBy(() -> patientService.deletePatient(ID))
                .isInstanceOf(PatientNotFoundException.class);

        verify(patientRepository, never()).deleteById(any(UUID.class));
    }

    // ------------------------------------------------------------------ getPatients (paging, sorting, search)

    @Test
    void getPatients_withoutSearch_usesFindAllAndAddsIdTieBreaker() {
        Pageable requested = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "email"));
        Page<Patient> repoPage = new PageImpl<>(
                List.of(patient(ID, "Jane Doe", "jane@example.com")), PageRequest.of(1, 5), 11);
        when(patientRepository.findAll(any(Pageable.class))).thenReturn(repoPage);

        PagedResponse<PatientResponseDTO> result = patientService.getPatients(null, requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientRepository).findAll(captor.capture());
        Pageable used = captor.getValue();
        assertThat(used.getPageNumber()).isEqualTo(1);
        assertThat(used.getPageSize()).isEqualTo(5);
        assertThat(used.getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()).toList())
                .containsExactly("email:DESC", "id:ASC");

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).getName()).isEqualTo("Jane Doe");
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    void getPatients_blankSearch_isTreatedAsNoSearch() {
        when(patientRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        patientService.getPatients("   ", PageRequest.of(0, 20, Sort.by("name")));

        verify(patientRepository).findAll(any(Pageable.class));
        verify(patientRepository, never())
                .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(any(), any(), any());
    }

    @Test
    void getPatients_withSearch_trimsTermAndSearchesNameOrEmail() {
        when(patientRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                eq("jane"), eq("jane"), any(Pageable.class))).thenReturn(Page.empty());

        patientService.getPatients("  jane ", PageRequest.of(0, 20, Sort.by("name")));

        verify(patientRepository).findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                eq("jane"), eq("jane"), any(Pageable.class));
        verify(patientRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void getPatients_unknownSortField_isRejectedWithoutTouchingTheDatabase() {
        Pageable requested = PageRequest.of(0, 20, Sort.by("password"));

        assertThatThrownBy(() -> patientService.getPatients(null, requested))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("Unsupported sort field 'password'")
                .hasMessageContaining("name");

        verifyNoInteractions(patientRepository);
    }

    @Test
    void getPatients_searchTextOver100Characters_isRejected() {
        String tooLong = "x".repeat(101);

        assertThatThrownBy(() -> patientService.getPatients(tooLong, PageRequest.of(0, 20, Sort.by("name"))))
                .isInstanceOf(InvalidQueryException.class);

        verifyNoInteractions(patientRepository);
    }
}