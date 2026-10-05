package com.fernando.recruitervisual.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

	// HS256 exige chave de pelo menos 256 bits.
	private static final int MIN_SECRET_BYTES = 32;

	private final SecretKey signingKey;
	private final Duration expiration;

	public JwtService(JwtProperties properties) {
		byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (secretBytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"JWT_SECRET deve ter pelo menos " + MIN_SECRET_BYTES + " bytes (256 bits) para HS256.");
		}
		this.signingKey = Keys.hmacShaKeyFor(secretBytes);
		this.expiration = properties.expiration();
	}

	public String generateToken(UUID recruiterId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(recruiterId.toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(expiration)))
				.signWith(signingKey, Jwts.SIG.HS256)
				.compact();
	}

	/**
	 * Valida assinatura e expiração e devolve o recruiterId do token.
	 * Retorna vazio para qualquer token ausente, malformado, adulterado ou expirado.
	 */
	public Optional<UUID> extractRecruiterId(String token) {
		try {
			Claims claims = Jwts.parser()
					.verifyWith(signingKey)
					.build()
					.parseSignedClaims(token)
					.getPayload();

			if (claims.getSubject() == null || claims.getExpiration() == null) {
				return Optional.empty();
			}
			return Optional.of(UUID.fromString(claims.getSubject()));
		} catch (JwtException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

}
