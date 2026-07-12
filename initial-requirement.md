# Software Requirements Specification (SRS)
## Project: Multi-Tenant, Extensible Omni-Industry Scheduling Framework

---

## 1. Architectural Design & Philosophy

The primary objective is to build a highly abstract, multi-tenant scheduling framework where the core booking mechanics are fully isolated from industry-specific business variables. The system will handle common cross-cutting concerns (concurrency, location processing, time validation, notifications, and auth) generically, allowing tenants to inject customized business models via dynamic data fields.

### Core Architecture Principles
*   **Domain Abstraction:** The system must never use industry-specific terminology in its database tables or core APIs. Entities are classified as `Resource` (e.g., doctor, mechanic, service booth) and `Service` (e.g., dental checkup, oil change, consultation).
*   **Metadata Flexibility:** To support highly variable domain data (e.g., patient histories for medical clinics, vehicle data for auto shops), extensible entities must use a PostgreSQL `JSONB` data structure.
*   **Multi-Tenancy Isolation:** Data isolation must be enforced at the API and database levels using a mandatory `tenant_id` key on all persistent entities.

---

## 2. Technical Framework Stack

| Architectural Layer | Selected Technology | Role & Responsibility |
| :--- | :--- | :--- |
| **User Interface** | React Next.js (App Router) | High-performance client-facing booking interface and multi-tenant admin control panels. |
| **Backend Core Engine** | Java Spring Boot 3.x | Secure REST API provider, transactional boundaries, validation engine, and state management. |
| **Primary Database** | PostgreSQL 15+ | ACID-compliant storage utilizing `JSONB` primitives for schemaless domain extensions. |
| **Event Streaming Mesh**| Apache Kafka | High-throughput distributed log for asynchronous notification processing, audit logging, and downstream synchronization. |
| **State Caching / Lock** | Redis (Optional Ecosystem)| For high-throughput distributed locking patterns if horizontal scaling exceeds DB capacity. |

---

## 3. Detailed Functional Requirements (FR)

### 3.1 Authentication & Identity Access Management (IAM)
*   **FR-1.1 Dual-Channel Identifier Resolution:** The login engine must accept either an email address or a phone number as a valid user identification format.
*   **FR-1.2 Dynamic Verification Strategy:** The authentication module must dynamically choose the validation dispatch strategy based on the identifier provided:
    *   *Email Identification:* Dispatches a secure Magic Link or an alphanumeric One-Time Password (OTP) via SMTP/SES.
    *   *SMS Identification:* Dispatches an alphanumeric OTP via an integrated cellular gateway (e.g., Twilio).
*   **FR-1.3 Verification Lifecycle:** Generated OTP tokens must expire exactly 5 minutes after creation and become invalid after the first failed validation attempt.
*   **FR-1.4 JWT Claims Construction:** Upon successful token verification, the backend must return a JSON Web Token (JWT) encapsulating tenant security variables (`tenant_id`, `user_id`, and `role_claims`).

### 3.2 Location & Abstract Resource Configurator (Admin Portal)
*   **FR-2.1 Branch Topology Configuration:** The admin panel must provide components to configure operational branches, capturing geographical coordinates (latitude/longitude), localized time zones, and structural physical addresses.
*   **FR-2.2 Generic Resource Asset Assignment:** Admins must be capable of registering physical or human assets (`Resource`) to a specific location branch with an associated availability schedule.
*   **FR-2.3 JSON Schema Form Builder:** The Next.js admin app must provide a system to define custom intake form schemas using a standard JSON framework. These fields append directly to the reservation data structure via the `JSONB` column on checkout.

