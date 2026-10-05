package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.fernando.recruitervisual.recruiter.Recruiter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "historico")
public class Historico {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "vaga_id", nullable = false)
	private Vaga vaga;

	// Recruiter autenticado que realizou a operação. A FK composta no banco garante que é o dono da vaga.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recruiter_id", nullable = false)
	private Recruiter recruiter;

	// Só em eventos de candidato. A FK composta no banco garante que o candidato pertence à mesma vaga.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "candidato_id")
	private Candidato candidato;

	// Só no avanço. Vira NULL no banco se a etapa for excluída; o nome permanece.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "etapa_anterior_id")
	private Etapa etapaAnterior;

	@Column(name = "etapa_anterior_nome", length = 100)
	private String etapaAnteriorNome;

	// Sempre gravada em eventos de candidato. Vira NULL no banco se a etapa for excluída; o nome permanece.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "etapa_id")
	private Etapa etapa;

	// Nome da etapa no momento do evento; não muda se a etapa for renomeada depois. Só em eventos de candidato.
	@Column(name = "etapa_nome", length = 100)
	private String etapaNome;

	// Só em STATUS_ALTERADO.
	@Enumerated(EnumType.STRING)
	@Column(name = "status_anterior", length = 20)
	private VagaStatus statusAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "status_novo", length = 20)
	private VagaStatus statusNovo;

	@Enumerated(EnumType.STRING)
	@Column(name = "acao", nullable = false, length = 30)
	private HistoricoAcao acao;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Historico() {
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

	public Recruiter getRecruiter() {
		return recruiter;
	}

	public void setRecruiter(Recruiter recruiter) {
		this.recruiter = recruiter;
	}

	public Candidato getCandidato() {
		return candidato;
	}

	public void setCandidato(Candidato candidato) {
		this.candidato = candidato;
	}

	public Etapa getEtapaAnterior() {
		return etapaAnterior;
	}

	public void setEtapaAnterior(Etapa etapaAnterior) {
		this.etapaAnterior = etapaAnterior;
	}

	public String getEtapaAnteriorNome() {
		return etapaAnteriorNome;
	}

	public void setEtapaAnteriorNome(String etapaAnteriorNome) {
		this.etapaAnteriorNome = etapaAnteriorNome;
	}

	public Etapa getEtapa() {
		return etapa;
	}

	public void setEtapa(Etapa etapa) {
		this.etapa = etapa;
	}

	public String getEtapaNome() {
		return etapaNome;
	}

	public void setEtapaNome(String etapaNome) {
		this.etapaNome = etapaNome;
	}

	public VagaStatus getStatusAnterior() {
		return statusAnterior;
	}

	public void setStatusAnterior(VagaStatus statusAnterior) {
		this.statusAnterior = statusAnterior;
	}

	public VagaStatus getStatusNovo() {
		return statusNovo;
	}

	public void setStatusNovo(VagaStatus statusNovo) {
		this.statusNovo = statusNovo;
	}

	public HistoricoAcao getAcao() {
		return acao;
	}

	public void setAcao(HistoricoAcao acao) {
		this.acao = acao;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
