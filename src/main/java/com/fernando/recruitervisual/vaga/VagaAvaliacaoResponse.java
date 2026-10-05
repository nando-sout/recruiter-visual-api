package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

// Avaliação na listagem da vaga inteira: igual a AvaliacaoResponse, mais o candidato a que pertence.
public record VagaAvaliacaoResponse(
		UUID id,
		UUID candidatoId,
		UUID etapaId,
		String etapaNome,
		int rating,
		String observacao,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	// etapaNome é o nome atual da etapa: acompanha renomeações.
	static VagaAvaliacaoResponse from(CandidatoEtapaAvaliacao avaliacao) {
		return new VagaAvaliacaoResponse(
				avaliacao.getId(),
				avaliacao.getCandidato().getId(),
				avaliacao.getEtapa().getId(),
				avaliacao.getEtapa().getName(),
				avaliacao.getRating(),
				avaliacao.getObservacao(),
				avaliacao.getCreatedAt(),
				avaliacao.getUpdatedAt());
	}

}
