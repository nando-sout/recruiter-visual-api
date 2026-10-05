package com.fernando.recruitervisual.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * jwt.secret vem da variável de ambiente JWT_SECRET (nunca do código ou do application.properties).
 * jwt.expiration é definido no application.properties.
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
		@NotBlank(message = "a variável de ambiente JWT_SECRET não está definida") String secret,
		@NotNull(message = "jwt.expiration não está configurado") Duration expiration) {
}
