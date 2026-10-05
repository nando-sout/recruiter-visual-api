package com.fernando.recruitervisual.vaga;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// Etapa em que o cliente acredita que o candidato está; evita reprovar com estado desatualizado.
public record ReprovarCandidatoRequest(
		@NotNull(message = "é obrigatório")
		UUID etapaId) {
}
