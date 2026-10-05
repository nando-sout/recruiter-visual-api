package com.fernando.recruitervisual.vaga;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateVagaRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 150, message = "deve ter no máximo 150 caracteres")
		String title,

		@NotBlank(message = "é obrigatório")
		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String description) {
}
