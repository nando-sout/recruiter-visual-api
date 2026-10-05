package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.NotNull;

// Valor fora do enum VagaStatus é rejeitado na desserialização do body, antes da validação.
public record UpdateVagaStatusRequest(
		@NotNull(message = "é obrigatório")
		VagaStatus status) {
}
