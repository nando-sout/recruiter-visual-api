package com.fernando.recruitervisual.vaga;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVagaRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 255, message = "deve ter no máximo 255 caracteres")
		String code,

		@NotBlank(message = "é obrigatório")
		@Size(max = 150, message = "deve ter no máximo 150 caracteres")
		String title,

		@NotBlank(message = "é obrigatório")
		@Size(max = 5000, message = "deve ter no máximo 5000 caracteres")
		String description,

		// Opcional, já na ordem final. Ausente (ou null), a vaga recebe as etapas padrão.
		List<@NotNull(message = "não pode conter valores nulos") @Valid CreateVagaEtapaRequest> etapas) {
}
