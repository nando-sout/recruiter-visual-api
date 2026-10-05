package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidatos de uma vaga. Toda operação localiza a vaga por id + recruiterId autenticado;
 * o candidato nunca é buscado só pelo próprio id.
 * Cadastrar, avançar e reprovar só acontecem em vaga ATUANDO; as consultas valem para qualquer status.
 */
@Service
public class CandidatoService {

	private final CandidatoRepository candidatoRepository;
	private final EtapaRepository etapaRepository;
	private final VagaRepository vagaRepository;
	private final HistoricoService historicoService;

	public CandidatoService(CandidatoRepository candidatoRepository, EtapaRepository etapaRepository,
			VagaRepository vagaRepository, HistoricoService historicoService) {
		this.candidatoRepository = candidatoRepository;
		this.etapaRepository = etapaRepository;
		this.vagaRepository = vagaRepository;
		this.historicoService = historicoService;
	}

	@Transactional
	public CandidatoResponse create(UUID vagaId, CreateCandidatoRequest request, UUID recruiterId) {
		// Mesmo lock das operações de etapa: a primeira etapa não pode ser excluída entre a busca e o insert.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		if (vaga.getStatus() != VagaStatus.ATUANDO) {
			throw new CandidatoCadastroException(
					"Não é possível cadastrar candidatos em uma vaga " + statusName(vaga));
		}
		Etapa primeiraEtapa =etapaRepository.findFirstByVagaIdOrderByPositionAsc(vaga.getId())
				.orElseThrow(() -> new IllegalStateException("Vaga sem etapas"));

		Candidato candidato = new Candidato();
		candidato.setId(UUID.randomUUID());
		candidato.setVaga(vaga);
		candidato.setEtapa(primeiraEtapa);
		candidato.setName(request.name());
		candidato.setLinkedin(request.linkedin());
		candidato.setStack(request.stack());
		candidato.setRating(request.rating());
		candidato.setLinkedinAbout(request.linkedinAbout());
		candidato.setRecruiterOpinion(request.recruiterOpinion());
		candidato.setTechnicalOpinion(request.technicalOpinion());

