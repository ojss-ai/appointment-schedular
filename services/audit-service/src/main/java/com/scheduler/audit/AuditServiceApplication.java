// TASK: ATOM-KAFKA-009
package com.scheduler.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Append-only HIPAA audit ledger. Consumes
 * {@code tenant.bookings.lifecycle} (consumer group {@code audit-consumers})
 * and INSERTs into {@code audit_log}. The datasource connects as the
 * {@code audit_writer} PostgreSQL role — INSERT-only by grant + RLS (V014),
 * so even buggy code cannot UPDATE or DELETE an audit record.
 */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
