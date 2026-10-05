package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Usado tanto para criar quanto para atualizar a avaliação do candidato em uma etapa.
public record AvaliacaoRequest(
		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser no mínimo 1")
		@Max(value = 5, message = "deve ser no máximo 5")
		Integer rating,

		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String observacao) {
}
