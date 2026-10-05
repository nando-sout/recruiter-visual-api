package com.fernando.recruitervisual.vaga;

public class RecruiterNotFoundException extends RuntimeException {

	public RecruiterNotFoundException() {
		super("Recruiter autenticado não encontrado");
	}

}
