package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CandidatoRepository extends JpaRepository<Candidato, UUID> {

	// Só candidatos ativos (Kanban). id como desempate para uma ordem estável entre candidatos criados no mesmo instante.
	List<Candidato> findByVagaIdAndReprovadoFalseOrderByCreatedAtAscIdAsc(UUID vagaId);

	// Só candidatos reprovados, na mesma ordem da listagem de ativos.
	List<Candidato> findByVagaIdAndReprovadoTrueOrderByCreatedAtAscIdAsc(UUID vagaId);

	Optional<Candidato> findByIdAndVagaId(UUID id, UUID vagaId);

	// Conta também os reprovados: eles continuam em etapa_id, então a etapa segue em uso.
	boolean existsByEtapaId(UUID etapaId);

	long countByEtapaIdAndReprovadoFalse(UUID etapaId);

	long countByEtapaReprovacaoId(UUID etapaId);

	// Só candidatos ativos. Etapas sem candidatos não aparecem no resultado.
	@Query("SELECT c.etapa.id AS etapaId, COUNT(c) AS total FROM Candidato c WHERE c.vaga.id = :vagaId AND c.reprovado = false GROUP BY c.etapa.id")
	List<EtapaCandidatesCount> countByEtapaOfVaga(UUID vagaId);

	// Reprovados agrupados pela etapa em que foram reprovados. Etapas sem reprovados não aparecem no resultado.
	@Query("SELECT c.etapaReprovacao.id AS etapaId, COUNT(c) AS total FROM Candidato c WHERE c.vaga.id = :vagaId AND c.reprovado = true GROUP BY c.etapaReprovacao.id")
	List<EtapaCandidatesCount> countReprovadosByEtapaOfVaga(UUID vagaId);

	interface EtapaCandidatesCount {

		UUID getEtapaId();

		long getTotal();

	}

}
