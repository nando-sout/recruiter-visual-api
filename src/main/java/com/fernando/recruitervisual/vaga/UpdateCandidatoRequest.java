package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Substitui todos os dados editáveis: campo opcional ausente (ou null) fica vazio no candidato.
// Sem etapaId nem dados de reprovação: a edição não movimenta o candidato.
public record UpdateCandidatoRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 150, message = "deve ter no máximo 150 caracteres")
		String name,

		@Size(max = 500, message = "deve ter no máximo 500 caracteres")
		String linkedin,

		@NotBlank(message = "é obrigatório")
		@Size(max = 500, message = "deve ter no máximo 500 caracteres")
		String stack,

		// Null significa "sem nota"; 0 não é uma nota válida na edição.
		@Min(value = 1, message = "deve ser no mínimo 1")
		@Max(value = 5, message = "deve ser no máximo 5")
		Integer rating,

		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String linkedinAbout,

		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String recruiterOpinion,

		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String technicalOpinion) {
}
