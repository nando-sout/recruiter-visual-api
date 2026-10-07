package com.fernando.recruitervisual.auth;

// Limite do BCrypt em bytes: uma senha de até 72 caracteres com acentos pode passar de 72 bytes.
public class PasswordTooLongException extends RuntimeException {

	// Campo do request que trouxe a senha, para a resposta de erro apontar o campo certo.
	private final String field;

	public PasswordTooLongException() {
		this("password");
	}

	public PasswordTooLongException(String field) {
		super("deve ter no máximo 72 bytes");
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
