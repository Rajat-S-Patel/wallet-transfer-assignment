package com.rajat.wallet.repository;

import com.rajat.wallet.domain.entities.Transfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {}
