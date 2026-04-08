# Java Spring Microservices

A production-style **microservices-based system** built using **Spring Boot, Spring Cloud, Docker, and modern backend technologies**. This project demonstrates how to design, build, and deploy scalable distributed systems following real-world enterprise patterns.
---

## 📚 Table of Contents

* [Overview](#-overview)
* [Architecture](#-architecture)
* [Services](#-services)
* [Tech Stack](#-tech-stack)
* [Getting Started](#-getting-started)
* [Environment Variables](#-environment-variables)
* [Running the Project](#-running-the-project)
* [API Communication](#-api-communication)
* [Future Improvements](#-future-improvements)

---

## 🧠 Overview

This project demonstrates a **microservices architecture**, where the system is split into multiple independent services that communicate with each other.

Each service:

* Runs independently
* Has its own database (if required)
* Communicates via **REST or gRPC**
* Is containerized using Docker

This approach improves:

* Scalability
* Maintainability
* Fault isolation

---

## 🏗️ Architecture

The system follows a typical **microservices architecture pattern**:

```
Client → API Gateway → Microservices → Database / Messaging System
```

### Key Components:

* **API Gateway** – Single entry point for all requests
* **Microservices** – Business logic split into services
* **Kafka** – Event-driven communication
* **gRPC** – High-performance service-to-service communication
* **Docker** – Containerization
* **PostgreSQL** – Database

---

## 🔧 Services

The project consists of the following services:

### 🧑‍⚕️ Patient Service

* Manages patient data
* Uses PostgreSQL
* Publishes events to Kafka

### 💳 Billing Service

* Handles billing operations
* Communicates using **gRPC**

### 🔐 Auth Service

* Manages authentication & authorization

### 📊 Analytics Service

* Processes and analyzes system data

### 🌐 API Gateway

* Routes incoming requests to appropriate services

### 📨 API Requests Module

* Contains shared request/response models

### ⚙️ Infrastructure

* Contains Docker, configs, and setup files

### 🧪 Integration Tests

* End-to-end testing across services

> These modules are visible in the repository structure. ([GitHub][1])

---

## 🛠️ Tech Stack

* **Java**
* **Spring Boot**
* **Spring Cloud**
* **Apache Kafka**
* **gRPC**
* **Docker & Docker Compose**
* **PostgreSQL**
* **Maven**

---

## ⚡ Getting Started

### Prerequisites

Make sure you have installed:

* Java 17+
* Maven
* Docker & Docker Compose
* Git

---

## 🔑 Environment Variables

Example for **Patient Service**:

```bash
JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
SPRING_DATASOURCE_PASSWORD=password
SPRING_DATASOURCE_URL=jdbc:postgresql://patient-service-db:5432/db
SPRING_DATASOURCE_USERNAME=admin_user
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_SQL_INIT_MODE=always
```

> These variables configure database, Kafka, and debugging. ([GitHub][1])

---

## ▶️ Running the Project

### 1. Clone the repository

```bash
git clone https://github.com/zoro-369/java-spring-microservices.git
cd java-spring-microservices
```

### 2. Start services using Docker

```bash
docker-compose up --build
```

### 3. Access services

* API Gateway → `http://localhost:<port>`
* Individual services → defined in docker-compose

---

## 🔄 API Communication

This system uses multiple communication patterns:

### REST (Synchronous)

* Used between client and API Gateway

### gRPC (High-performance)

* Used between services (e.g., Billing Service)

```xml
<!-- Example dependency -->
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-netty-shaded</artifactId>
</dependency>
```

### Kafka (Event-driven)

* Used for asynchronous communication

---

## 🚀 Features

* Microservices-based architecture
* API Gateway routing
* Service-to-service communication (gRPC)
* Event-driven messaging (Kafka)
* Containerized deployment (Docker)
* Scalable and modular design

---

## 🔮 Future Improvements

* Add service discovery (Eureka)
* Implement distributed tracing (Zipkin)
* Add monitoring (Prometheus + Grafana)
* Kubernetes deployment
* CI/CD pipelines

---

## 🤝 Contributing

Contributions are welcome!

1. Fork the repo
2. Create a new branch
3. Make your changes
4. Submit a PR

---

## 📜 License

This project is for educational purposes based on course material.

