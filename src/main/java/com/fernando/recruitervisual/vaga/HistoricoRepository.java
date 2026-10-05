package com.fernando.recruitervisual.vaga;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HistoricoRepository extends JpaRepository<Historico, UUID> {

	// Candidatos distintos que chegaram a cada etapa da vaga: criados nela ou avançados para ela.
	// DISTINCT porque uma reordenação de etapas pode levar o mesmo candidato de volta a uma etapa.
	// Etapas sem chegadas não aparecem no resultado; eventos de etapas já excluídas (etapa NULL) ficam de fora.
	@Query("""
			SELECT h.etapa.id AS etapaId, COUNT(DISTINCT h.candidato.id) AS total
			FROM Historico h
			WHERE h.vaga.id = :vagaId
			  AND h.etapa IS NOT NULL
			  AND h.acao IN (com.fernando.recruitervisual.vaga.HistoricoAcao.CANDIDATO_CRIADO,
			                 com.fernando.recruitervisual.vaga.HistoricoAcao.CANDIDATO_AVANCADO)
			GROUP BY h.vaga.id, h.etapa.id
			""")
	List<CandidatoRepository.EtapaCandidatesCount> countChegaramByEtapaOfVaga(UUID vagaId);

	// O candidato passou pela etapa (ou está nela) quando existe um evento dessas ações com ela como destino.
	boolean existsByCandidatoIdAndEtapaIdAndAcaoIn(UUID candidatoId, UUID etapaId, Collection<HistoricoAcao> acoes);

}
