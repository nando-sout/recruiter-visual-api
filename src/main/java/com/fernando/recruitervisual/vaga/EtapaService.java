package com.fernando.recruitervisual.vaga;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Etapas de uma vaga. A etapa de proposta é sempre a última: novas etapas entram antes dela,
 * ela não pode ser excluída nem mudar de posição, mas pode ser renomeada.
 * Toda operação localiza a vaga por id + recruiterId autenticado.
 */
@Service
public class EtapaService {

	private static final List<String> DEFAULT_ETAPAS = List.of("Envio de Shortlist", "Entrevista Liderança", "Entrevista RH");
	private static final String PROPOSTA_DEFAULT_NAME = "Proposta";

	private final EtapaRepository etapaRepository;
	private final VagaRepository vagaRepository;
	private final CandidatoRepository candidatoRepository;
	private final HistoricoRepository historicoRepository;

	public EtapaService(EtapaRepository etapaRepository, VagaRepository vagaRepository,
			CandidatoRepository candidatoRepository, HistoricoRepository historicoRepository) {
		this.etapaRepository = etapaRepository;
		this.vagaRepository = vagaRepository;
		this.candidatoRepository = candidatoRepository;
		this.historicoRepository = historicoRepository;
	}

	// Chamado dentro da transação de criação da vaga: ou a vaga e as 4 etapas são gravadas, ou nada é.
	void createDefaultEtapas(Vaga vaga) {
		List<Etapa> etapas = new ArrayList<>();
		for (String name : DEFAULT_ETAPAS) {
			etapas.add(newEtapa(vaga, name, etapas.size() + 1, false));
		}
		etapas.add(newEtapa(vaga, PROPOSTA_DEFAULT_NAME, etapas.size() + 1, true));
		etapaRepository.saveAllAndFlush(etapas);
	}

	// Etapas configuradas pelo recruiter na criação da vaga, na ordem recebida. Também dentro da transação
	// de criação da vaga: uma configuração inválida desfaz a vaga junto.
	void createEtapas(Vaga vaga, List<CreateVagaEtapaRequest> requests) {
		if (requests.stream().filter(CreateVagaEtapaRequest::proposta).count() != 1) {
			throw new InvalidVagaEtapasException("A vaga deve ter exatamente uma etapa de proposta");
		}
		if (!requests.get(requests.size() - 1).proposta()) {
			throw new InvalidVagaEtapasException("A etapa de proposta deve ser a última");
		}

		List<Etapa> etapas = new ArrayList<>();
		for (CreateVagaEtapaRequest request : requests) {
			etapas.add(newEtapa(vaga, request.name(), etapas.size() + 1, request.proposta()));
		}
		etapaRepository.saveAllAndFlush(etapas);
	}

	@Transactional(readOnly = true)
	public List<EtapaResponse> list(UUID vagaId, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		return toResponses(vaga.getId(), etapaRepository.findByVagaIdOrderByPositionAsc(vaga.getId()));
	}

	@Transactional
	public EtapaResponse create(UUID vagaId, EtapaRequest request, UUID recruiterId) {
		Vaga vaga = lockVaga(vagaId, recruiterId);
		Etapa proposta = findProposta(etapaRepository.findByVagaIdOrderByPositionAsc(vaga.getId()));

		// A nova etapa ocupa o lugar da proposta, que desce uma posição e continua sendo a última.
		int position = proposta.getPosition();
		proposta.setPosition(position + 1);

		return toResponse(etapaRepository.saveAndFlush(newEtapa(vaga, request.name(), position, false)), 0, 0, 0);
	}

	@Transactional
	public EtapaResponse rename(UUID vagaId, UUID etapaId, EtapaRequest request, UUID recruiterId) {
		Vaga vaga = vagaRepository.findByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
		Etapa etapa = etapaRepository.findByIdAndVagaId(etapaId, vaga.getId())
				.orElseThrow(EtapaNotFoundException::new);

		etapa.setName(request.name());

		return toResponse(etapaRepository.saveAndFlush(etapa),
				candidatoRepository.countByEtapaIdAndReprovadoFalse(etapa.getId()),
				candidatoRepository.countByEtapaReprovacaoId(etapa.getId()),
				toMap(historicoRepository.countChegaramByEtapaOfVaga(vaga.getId())).getOrDefault(etapa.getId(), 0L));
	}

