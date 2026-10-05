package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Usado tanto para criar quanto para renomear etapa.
public record EtapaRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 100, message = "deve ter no máximo 100 caracteres")
		String name) {
}
