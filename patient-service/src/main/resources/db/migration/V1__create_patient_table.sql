-- Initial schema for patient-service (replaces Hibernate ddl-auto=update and data.sql CREATE TABLE).
CREATE TABLE patient
(
    id              UUID         PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    address         VARCHAR(255) NOT NULL,
    date_of_birth   DATE         NOT NULL,
    registered_date DATE         NOT NULL,
    CONSTRAINT uq_patient_email UNIQUE (email)
);