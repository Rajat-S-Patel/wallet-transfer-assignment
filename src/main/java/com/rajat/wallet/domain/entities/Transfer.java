package com.rajat.wallet.domain.entities;

import com.rajat.wallet.domain.entities.common.AuditableEntity;
import com.rajat.wallet.domain.enums.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A transfer request and its lifecycle. Exactly-once request handling lives in the generic {@link
 * IdempotencyRecord} registry, not here. State transitions are guarded: a transfer may only move
 * out of {@link TransferStatus#PENDING}.
 */
@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
public class Transfer extends AuditableEntity {

  @Column(name = "from_wallet_id", nullable = false, updatable = false)
  private UUID fromWalletId;

  @Column(name = "to_wallet_id", nullable = false, updatable = false)
  private UUID toWalletId;

  @Column(nullable = false, updatable = false)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransferStatus status;

  @Column(name = "failure_reason")
  private String failureReason;

  /** Creates a new transfer in the {@link TransferStatus#PENDING} state. */
  public static Transfer pending(UUID fromWalletId, UUID toWalletId, BigDecimal amount) {
    Transfer t = new Transfer();
    t.fromWalletId = fromWalletId;
    t.toWalletId = toWalletId;
    t.amount = amount;
    t.status = TransferStatus.PENDING;
    return t;
  }

  /** Transitions PENDING -> PROCESSED. */
  public void markProcessed() {
    requirePending();
    this.status = TransferStatus.PROCESSED;
  }

  /** Transitions PENDING -> FAILED, recording why. */
  public void markFailed(String reason) {
    requirePending();
    this.status = TransferStatus.FAILED;
    this.failureReason = reason;
  }

  private void requirePending() {
    if (status != TransferStatus.PENDING) {
      throw new IllegalStateException(
          "Transfer " + getId() + " is " + status + "; only PENDING transfers may transition");
    }
  }
}
