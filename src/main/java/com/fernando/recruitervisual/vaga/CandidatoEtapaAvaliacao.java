package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Avaliação de um candidato em uma etapa. No máximo uma por candidato + etapa (UNIQUE no banco).
 * Independente de Candidato.rating, que continua sendo a nota geral do candidato.
 */
@Entity
@Table(name = "candidato_etapa_avaliacao")
public class CandidatoEtapaAvaliacao {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	// As FKs compostas no banco garantem que candidato e etapa pertencem a esta vaga.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "vaga_id", nullable = false, updatable = false)
	private Vaga vaga;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "candidato_id", nullable = false, updatable = false)
	private Candidato candidato;

	// Excluir a etapa apaga a avaliação (ON DELETE CASCADE no banco).
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "etapa_id", nullable = false, updatable = false)
	private Etapa etapa;

	@Column(name = "rating", nullable = false)
	private int rating;

	@Column(name = "observacao", length = 5000)
	private String observacao;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected CandidatoEtapaAvaliacao() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public Vaga getVaga() {
		return vaga;
	}

	public void setVaga(Vaga vaga) {
		this.vaga = vaga;
	}

	public Candidato getCandidato() {
		return candidato;
	}

	public void setCandidato(Candidato candidato) {
		this.candidato = candidato;
	}

	public Etapa getEtapa() {
		return etapa;
	}

	public void setEtapa(Etapa etapa) {
		this.etapa = etapa;
	}

	public int getRating() {
		return rating;
	}

	public void setRating(int rating) {
		this.rating = rating;
	}

	public String getObservacao() {
		return observacao;
	}

	public void setObservacao(String observacao) {
		this.observacao = observacao;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

}
