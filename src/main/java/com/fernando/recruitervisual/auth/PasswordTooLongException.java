package com.fernando.recruitervisual.auth;

// Limite do BCrypt em bytes: uma senha de até 72 caracteres com acentos pode passar de 72 bytes.
public class PasswordTooLongException extends RuntimeException {

	public PasswordTooLongException() {
		super("deve ter no máximo 72 bytes");
	}

}
