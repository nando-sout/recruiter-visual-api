package com.fernando.recruitervisual.vaga;

public class VagaNotFoundException extends RuntimeException {

	public VagaNotFoundException() {
		super("Vaga não encontrada");
	}

}
