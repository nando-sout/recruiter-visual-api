package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avaliações do candidato por etapa. É edição de informação, não movimentação: vale para qualquer status
 * da vaga e para candidato reprovado, não gera histórico e não muda etapa, reprovação nem dados do candidato.
 * Toda operação localiza a vaga por id + recruiterId autenticado.
 */
@Service
public class AvaliacaoService {

	// Eventos que colocam o candidato em uma etapa.
	private static final List<HistoricoAcao> ACOES_DE_CHEGADA = List.of(
			HistoricoAcao.CANDIDATO_CRIADO, HistoricoAcao.CANDIDATO_AVANCADO);

	private final CandidatoEtapaAvaliacaoRepository avaliacaoRepository;
	private final CandidatoRepository candidatoRepository;
	private final EtapaRepository etapaRepository;
	private final VagaRepository vagaRepository;
	private final HistoricoRepository historicoRepository;

	public AvaliacaoService(CandidatoEtapaAvaliacaoRepository avaliacaoRepository,
			CandidatoRepository candidatoRepository, EtapaRepository etapaRepository, VagaRepository vagaRepository,
			HistoricoRepository historicoRepository) {
		this.avaliacaoRepository = avaliacaoRepository;
		this.candidatoRepository = candidatoRepository;
		this.etapaRepository = etapaRepository;
		this.vagaRepository = vagaRepository;
		this.historicoRepository = historicoRepository;
	}

	/**
	 * Cria a avaliação do candidato na etapa ou atualiza a que já existe.
	 */
	@Transactional
	public AvaliacaoResponse save(UUID vagaId, UUID candidatoId, UUID etapaId, AvaliacaoRequest request, UUID recruiterId) {
		// Lock da vaga: duas gravações simultâneas da mesma avaliação não criam duas linhas,
		// e a etapa não é excluída entre a busca e o insert. A UNIQUE do banco é a garantia final.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Candidato candidato = candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.orElseThrow(CandidatoNotFoundException::new);
		Etapa etapa = etapaRepository.findByIdAndVagaId(etapaId, vaga.getId())
				.orElseThrow(EtapaNotFoundException::new);

		// A etapa atual também conta: o candidato chegou a ela por um desses eventos.
		if (!historicoRepository.existsByCandidatoIdAndEtapaIdAndAcaoIn(candidato.getId(), etapa.getId(), ACOES_DE_CHEGADA)) {
			throw new CandidatoAvaliacaoException("O candidato não passou pela etapa informada");
		}

		CandidatoEtapaAvaliacao avaliacao = avaliacaoRepository
				.findByCandidatoIdAndEtapaId(candidato.getId(), etapa.getId())
				.orElseGet(() -> newAvaliacao(vaga, candidato, etapa));
		avaliacao.setRating(request.rating());
		avaliacao.setObservacao(request.observacao());

		return AvaliacaoResponse.from(avaliacaoRepository.saveAndFlush(avaliacao));
	}

	@Transactional(readOnly = true)
	public List<AvaliacaoResponse> list(UUID vagaId, UUID candidatoId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Candidato candidato = candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.orElseThrow(CandidatoNotFoundException::new);
		return avaliacaoRepository.findByCandidatoIdOrderByEtapaPosition(candidato.getId()).stream()
				.map(AvaliacaoResponse::from)
				.toList();
	}

	// Avaliações de todos os candidatos da vaga, ativos e reprovados.
	@Transactional(readOnly = true)
	public List<VagaAvaliacaoResponse> listByVaga(UUID vagaId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		return avaliacaoRepository.findByVagaIdOrderByCandidatoAndEtapaPosition(vaga.getId()).stream()
				.map(VagaAvaliacaoResponse::from)
				.toList();
	}

	private static CandidatoEtapaAvaliacao newAvaliacao(Vaga vaga, Candidato candidato, Etapa etapa) {
		CandidatoEtapaAvaliacao avaliacao = new CandidatoEtapaAvaliacao();
		avaliacao.setId(UUID.randomUUID());
		avaliacao.setVaga(vaga);
		avaliacao.setCandidato(candidato);
		avaliacao.setEtapa(etapa);
		return avaliacao;
	}

}
