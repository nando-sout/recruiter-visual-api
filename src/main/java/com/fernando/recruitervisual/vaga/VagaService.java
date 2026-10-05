package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fernando.recruitervisual.recruiter.Recruiter;
import com.fernando.recruitervisual.recruiter.RecruiterRepository;

@Service
public class VagaService {

	// Nome da constraint UNIQUE criada pela V2 em vaga.code.
	private static final String CODE_UNIQUE_CONSTRAINT = "vaga_code_key";

	// Transições manuais permitidas: pausar, retomar e cancelar. FECHADA não aparece: só o avanço de candidato
	// à Proposta a define, e uma vaga fechada não sai desse status manualmente.
	// CANCELADA é final: não é retomada nem pausada.
	private static final Map<VagaStatus, Set<VagaStatus>> MANUAL_TRANSITIONS = Map.of(
			VagaStatus.ATUANDO, Set.of(VagaStatus.PAUSADA, VagaStatus.CANCELADA),
			VagaStatus.PAUSADA, Set.of(VagaStatus.ATUANDO, VagaStatus.CANCELADA),
			VagaStatus.CANCELADA, Set.of());

	private final VagaRepository vagaRepository;
	private final RecruiterRepository recruiterRepository;
	private final EtapaService etapaService;
	private final HistoricoService historicoService;

	public VagaService(VagaRepository vagaRepository, RecruiterRepository recruiterRepository, EtapaService etapaService,
			HistoricoService historicoService) {
		this.vagaRepository = vagaRepository;
		this.recruiterRepository = recruiterRepository;
		this.etapaService = etapaService;
		this.historicoService = historicoService;
	}

	@Transactional
	public VagaResponse create(CreateVagaRequest request, UUID recruiterId) {
		Recruiter recruiter = recruiterRepository.findById(recruiterId)
				.orElseThrow(RecruiterNotFoundException::new);

		// Verificação antecipada só para a mensagem amigável; a UNIQUE do banco é a garantia final.
		if (vagaRepository.findByCode(request.code()).isPresent()) {
			throw new DuplicateVagaCodeException(request.code());
		}

		Vaga vaga = new Vaga();
		vaga.setId(UUID.randomUUID());
		vaga.setCode(request.code());
		vaga.setTitle(request.title());
		vaga.setDescription(request.description());
		vaga.setStatus(VagaStatus.ATUANDO);
		vaga.setRecruiter(recruiter);

		Vaga saved;
		try {
			saved = vagaRepository.saveAndFlush(vaga);
		} catch (DataIntegrityViolationException ex) {
			if (isCodeUniqueViolation(ex)) {
				throw new DuplicateVagaCodeException(request.code());
			}
			throw ex;
		}

		if (request.etapas() == null) {
			etapaService.createDefaultEtapas(saved);
		} else {
			etapaService.createEtapas(saved, request.etapas());
		}
		return VagaResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<VagaResponse> listByRecruiter(UUID recruiterId) {
		return vagaRepository.findByRecruiterId(recruiterId).stream()
				.map(VagaResponse::from)
				.toList();
	}

	// Vaga inexistente e vaga de outro recruiter geram a mesma exceção, para não revelar que ela existe.
	@Transactional(readOnly = true)
	public VagaResponse findByIdForRecruiter(UUID id, UUID recruiterId) {
		return vagaRepository.findByIdAndRecruiterId(id, recruiterId)
				.map(VagaResponse::from)
				.orElseThrow(VagaNotFoundException::new);
	}

	// Só title e description mudam; id, code, status e recruiter são preservados.
	@Transactional
	public VagaResponse update(UUID id, UpdateVagaRequest request, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(id, recruiterId)
				.orElseThrow(VagaNotFoundException::new);

		vaga.setTitle(request.title());
		vaga.setDescription(request.description());

		return VagaResponse.from(vagaRepository.saveAndFlush(vaga));
	}

	@Transactional
	public VagaResponse updateStatus(UUID id, VagaStatus status, UUID recruiterId) {
		// Mesmo lock do avanço de candidato: não sobrescreve um FECHADA gravado por um avanço simultâneo.
		Vaga vaga = vagaRepository.findWithLockByIdAndRecruiterId(id, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		VagaStatus atual = vaga.getStatus();

		if (atual == VagaStatus.FECHADA) {
			throw new VagaStatusTransitionException("Uma vaga fechada não pode ter o status alterado manualmente");
		}
		if (!MANUAL_TRANSITIONS.get(atual).contains(status)) {
			throw new VagaStatusTransitionException(
					"Não é possível alterar o status da vaga de " + atual + " para " + status);
		}

		vaga.setStatus(status);
		Vaga saved = vagaRepository.saveAndFlush(vaga);
		historicoService.registrarStatusAlterado(saved, atual, status, recruiterId);
		return VagaResponse.from(saved);
	}

	private static boolean isCodeUniqueViolation(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException cve) {
				return CODE_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName());
			}
		}
		return false;
	}

}
