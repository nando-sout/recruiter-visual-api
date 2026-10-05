package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface VagaRepository extends JpaRepository<Vaga, UUID> {

	Optional<Vaga> findByCode(String code);

	List<Vaga> findByRecruiterId(UUID recruiterId);

	Optional<Vaga> findByIdAndRecruiterId(UUID id, UUID recruiterId);

	// Mesmo filtro de findByIdAndRecruiterId, com SELECT ... FOR UPDATE.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Vaga> findWithLockByIdAndRecruiterId(UUID id, UUID recruiterId);

}
