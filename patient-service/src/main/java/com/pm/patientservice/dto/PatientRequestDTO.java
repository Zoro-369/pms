package com.pm.patientservice.dto;

import com.pm.patientservice.dto.validators.CreatePatientValidationGroup;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class PatientRequestDTO {

  @NotBlank(message = "Name is required")
  @Size(max = 100, message = "Name cannot exceed 100 characters")
  private String name;

  @NotBlank(message = "Email is required")
  @Email(message = "Email should be valid")
  private String email;

  @NotBlank(message = "Address is required")
  private String address;

  // Typed dates: Jackson parses "yyyy-MM-dd" into LocalDate. A malformed value is rejected with 400
  // before it reaches the service, instead of LocalDate.parse() throwing and surfacing as a 500.
  @NotNull(message = "Date of birth is required")
  @Past(message = "Date of birth must be in the past")
  private LocalDate dateOfBirth;

  @NotNull(groups = CreatePatientValidationGroup.class, message = "Registered date is required")
  @PastOrPresent(message = "Registered date cannot be in the future")
  private LocalDate registeredDate;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  public LocalDate getRegisteredDate() {
    return registeredDate;
  }

  public void setRegisteredDate(LocalDate registeredDate) {
    this.registeredDate = registeredDate;
  }
}