package com.fernando.recruitervisual.auth;

public class EmailNotVerifiedException extends RuntimeException {

	public EmailNotVerifiedException() {
		super("E-mail ainda não verificado. Confirme o código enviado para o seu e-mail antes de entrar.");
	}

}
