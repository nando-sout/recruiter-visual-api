package com.fernando.recruitervisual.vaga;

public class InvalidEtapaOrderException extends RuntimeException {

	public InvalidEtapaOrderException() {
		super("A nova ordem deve conter exatamente todas as etapas da vaga, sem repetições");
	}

}