		Candidato saved = candidatoRepository.saveAndFlush(candidato);
		historicoService.registrar(saved, HistoricoAcao.CANDIDATO_CRIADO, null, primeiraEtapa, recruiterId);
		return CandidatoResponse.from(saved);
	}

	/**
	 * Edita os dados do candidato. É edição de informação, não movimentação: vale para qualquer status da vaga
	 * e também para candidato reprovado, e não muda etapa nem dados de reprovação.
	 */
	@Transactional
	public CandidatoResponse update(UUID vagaId, UUID candidatoId, UpdateCandidatoRequest request, UUID recruiterId) {
		// Mesmo lock do avanço e da reprovação: o UPDATE grava todas as colunas do candidato,
		// então uma edição simultânea não pode sobrescrever a etapa ou a reprovação recém-gravadas.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Candidato candidato = candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.orElseThrow(CandidatoNotFoundException::new);

		candidato.setName(request.name());
		candidato.setLinkedin(request.linkedin());
		candidato.setStack(request.stack());
		candidato.setRating(request.rating());
		candidato.setLinkedinAbout(request.linkedinAbout());
		candidato.setRecruiterOpinion(request.recruiterOpinion());
		candidato.setTechnicalOpinion(request.technicalOpinion());

		return CandidatoResponse.from(candidatoRepository.saveAndFlush(candidato));
	}

	/**
	 * Move o candidato para a próxima etapa por position, uma por requisição.
	 * Chegar à etapa de proposta fecha a vaga na mesma transação.
	 */
	@Transactional
	public CandidatoResponse advance(UUID vagaId, UUID candidatoId, UUID etapaIdEsperada, UUID recruiterId) {
		// Lock da vaga: serializa com outros avanços e com criar/excluir/reordenar etapas.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Candidato candidato = candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.orElseThrow(CandidatoNotFoundException::new);
		Etapa atual = candidato.getEtapa();

		if (candidato.isReprovado()) {
			throw new CandidatoAdvanceException("O candidato foi reprovado e não pode avançar");
		}
		if (!atual.getId().equals(etapaIdEsperada)) {
			throw new CandidatoAdvanceException("O candidato não está mais na etapa informada");
		}
		if (atual.isProposta()) {
			throw new CandidatoAdvanceException("O candidato já está na etapa final");
		}
		if (vaga.getStatus() != VagaStatus.ATUANDO) {
			throw new CandidatoAdvanceException("Não é possível avançar candidatos em uma vaga " + statusName(vaga));
		}

		// A proposta é sempre a última etapa, então toda etapa comum tem uma próxima.
		Etapa proxima = etapaRepository
				.findFirstByVagaIdAndPositionGreaterThanOrderByPositionAsc(vaga.getId(), atual.getPosition())
				.orElseThrow(() -> new IllegalStateException("Etapa comum sem próxima etapa"));

		candidato.setEtapa(proxima);
		if (proxima.isProposta()) {
			vaga.setStatus(VagaStatus.FECHADA);
		}

		// O flush grava candidato e vaga juntos.
		Candidato saved = candidatoRepository.saveAndFlush(candidato);
		historicoService.registrar(saved, HistoricoAcao.CANDIDATO_AVANCADO, atual, proxima, recruiterId);
		return CandidatoResponse.from(saved);
	}

	/**
	 * Marca o candidato como reprovado na etapa atual. Ele continua na etapa e vinculado à vaga,
	 * mas sai do Kanban ativo. O nome da etapa é copiado para não mudar com renomeações futuras.
	 */
	@Transactional
	public CandidatoResponse reprovar(UUID vagaId, UUID candidatoId, UUID etapaIdEsperada, UUID recruiterId) {
		// Mesmo lock do avanço: reprovar e avançar o mesmo candidato não se sobrepõem.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Candidato candidato = candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.orElseThrow(CandidatoNotFoundException::new);
		Etapa atual = candidato.getEtapa();

		if (!atual.getId().equals(etapaIdEsperada)) {
			throw new CandidatoReprovacaoException("O candidato não está mais na etapa informada");
		}
		if (candidato.isReprovado()) {
			throw new CandidatoReprovacaoException("O candidato já foi reprovado");
		}
		// Chegar à proposta fecha a vaga; reprovar ali exigiria reabri-la, o que não é permitido.
		if (atual.isProposta()) {
			throw new CandidatoReprovacaoException("Não é possível reprovar um candidato na etapa de proposta");
		}
		if (vaga.getStatus() != VagaStatus.ATUANDO) {
			throw new CandidatoReprovacaoException("Não é possível reprovar candidatos em uma vaga " + statusName(vaga));
		}

		candidato.setReprovado(true);
		candidato.setEtapaReprovacao(atual);
		candidato.setEtapaReprovacaoNome(atual.getName());

		Candidato saved = candidatoRepository.saveAndFlush(candidato);
		historicoService.registrar(saved, HistoricoAcao.CANDIDATO_REPROVADO, null, atual, recruiterId);
		return CandidatoResponse.from(saved);
	}

	// Só candidatos ativos: os reprovados continuam acessíveis por findById.
	@Transactional(readOnly = true)
	public List<CandidatoResponse> list(UUID vagaId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		return candidatoRepository.findByVagaIdAndReprovadoFalseOrderByCreatedAtAscIdAsc(vaga.getId()).stream()
				.map(CandidatoResponse::from)
				.toList();
	}

	// Só candidatos reprovados: o complemento de list.
	@Transactional(readOnly = true)
	public List<CandidatoResponse> listReprovados(UUID vagaId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		return candidatoRepository.findByVagaIdAndReprovadoTrueOrderByCreatedAtAscIdAsc(vaga.getId()).stream()
				.map(CandidatoResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public CandidatoResponse findById(UUID vagaId, UUID candidatoId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		return candidatoRepository.findByIdAndVagaId(candidatoId, vaga.getId())
				.map(CandidatoResponse::from)
				.orElseThrow(CandidatoNotFoundException::new);
	}

	// "pausada", "cancelada", "fechada": para as mensagens de status que não permitem a operação.
	private static String statusName(Vaga vaga) {
		return vaga.getStatus().name().toLowerCase(Locale.ROOT);
	}

}
