package com.fernando.recruitervisual.vaga;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// Etapa em que o cliente acredita que o candidato está; evita avançar duas vezes com estado desatualizado.
public record AdvanceCandidatoRequest(
		@NotNull(message = "é obrigatório")
		UUID etapaId) {
}
