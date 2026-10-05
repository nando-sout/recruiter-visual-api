package com.fernando.recruitervisual.auth;

import java.util.Locale;

/**
 * E-mail do cadastro, da verificação e do login: sem espaços nas pontas e em minúsculas.
 * Aplicado nos construtores dos requests, antes da validação.
 */
final class EmailNormalizer {

	private EmailNormalizer() {
	}

	static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

}
