package com.fernando.recruitervisual.vaga;

public class CandidatoNotFoundException extends RuntimeException {

	public CandidatoNotFoundException() {
		super("Candidato não encontrado");
	}

}
