package com.fernando.recruitervisual.auth;

import java.util.UUID;

public record LoginResponse(
		String message,
		String token,
		UUID recruiterId,
		String name,
		String email) {
}
