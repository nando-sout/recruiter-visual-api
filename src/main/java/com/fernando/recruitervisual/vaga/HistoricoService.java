package com.fernando.recruitervisual.vaga;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fernando.recruitervisual.recruiter.RecruiterRepository;

/**
 * Registro do histórico do processo seletivo. Só persiste o acontecimento;
 * as regras do ciclo de vida do candidato ficam no CandidatoService.
 */
@Service
public class HistoricoService {

	private final HistoricoRepository historicoRepository;
	private final RecruiterRepository recruiterRepository;

	public HistoricoService(HistoricoRepository historicoRepository, RecruiterRepository recruiterRepository) {
		this.historicoRepository = historicoRepository;
		this.recruiterRepository = recruiterRepository;
	}

	// Chamado dentro da transação da operação do candidato: ou a operação e o histórico são gravados, ou nada é.
	// recruiterId é o do JWT; a FK composta no banco garante que ele é o dono da vaga.
	void registrar(Candidato candidato, HistoricoAcao acao, Etapa etapaAnterior, Etapa etapa, UUID recruiterId) {
		Historico historico = new Historico();
		historico.setId(UUID.randomUUID());
		historico.setVaga(candidato.getVaga());
		historico.setRecruiter(recruiterRepository.getReferenceById(recruiterId));
		historico.setCandidato(candidato);
		historico.setEtapaAnterior(etapaAnterior);
		historico.setEtapaAnteriorNome(etapaAnterior == null ? null : etapaAnterior.getName());
		historico.setEtapa(etapa);
		historico.setEtapaNome(etapa.getName());
		historico.setAcao(acao);

		historicoRepository.saveAndFlush(historico);
	}

	// Chamado dentro da transação da alteração de status: ou o status e o histórico são gravados, ou nada é.
	void registrarStatusAlterado(Vaga vaga, VagaStatus statusAnterior, VagaStatus statusNovo, UUID recruiterId) {
		Historico historico = new Historico();
		historico.setId(UUID.randomUUID());
		historico.setVaga(vaga);
		historico.setRecruiter(recruiterRepository.getReferenceById(recruiterId));
		historico.setStatusAnterior(statusAnterior);
		historico.setStatusNovo(statusNovo);
		historico.setAcao(HistoricoAcao.STATUS_ALTERADO);

		historicoRepository.saveAndFlush(historico);
	}

}
