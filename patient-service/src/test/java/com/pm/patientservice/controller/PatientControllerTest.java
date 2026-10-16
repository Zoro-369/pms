package com.pm.patientservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.patientservice.dto.PagedResponse;
import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.exception.EmailAlreadyExistsException;
import com.pm.patientservice.exception.IdempotencyKeyReusedException;
import com.pm.patientservice.exception.IdempotencyRequestInProgressException;
import com.pm.patientservice.exception.InvalidQueryException;
import com.pm.patientservice.exception.PatientNotFoundException;
import com.pm.patientservice.service.IdempotentPatientService;
import com.pm.patientservice.service.PatientService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: real controller, real validation, real JSON handling and the real
 * GlobalExceptionHandler, with the service mocked. No database, Kafka or gRPC involved.
 */
@WebMvcTest(PatientController.class)
class PatientControllerTest {

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PatientService patientService;
    @MockitoBean private IdempotentPatientService idempotentPatientService;

    // ------------------------------------------------------------------ helpers

    private static PatientResponseDTO dto() {
        PatientResponseDTO dto = new PatientResponseDTO();
        dto.setId(ID.toString());
        dto.setName("Jane Doe");
        dto.setEmail("jane@example.com");
        dto.setAddress("1 Main St");
        dto.setDateOfBirth("1990-01-01");
        return dto;
    }

