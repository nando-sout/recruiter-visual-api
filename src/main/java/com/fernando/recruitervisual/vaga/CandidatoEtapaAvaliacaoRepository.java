package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CandidatoEtapaAvaliacaoRepository extends JpaRepository<CandidatoEtapaAvaliacao, UUID> {

	Optional<CandidatoEtapaAvaliacao> findByCandidatoIdAndEtapaId(UUID candidatoId, UUID etapaId);

	// Na ordem atual das etapas da vaga. A etapa vem junto para o nome não custar uma consulta por avaliação.
	@Query("SELECT a FROM CandidatoEtapaAvaliacao a JOIN FETCH a.etapa e WHERE a.candidato.id = :candidatoId ORDER BY e.position")
	List<CandidatoEtapaAvaliacao> findByCandidatoIdOrderByEtapaPosition(UUID candidatoId);

	// Todas as avaliações da vaga em uma única consulta, com candidato e etapa já carregados.
	// Candidatos na ordem das listagens (createdAt, id) e, dentro de cada um, na ordem atual das etapas.
	@Query("""
			SELECT a FROM CandidatoEtapaAvaliacao a
			JOIN FETCH a.candidato c
			JOIN FETCH a.etapa e
			WHERE a.vaga.id = :vagaId
			ORDER BY c.createdAt, c.id, e.position
			""")
	List<CandidatoEtapaAvaliacao> findByVagaIdOrderByCandidatoAndEtapaPosition(UUID vagaId);

}
