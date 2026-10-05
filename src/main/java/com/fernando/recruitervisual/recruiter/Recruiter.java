package com.fernando.recruitervisual.recruiter;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recruiter")
public class Recruiter {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "email", nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "name", nullable = false)
	private String name;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	// NULL enquanto o e-mail não foi confirmado.
	@Column(name = "email_verified_at")
	private LocalDateTime emailVerifiedAt;

	// Hash BCrypt do código de confirmação ativo; NULL quando não há código ativo.
	@Column(name = "verification_code_hash")
	private String verificationCodeHash;

	@Column(name = "verification_code_expires_at")
	private LocalDateTime verificationCodeExpiresAt;

	// Tentativas incorretas do código ativo.
	@Column(name = "verification_attempts", nullable = false)
	private int verificationAttempts;

	protected Recruiter() {
	}

	public Recruiter(UUID id, String name, String email, String passwordHash) {
		this.id = id;
		this.name = name;
		this.email = email;
		this.passwordHash = passwordHash;
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) {
		this.emailVerifiedAt = emailVerifiedAt;
	}

	public String getVerificationCodeHash() {
		return verificationCodeHash;
	}

	public void setVerificationCodeHash(String verificationCodeHash) {
		this.verificationCodeHash = verificationCodeHash;
	}

	public LocalDateTime getVerificationCodeExpiresAt() {
		return verificationCodeExpiresAt;
	}

	public void setVerificationCodeExpiresAt(LocalDateTime verificationCodeExpiresAt) {
		this.verificationCodeExpiresAt = verificationCodeExpiresAt;
	}

	public int getVerificationAttempts() {
		return verificationAttempts;
	}

	public void setVerificationAttempts(int verificationAttempts) {
		this.verificationAttempts = verificationAttempts;
	}

}
