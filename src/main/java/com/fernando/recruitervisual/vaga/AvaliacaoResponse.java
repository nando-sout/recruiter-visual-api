package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

public record AvaliacaoResponse(
		UUID id,
		UUID etapaId,
		String etapaNome,
		int rating,
		String observacao,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	// etapaNome é o nome atual da etapa: acompanha renomeações.
	static AvaliacaoResponse from(CandidatoEtapaAvaliacao avaliacao) {
		return new AvaliacaoResponse(
				avaliacao.getId(),
				avaliacao.getEtapa().getId(),
				avaliacao.getEtapa().getName(),
				avaliacao.getRating(),
				avaliacao.getObservacao(),
				avaliacao.getCreatedAt(),
				avaliacao.getUpdatedAt());
	}

}
