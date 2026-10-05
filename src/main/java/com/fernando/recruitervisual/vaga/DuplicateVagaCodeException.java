package com.fernando.recruitervisual.vaga;

public class DuplicateVagaCodeException extends RuntimeException {

	public DuplicateVagaCodeException(String code) {
		super("Já existe uma vaga com o código '" + code + "'");
	}

}