	@Transactional
	public void delete(UUID vagaId, UUID etapaId, UUID recruiterId) {
		Vaga vaga = lockVaga(vagaId, recruiterId);
		List<Etapa> etapas = new ArrayList<>(etapaRepository.findByVagaIdOrderByPositionAsc(vaga.getId()));
		Etapa etapa = etapas.stream()
				.filter(e -> e.getId().equals(etapaId))
				.findFirst()
				.orElseThrow(EtapaNotFoundException::new);

		if (etapa.isProposta()) {
			throw new PropostaEtapaException("A etapa de proposta não pode ser excluída");
		}
		// Candidatos ainda não podem ser movidos, então a etapa fica; reprovados também contam.
		// A FK do banco é a garantia final.
		if (candidatoRepository.existsByEtapaId(etapa.getId())) {
			throw new EtapaHasCandidatosException();
		}

		etapas.remove(etapa);
		etapaRepository.delete(etapa);
		renumber(etapas);
		etapaRepository.flush();
	}

	@Transactional
	public List<EtapaResponse> reorder(UUID vagaId, ReorderEtapasRequest request, UUID recruiterId) {
		Vaga vaga = lockVaga(vagaId, recruiterId);
		List<Etapa> etapas = etapaRepository.findByVagaIdOrderByPositionAsc(vaga.getId());
		Map<UUID, Etapa> etapasById = etapas.stream().collect(Collectors.toMap(Etapa::getId, Function.identity()));
		List<UUID> ids = request.etapaIds();

		// Mesmo tamanho, sem repetição e só ids desta vaga: a lista é exatamente uma permutação das etapas.
		if (ids.size() != etapas.size()
				|| new HashSet<>(ids).size() != ids.size()
				|| !etapasById.keySet().containsAll(ids)) {
			throw new InvalidEtapaOrderException();
		}

		Etapa proposta = findProposta(etapas);
		int propostaIndex = etapas.indexOf(proposta);
		if (!ids.get(propostaIndex).equals(proposta.getId())) {
			throw new PropostaEtapaException("A etapa de proposta não pode mudar de posição");
		}

		List<Etapa> reordered = ids.stream().map(etapasById::get).toList();
		renumber(reordered);
		etapaRepository.flush();
		return toResponses(vaga.getId(), reordered);
	}

	// Bloqueia a linha da vaga para que operações simultâneas não calculem as mesmas posições.
	private Vaga lockVaga(UUID vagaId, UUID recruiterId) {
		return vagaRepository.findWithLockByIdAndRecruiterId(vagaId, recruiterId)
				.orElseThrow(VagaNotFoundException::new);
	}

	private static Etapa findProposta(List<Etapa> etapas) {
		return etapas.stream()
				.filter(Etapa::isProposta)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Vaga sem etapa de proposta"));
	}

	private static void renumber(List<Etapa> etapas) {
		for (int i = 0; i < etapas.size(); i++) {
			etapas.get(i).setPosition(i + 1);
		}
	}

	private static Etapa newEtapa(Vaga vaga, String name, int position, boolean proposta) {
		Etapa etapa = new Etapa();
		etapa.setId(UUID.randomUUID());
		etapa.setVaga(vaga);
		etapa.setName(name);
		etapa.setPosition(position);
		etapa.setProposta(proposta);
		return etapa;
	}

	private List<EtapaResponse> toResponses(UUID vagaId, List<Etapa> etapas) {
		Map<UUID, Long> counts = toMap(candidatoRepository.countByEtapaOfVaga(vagaId));
		Map<UUID, Long> reprovados = toMap(candidatoRepository.countReprovadosByEtapaOfVaga(vagaId));
		Map<UUID, Long> chegaram = toMap(historicoRepository.countChegaramByEtapaOfVaga(vagaId));
		return etapas.stream()
				.map(etapa -> toResponse(etapa, counts.getOrDefault(etapa.getId(), 0L),
						reprovados.getOrDefault(etapa.getId(), 0L), chegaram.getOrDefault(etapa.getId(), 0L)))
				.toList();
	}

	private static EtapaResponse toResponse(Etapa etapa, long candidatesCount, long reprovadosCount, long chegaramCount) {
		return EtapaResponse.from(etapa, candidatesCount, reprovadosCount, chegaramCount,
				taxaReprovacao(reprovadosCount, chegaramCount));
	}

	// Reprovados na etapa sobre os candidatos que chegaram a ela, em percentual com uma casa decimal.
	// Sem chegadas não há base para a taxa: null, e não 0, que significa "chegaram e ninguém foi reprovado".
	private static BigDecimal taxaReprovacao(long reprovadosCount, long chegaramCount) {
		if (chegaramCount == 0) {
			return null;
		}
		return BigDecimal.valueOf(reprovadosCount * 100)
				.divide(BigDecimal.valueOf(chegaramCount), 1, RoundingMode.HALF_UP);
	}

	private static Map<UUID, Long> toMap(List<CandidatoRepository.EtapaCandidatesCount> counts) {
		return counts.stream()
				.collect(Collectors.toMap(
						CandidatoRepository.EtapaCandidatesCount::getEtapaId,
						CandidatoRepository.EtapaCandidatesCount::getTotal));
	}

}