    /** Builds a JSON body, omitting any field passed as null. */
    private String body(String name, String email, String address, String dob, String registered)
            throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        if (name != null) map.put("name", name);
        if (email != null) map.put("email", email);
        if (address != null) map.put("address", address);
        if (dob != null) map.put("dateOfBirth", dob);
        if (registered != null) map.put("registeredDate", registered);
        return objectMapper.writeValueAsString(map);
    }

    private String validBody() throws Exception {
        return body("Jane Doe", "jane@example.com", "1 Main St", "1990-01-01", "2024-01-01");
    }

    private static PagedResponse<PatientResponseDTO> onePage() {
        return new PagedResponse<>(List.of(dto()), 0, 20, 1, 1, true, true);
    }

    // ------------------------------------------------------------------ GET /patients

    @Test
    void getPatients_returnsPagedEnvelopeAndUsesDefaults() throws Exception {
        when(patientService.getPatients(any(), any(Pageable.class))).thenReturn(onePage());

        mockMvc.perform(get("/patients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Jane Doe"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.first").value(true));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientService).getPatients(isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void getPatients_passesPageSizeSortAndSearchToTheService() throws Exception {
        when(patientService.getPatients(any(), any(Pageable.class))).thenReturn(onePage());

        mockMvc.perform(get("/patients")
                        .param("page", "2").param("size", "5").param("sort", "email,desc").param("search", "jane"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientService).getPatients(eq("jane"), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort().getOrderFor("email").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void getPatients_capsPageSizeAtConfiguredMaximum() throws Exception {
        when(patientService.getPatients(any(), any(Pageable.class))).thenReturn(onePage());

        mockMvc.perform(get("/patients").param("size", "1000")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientService).getPatients(any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void getPatients_invalidQuery_returns400ProblemDetail() throws Exception {
        when(patientService.getPatients(any(), any(Pageable.class)))
                .thenThrow(new InvalidQueryException("Unsupported sort field 'password'. Allowed: name"));

        mockMvc.perform(get("/patients").param("sort", "password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid query"))
                .andExpect(jsonPath("$.detail").value(containsString("Unsupported sort field")));
    }

    // ------------------------------------------------------------------ GET /patients/{id}

    @Test
    void getPatient_returns200WithPatient() throws Exception {
        when(patientService.getPatient(ID)).thenReturn(dto());

        mockMvc.perform(get("/patients/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    void getPatient_unknownId_returns404ProblemDetail() throws Exception {
        when(patientService.getPatient(ID)).thenThrow(new PatientNotFoundException("not found"));

        mockMvc.perform(get("/patients/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Patient not found"));
    }

    @Test
    void getPatient_malformedId_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(get("/patients/not-a-uuid")).andExpect(status().isBadRequest());

        verifyNoInteractions(patientService);
    }

    // ------------------------------------------------------------------ POST /patients

    @Test
    void createPatient_valid_returns201WithLocationAndParsesTypedDates() throws Exception {
        when(patientService.createPatient(any(PatientRequestDTO.class))).thenReturn(dto());

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/patients/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()));

        ArgumentCaptor<PatientRequestDTO> captor = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(patientService).createPatient(captor.capture());
        assertThat(captor.getValue().getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(captor.getValue().getRegisteredDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void createPatient_invalidFields_returns400WithFieldErrors() throws Exception {
        String invalid = body("", "not-an-email", null, null, null);

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.address").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists())
                .andExpect(jsonPath("$.errors.registeredDate").exists());

        verifyNoInteractions(patientService);
    }

    @Test
    void createPatient_missingRegisteredDate_isRejectedOnCreate() throws Exception {
        String noRegistered = body("Jane Doe", "jane@example.com", "1 Main St", "1990-01-01", null);

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(noRegistered))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registeredDate").value("Registered date is required"));
    }

    @Test
    void createPatient_malformedDate_returns400NotAServerError() throws Exception {
        String badDate = body("Jane Doe", "jane@example.com", "1 Main St", "31-12-1995", "2024-01-01");

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(badDate))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(patientService);
    }

    @Test
    void createPatient_birthDateInFuture_isRejected() throws Exception {
        String future = LocalDate.now().plusDays(1).toString();
        String invalid = body("Jane Doe", "jane@example.com", "1 Main St", future, "2024-01-01");

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists());
    }

    @Test
    void createPatient_duplicateEmail_returns409() throws Exception {
        when(patientService.createPatient(any(PatientRequestDTO.class)))
                .thenThrow(new EmailAlreadyExistsException("exists"));

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate email"));
    }

    // ------------------------------------------------------------------ POST with Idempotency-Key

    @Test
    void createPatient_withIdempotencyKey_usesIdempotentServiceAndIsNotMarkedReplayed()
            throws Exception {
        when(idempotentPatientService.createPatient(eq("key-1"), any(PatientRequestDTO.class)))
                .thenReturn(new IdempotentPatientService.Result(dto(), false));

        mockMvc.perform(post("/patients").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(header().string("Location", "/api/v1/patients/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()));

        verify(idempotentPatientService).createPatient(eq("key-1"), any(PatientRequestDTO.class));
        verifyNoInteractions(patientService);
    }

    @Test
    void createPatient_replayedResponse_returnsSameBodyWithReplayHeader() throws Exception {
        when(idempotentPatientService.createPatient(eq("key-1"), any(PatientRequestDTO.class)))
                .thenReturn(new IdempotentPatientService.Result(dto(), true));

        mockMvc.perform(post("/patients").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/patients/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    void createPatient_keyReusedWithDifferentBody_returns422() throws Exception {
        when(idempotentPatientService.createPatient(eq("key-1"), any(PatientRequestDTO.class)))
                .thenThrow(new IdempotencyKeyReusedException("different body"));

        mockMvc.perform(post("/patients").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"));
    }

    @Test
    void createPatient_requestStillInProgress_returns409() throws Exception {
        when(idempotentPatientService.createPatient(eq("key-1"), any(PatientRequestDTO.class)))
                .thenThrow(new IdempotencyRequestInProgressException("in progress"));

        mockMvc.perform(post("/patients").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Request in progress"));
    }

    @Test
    void createPatient_invalidIdempotencyKey_returns400() throws Exception {
        when(idempotentPatientService.createPatient(eq("bad key"), any(PatientRequestDTO.class)))
                .thenThrow(new InvalidQueryException("Idempotency-Key must be 1-255 printable ASCII"));

        mockMvc.perform(post("/patients").header("Idempotency-Key", "bad key")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
    }

    @Test
    void createPatient_withoutKey_doesNotTouchTheIdempotencyService() throws Exception {
        when(patientService.createPatient(any(PatientRequestDTO.class))).thenReturn(dto());

        mockMvc.perform(post("/patients").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        verifyNoInteractions(idempotentPatientService);
    }

    @Test
    void createPatient_invalidBodyWithKey_failsValidationBeforeTheKeyIsConsumed() throws Exception {
        String invalid = body("", "not-an-email", null, null, null);

        mockMvc.perform(post("/patients").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(idempotentPatientService, patientService);
    }

    // ------------------------------------------------------------------ PUT /patients/{id}

    @Test
    void updatePatient_doesNotRequireRegisteredDate() throws Exception {
        when(patientService.updatePatient(eq(ID), any(PatientRequestDTO.class))).thenReturn(dto());
        String noRegistered = body("Jane Doe", "jane@example.com", "1 Main St", "1990-01-01", null);

        mockMvc.perform(put("/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(noRegistered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void updatePatient_invalidEmail_returns400() throws Exception {
        String invalid = body("Jane Doe", "bad", "1 Main St", "1990-01-01", null);

        mockMvc.perform(put("/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        verifyNoInteractions(patientService);
    }

    @Test
    void updatePatient_unknownId_returns404() throws Exception {
        when(patientService.updatePatient(eq(ID), any(PatientRequestDTO.class)))
                .thenThrow(new PatientNotFoundException("not found"));

        mockMvc.perform(put("/patients/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ DELETE /patients/{id}

    @Test
    void deletePatient_returns204() throws Exception {
        mockMvc.perform(delete("/patients/{id}", ID)).andExpect(status().isNoContent());

        verify(patientService).deletePatient(ID);
    }

    @Test
    void deletePatient_unknownId_returns404() throws Exception {
        doThrow(new PatientNotFoundException("not found")).when(patientService).deletePatient(ID);

        mockMvc.perform(delete("/patients/{id}", ID)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ error-handling safety nets

    @Test
    void unexpectedException_returnsGeneric500WithoutLeakingInternals() throws Exception {
        when(patientService.getPatient(ID))
                .thenThrow(new RuntimeException("connection to db-secret-host failed"));

        mockMvc.perform(get("/patients/{id}", ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void unknownPath_returns404_notTurnedIntoA500ByTheCatchAllHandler() throws Exception {
        mockMvc.perform(get("/no-such-endpoint")).andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethod_returns405_notTurnedIntoA500ByTheCatchAllHandler() throws Exception {
        mockMvc.perform(patch("/patients")).andExpect(status().isMethodNotAllowed());
    }
}