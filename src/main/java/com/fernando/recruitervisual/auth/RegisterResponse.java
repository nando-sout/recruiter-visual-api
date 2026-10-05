package com.fernando.recruitervisual.auth;

import java.util.UUID;

// Sem token: o recruiter só faz login depois de verificar o e-mail.
public record RegisterResponse(
		String message,
		UUID recruiterId,
		String name,
		String email) {
}
