package com.fernando.recruitervisual.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		String email) {

	public ForgotPasswordRequest {
		email = EmailNormalizer.normalize(email);
	}

}
