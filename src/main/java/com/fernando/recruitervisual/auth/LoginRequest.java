package com.fernando.recruitervisual.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
		@NotBlank @Email String email,
		@NotBlank String password) {

	// Mesma normalização do cadastro; a senha é mantida exatamente como veio.
	public LoginRequest {
		email = EmailNormalizer.normalize(email);
	}

}
