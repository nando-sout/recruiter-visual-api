package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

public record CandidatoResponse(
		UUID id,
		UUID vagaId,
		UUID etapaId,
		String name,
		String linkedin,
		String stack,
		Integer rating,
		String linkedinAbout,
		String recruiterOpinion,
		String technicalOpinion,
		boolean reprovado,
		UUID etapaReprovacaoId,
		String etapaReprovacaoNome,
		LocalDateTime createdAt) {

	static CandidatoResponse from(Candidato candidato) {
		return new CandidatoResponse(
				candidato.getId(),
				candidato.getVaga().getId(),
				candidato.getEtapa().getId(),
				candidato.getName(),
				candidato.getLinkedin(),
				candidato.getStack(),
				candidato.getRating(),
				candidato.getLinkedinAbout(),
				candidato.getRecruiterOpinion(),
				candidato.getTechnicalOpinion(),
				candidato.isReprovado(),
				candidato.getEtapaReprovacao() == null ? null : candidato.getEtapaReprovacao().getId(),
				candidato.getEtapaReprovacaoNome(),
				candidato.getCreatedAt());
	}

}
