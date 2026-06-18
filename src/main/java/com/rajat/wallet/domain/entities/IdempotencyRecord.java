package com.rajat.wallet.domain.entities;

import com.rajat.wallet.domain.entities.common.AuditableEntity;
import com.rajat.wallet.domain.enums.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Durable idempotency registry, deliberately decoupled from any single operation so the same
 * mechanism can guard transfers and any future endpoint. The unique {@code idempotencyKey} enforces
 * exactly-once at the API level; {@code responseStatus}/{@code responseBody} cache the original
 * result so a duplicate request is replayed verbatim without re-executing side effects. The
 * {@code requestHash} lets the service reject a key replayed with a different payload, and the
 * {@code status} lifecycle lets a concurrent duplicate detect an in-flight request.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord extends AuditableEntity {

  @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, updatable = false)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private IdempotencyStatus status;

  /**
   * Id of the resource the request created (e.g. a transfer). A plain UUID rather than a foreign
   * key, since this registry is polymorphic across operations and may point at different tables.
   */
  @Column(name = "target_id")
  private UUID targetId;

  @Column(name = "response_status")
  private Integer responseStatus;

  @Column(name = "response_body")
  private String responseBody;

  /** Creates an in-flight record for a first-seen request. */
  public static IdempotencyRecord inProgress(String idempotencyKey, String requestHash) {
    IdempotencyRecord r = new IdempotencyRecord();
    r.idempotencyKey = idempotencyKey;
    r.requestHash = requestHash;
    r.status = IdempotencyStatus.IN_PROGRESS;
    return r;
  }

  /** Captures the created resource and final response, marking the record replayable. */
  public void complete(UUID targetId, int responseStatus, String responseBody) {
    this.status = IdempotencyStatus.COMPLETED;
    this.targetId = targetId;
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
  }
}
