package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// Ordem final completa das etapas da vaga.
public record ReorderEtapasRequest(
		@NotNull(message = "é obrigatório")
		List<@NotNull(message = "não pode conter valores nulos") UUID> etapaIds) {
}
