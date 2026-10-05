package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Etapa configurada na criação da vaga. Sem id nem posição: o backend gera o id e a posição vem da ordem da lista.
public record CreateVagaEtapaRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 100, message = "deve ter no máximo 100 caracteres")
		String name,

		@NotNull(message = "é obrigatório")
		Boolean proposta) {
}
