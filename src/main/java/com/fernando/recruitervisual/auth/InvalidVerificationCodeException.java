package com.fernando.recruitervisual.auth;

// Mesma mensagem para código errado, expirado, invalidado, e-mail inexistente ou já verificado.
public class InvalidVerificationCodeException extends RuntimeException {

	public InvalidVerificationCodeException() {
		super("Código inválido ou expirado");
	}

}
