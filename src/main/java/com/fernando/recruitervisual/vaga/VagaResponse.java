package com.fernando.recruitervisual.vaga;

import java.time.LocalDateTime;
import java.util.UUID;

public record VagaResponse(
		UUID id,
		String code,
		String title,
		String description,
		VagaStatus status,
		UUID recruiterId,
		LocalDateTime createdAt) {

	static VagaResponse from(Vaga vaga) {
		return new VagaResponse(
				vaga.getId(),
				vaga.getCode(),
				vaga.getTitle(),
				vaga.getDescription(),
				vaga.getStatus(),
				vaga.getRecruiter().getId(),
				vaga.getCreatedAt());
	}

}