### 3.3 Dynamic Slot Generation & Scheduling Rules Engine
*   **FR-3.1 Operating Matrix Calculation:** The system must generate operational time blocks dynamically by evaluating a resource’s base shift, overlapping break patterns, and global branch holidays.
*   **FR-3.2 Dynamic Slotting Engine:** Time slots must not be statically written to the database. Instead, the engine must compute open intervals on demand by subtracting confirmed booking durations and mandatory service buffers from the baseline operating matrix.
*   **FR-3.3 Pre/Post Buffer Padding:** The booking engine must automatically enforce structural padding rules (e.g., a 15-minute cleanup buffer post-appointment) to protect resource assets from immediate back-to-back scheduling.

### 3.4 Concurrency Processing & Reservation Engine
*   **FR-4.1 Anti-Race-Condition Lock:** The backend engine must prevent duplicate slot assignments by using an explicit pessimistic locking mechanism (`SELECT ... FOR UPDATE` in Spring Data JPA) or a atomic Redis-based distributed lock during the checkout sequence.
*   **FR-4.2 Temporary State Isolation:** On slot selection, the resource state must change from `AVAILABLE` to `PENDING_HOLD` for a maximum duration of 10 minutes while processing payment or form inputs.
*   **FR-4.3 Automatic Garbage Collection:** A transactional Spring Boot scheduler must continuously scan for expired `PENDING_HOLD` records, automatically reverting unfinalized bookings to `AVAILABLE` without system deadlocks.

### 3.5 Asynchronous Event Mesh (Apache Kafka Integration)
*   **FR-5.1 Structural Lifecycle Emission:** The booking service must immediately broadcast a structured payload to a dedicated Kafka topic (e.g., `tenant.bookings.lifecycle`) upon any terminal state transition (`HELD`, `CONFIRMED`, `CANCELLED`).
*   **FR-5.2 Transactional Outbox Guarantee:** To prevent distributed transaction failures, the Spring Boot application must write the business state changes and the matching event payload inside a single ACID database transaction using an `outbox` table pattern. A dedicated reader then streams events out to Kafka reliably.
*   **FR-5.3 Asynchronous Consumer Splitting:** Separate, decoupled consumer services must subscribe to Kafka topics to process heavy, non-blocking side effects:
    *   *Notification System:* Listens for events to send emails or texts.
    *   *Audit Ledger System:* Captures immutable change history to comply with industry-specific laws (such as HIPAA).

---

## 4. Non-Functional Requirements (NFR)

### 4.1 Performance & Scalability
*   **NFR-1.1 Processing Throughput:** The foundational framework must process at least 500 concurrent reservation transactions per minute without degrading performance or dropping requests.
*   **NFR-1.2 API Discovery Latency:** The location discovery and slot-generation endpoints must return payload responses in less than 300 milliseconds under standard target operating conditions.
*   **NFR-1.3 Query Optimization:** The PostgreSQL database layer must establish compound B-tree indexes across the key fields (`tenant_id`, `location_id`, `start_time`) to keep slot calculations swift as the data scales.

### 4.2 Security, Auditability & Resilience
*   **NFR-2.1 Idempotent Event Delivery:** All downstream Kafka consumers must track message keys via an idempotent checker to prevent duplicate actions (like sending twin notification texts) if network drops cause Kafka to re-deliver a message.
*   **NFR-2.2 Payload Contracts:** All data models sent across the Kafka event mesh must be strictly validated using a schema registry layer (such as Apache Avro) to ensure backward compatibility as system modules evolve.

---

## 5. Architectural Entity Relationship & Strategy Concept

[ Next.js User Interface ] <--- JWT Authentication (Email/SMS OTP)|| (REST API Request)v[ Spring Boot Core Engine ] --- (Pessimistic / Distributed Lock Applied)|                 || (ACID Write)    | (Transactional Outbox Write)v                 v[ PostgreSQL DB ]  [ Outbox Table ] --(CDC / Kafka Connect)--> [ Apache Kafka ]tenant_id                                                          |location_id                                                        +---> [ Notification Service ]resource_id                                                        |extension (JSONB)                                                  +---> [ Compliance Audit Log ]