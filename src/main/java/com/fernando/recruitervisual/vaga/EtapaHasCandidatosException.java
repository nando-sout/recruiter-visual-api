package com.fernando.recruitervisual.vaga;

public class EtapaHasCandidatosException extends RuntimeException {

	public EtapaHasCandidatosException() {
		super("Não é possível excluir etapas que possuem candidatos.");
	}

}
