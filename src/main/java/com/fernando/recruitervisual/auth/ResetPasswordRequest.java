package com.fernando.recruitervisual.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		String email,

		@NotBlank(message = "é obrigatório")
		@Pattern(regexp = "\\d{6}", message = "deve ter exatamente 6 dígitos")
		String code,

		@NotBlank(message = "é obrigatório")
		@Size(min = 8, max = 72, message = "deve ter entre 8 e 72 caracteres")
		String newPassword) {

	// A senha é mantida exatamente como veio.
	public ResetPasswordRequest {
		email = EmailNormalizer.normalize(email);
	}

}
