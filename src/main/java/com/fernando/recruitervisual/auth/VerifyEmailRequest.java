package com.fernando.recruitervisual.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(
		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		String email,

		@NotBlank(message = "é obrigatório")
		@Pattern(regexp = "\\d{6}", message = "deve ter exatamente 6 dígitos")
		String code) {

	public VerifyEmailRequest {
		email = EmailNormalizer.normalize(email);
	}

}
