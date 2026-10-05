package com.fernando.recruitervisual.recruiter;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface RecruiterRepository extends JpaRepository<Recruiter, UUID> {

	Optional<Recruiter> findByEmail(String email);

	// Mesmo filtro de findByEmail, com SELECT ... FOR UPDATE: tentativas simultâneas do código não se perdem.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Recruiter> findWithLockByEmail(String email);

}
