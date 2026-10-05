package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "candidato")
public class Candidato {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "vaga_id", nullable = false)
	private Vaga vaga;

	// Etapa atual. A FK composta no banco garante que ela pertence à mesma vaga.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "etapa_id", nullable = false)
	private Etapa etapa;

	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Column(name = "linkedin", length = 500)
	private String linkedin;

	@Column(name = "stack", nullable = false, length = 500)
	private String stack;

	@Column(name = "rating")
	private Integer rating;

	@Column(name = "linkedin_about", length = 5000)
	private String linkedinAbout;

	@Column(name = "recruiter_opinion", length = 5000)
	private String recruiterOpinion;

	@Column(name = "technical_opinion", length = 5000)
	private String technicalOpinion;

	// Estado, não etapa: o reprovado continua em etapa, mas sai do Kanban ativo.
	@Column(name = "reprovado", nullable = false)
	private boolean reprovado;

	// Etapa em que foi reprovado. A FK composta no banco garante que ela pertence à mesma vaga.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "etapa_reprovacao_id")
	private Etapa etapaReprovacao;

	// Nome da etapa no momento da reprovação; não muda se a etapa for renomeada depois.
	@Column(name = "etapa_reprovacao_nome", length = 100)
	private String etapaReprovacaoNome;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Candidato() {
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

	public Etapa getEtapa() {
		return etapa;
	}

	public void setEtapa(Etapa etapa) {
		this.etapa = etapa;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getLinkedin() {
		return linkedin;
	}

	public void setLinkedin(String linkedin) {
		this.linkedin = linkedin;
	}

	public String getStack() {
		return stack;
	}

	public void setStack(String stack) {
		this.stack = stack;
	}

	public Integer getRating() {
		return rating;
	}

	public void setRating(Integer rating) {
		this.rating = rating;
	}

	public String getLinkedinAbout() {
		return linkedinAbout;
	}

	public void setLinkedinAbout(String linkedinAbout) {
		this.linkedinAbout = linkedinAbout;
	}

	public String getRecruiterOpinion() {
		return recruiterOpinion;
	}

	public void setRecruiterOpinion(String recruiterOpinion) {
		this.recruiterOpinion = recruiterOpinion;
	}

	public String getTechnicalOpinion() {
		return technicalOpinion;
	}

	public void setTechnicalOpinion(String technicalOpinion) {
		this.technicalOpinion = technicalOpinion;
	}

	public boolean isReprovado() {
		return reprovado;
	}

	public void setReprovado(boolean reprovado) {
		this.reprovado = reprovado;
	}

	public Etapa getEtapaReprovacao() {
		return etapaReprovacao;
	}

	public void setEtapaReprovacao(Etapa etapaReprovacao) {
		this.etapaReprovacao = etapaReprovacao;
	}

	public String getEtapaReprovacaoNome() {
		return etapaReprovacaoNome;
	}

	public void setEtapaReprovacaoNome(String etapaReprovacaoNome) {
		this.etapaReprovacaoNome = etapaReprovacaoNome;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
