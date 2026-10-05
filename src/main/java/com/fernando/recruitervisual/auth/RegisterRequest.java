package com.fernando.recruitervisual.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 255, message = "deve ter no máximo 255 caracteres")
		String name,

		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		@Size(max = 255, message = "deve ter no máximo 255 caracteres")
		String email,

		@NotBlank(message = "é obrigatório")
		@Size(min = 8, max = 72, message = "deve ter entre 8 e 72 caracteres")
		String password) {

	// A senha é mantida exatamente como veio.
	public RegisterRequest {
		name = name == null ? null : name.strip();
		email = EmailNormalizer.normalize(email);
	}

}
