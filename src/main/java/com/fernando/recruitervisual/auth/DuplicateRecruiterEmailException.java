package com.fernando.recruitervisual.auth;

public class DuplicateRecruiterEmailException extends RuntimeException {

	public DuplicateRecruiterEmailException() {
		super("Este e-mail já está cadastrado");
	}

}
