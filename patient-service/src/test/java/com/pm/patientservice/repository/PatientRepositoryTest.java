package com.pm.patientservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pm.patientservice.model.Patient;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Repository tests against a REAL PostgreSQL 17 started by Testcontainers (Docker must be running).
 * Flyway builds the schema from db/migration and Hibernate only validates it, exactly like
 * production, so these tests also catch drift between the entities and the migrations.
 * Dev seed data (db/seed) is deliberately not loaded, so every test starts from an empty table.
 * Each test runs in a transaction that is rolled back afterwards.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PatientRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired private PatientRepository repository;
    @Autowired private TestEntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------ helpers

    private static Patient newPatient(String name, String email) {
        return newPatient(name, email, LocalDate.of(1990, 1, 1));
    }

    private static Patient newPatient(String name, String email, LocalDate dateOfBirth) {
        Patient p = new Patient();
        p.setName(name);
        p.setEmail(email);
        p.setAddress("1 Test St");
        p.setDateOfBirth(dateOfBirth);
        p.setRegisteredDate(LocalDate.of(2024, 1, 1));
        return p;
    }

    private Patient save(String name, String email) {
        return repository.saveAndFlush(newPatient(name, email));
    }

    private Page<Patient> search(String term, Pageable pageable) {
        return repository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                term, term, pageable);
    }

    private static List<String> names(Page<Patient> page) {
        return page.getContent().stream().map(Patient::getName).toList();
    }

    // ------------------------------------------------------------------ schema and mapping

    @Test
    void schemaIsBuiltByFlywayMigrations() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version = '1'",
                Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void save_persistsAllColumnsAndGeneratesUuid() {
        Patient saved = save("Jane Doe", "jane@example.com");
        em.clear(); // force the next read to hit the database, not the persistence context

        Patient loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getName()).isEqualTo("Jane Doe");
        assertThat(loaded.getEmail()).isEqualTo("jane@example.com");
        assertThat(loaded.getAddress()).isEqualTo("1 Test St");
        assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(loaded.getRegisteredDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void save_duplicateEmail_violatesTheNamedUniqueConstraint() {
        save("Jane", "jane@example.com");

        // This is the exact exception GlobalExceptionHandler maps to HTTP 409.
        assertThatThrownBy(() -> repository.saveAndFlush(newPatient("Jane Two", "jane@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_patient_email");
    }

    // ------------------------------------------------------------------ existsBy...

    @Test
    void existsByEmail_matchesTheExactEmail() {
        save("Jane", "jane@example.com");

        assertThat(repository.existsByEmail("jane@example.com")).isTrue();
        assertThat(repository.existsByEmail("other@example.com")).isFalse();
    }

    /**
     * Characterization test: pins a REAL flaw found while writing these tests. Email uniqueness is
     * case-sensitive, so "JANE@example.com" and "jane@example.com" are treated as different patients.
     * Fix later with a V2 migration (unique index on lower(email)) and lower-casing in the service.
     */
    @Test
    void email_uniquenessIsCurrentlyCaseSensitive() {
        save("Jane", "jane@example.com");

        assertThat(repository.existsByEmail("JANE@EXAMPLE.COM")).isFalse();
        assertThatCode(() -> repository.saveAndFlush(newPatient("Jane Two", "JANE@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void existsByEmailAndIdNot_ignoresThePatientsOwnRow() {
        Patient a = save("A", "a@example.com");
        Patient b = save("B", "b@example.com");

        assertThat(repository.existsByEmailAndIdNot("a@example.com", a.getId())).isFalse();
        assertThat(repository.existsByEmailAndIdNot("a@example.com", b.getId())).isTrue();
        assertThat(repository.existsByEmailAndIdNot("nobody@example.com", a.getId())).isFalse();
    }

    // ------------------------------------------------------------------ search

    @Test
    void search_matchesNameCaseInsensitively() {
        save("Alice Johnson", "alice@example.com");
        save("Bob Brown", "bob@example.com");

        Page<Patient> result = search("JOHNSON", PageRequest.of(0, 10, Sort.by("name")));

        assertThat(names(result)).containsExactly("Alice Johnson");
    }

    @Test
    void search_matchesEmailCaseInsensitively() {
        save("Alice Johnson", "alice@example.com");
        save("Bob Brown", "bob@example.com");

        Page<Patient> result = search("BOB@", PageRequest.of(0, 10, Sort.by("name")));

        assertThat(names(result)).containsExactly("Bob Brown");
    }

    @Test
    void search_withNoMatch_returnsEmptyPage() {
        save("Alice Johnson", "alice@example.com");

        Page<Patient> result = search("zzz-no-match", PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void search_treatsPercentAndUnderscoreAsLiteralCharacters() {
        save("100% Real", "percent@example.com");
        save("Plain Name", "plain@example.com");
        save("a_b", "under@example.com");
        save("axb", "axb@example.com");

        // If % were a wildcard, searching "%" would return every patient.
        assertThat(names(search("%", PageRequest.of(0, 10, Sort.by("name")))))
                .containsExactly("100% Real");
        // If _ were a wildcard, searching "a_b" would also match "axb".
        assertThat(names(search("a_b", PageRequest.of(0, 10, Sort.by("name")))))
                .containsExactly("a_b");
    }

    @Test
    void search_isPaged() {
        save("Pager One", "pager1@example.com");
        save("Pager Two", "pager2@example.com");
        save("Pager Three", "pager3@example.com");
        save("Someone Else", "else@example.com");

        Page<Patient> first = search("pager", PageRequest.of(0, 2, Sort.by("name")));
        Page<Patient> second = search("pager", PageRequest.of(1, 2, Sort.by("name")));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(second.isLast()).isTrue();
    }

    // ------------------------------------------------------------------ paging and sorting

    @Test
    void findAll_sortsByNameAscending() {
        save("Charlie", "c@example.com");
        save("Alice", "a@example.com");
        save("Bob", "b@example.com");

        Page<Patient> page = repository.findAll(PageRequest.of(0, 10, Sort.by("name").ascending()));

        assertThat(names(page)).containsExactly("Alice", "Bob", "Charlie");
    }

    @Test
    void findAll_sortsByDateOfBirthDescending_provingFieldToColumnMapping() {
        repository.saveAndFlush(newPatient("Old", "old@example.com", LocalDate.of(1950, 1, 1)));
        repository.saveAndFlush(newPatient("Young", "young@example.com", LocalDate.of(2010, 1, 1)));
        repository.saveAndFlush(newPatient("Middle", "middle@example.com", LocalDate.of(1990, 1, 1)));

        Page<Patient> page =
                repository.findAll(PageRequest.of(0, 10, Sort.by("dateOfBirth").descending()));

        assertThat(names(page)).containsExactly("Young", "Middle", "Old");
    }

    @Test
    void pagingWithIdTieBreaker_visitsEveryRowExactlyOnce() {
        for (int i = 1; i <= 5; i++) {
            save("Same Name", "same" + i + "@example.com");
        }
        Sort stable = Sort.by("name").and(Sort.by("id"));

        Set<UUID> seen = new HashSet<>();
        int[] expectedSizes = {2, 2, 1};
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            Page<Patient> page = repository.findAll(PageRequest.of(pageNumber, 2, stable));
            assertThat(page.getContent()).hasSize(expectedSizes[pageNumber]);
            page.getContent().forEach(p -> seen.add(p.getId()));
        }

        // Five patients share one name; the id tie-breaker keeps pages disjoint and complete.
        assertThat(seen).hasSize(5);
    }
}