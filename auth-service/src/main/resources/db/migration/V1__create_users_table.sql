-- Initial schema for auth-service (replaces Hibernate ddl-auto=update and data.sql CREATE TABLE).
CREATE TABLE users
(
    id       UUID         PRIMARY KEY,
    email    VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(50)  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);