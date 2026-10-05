package com.fernando.recruitervisual.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fernando.recruitervisual.recruiter.Recruiter;
import com.fernando.recruitervisual.recruiter.RecruiterRepository;

@Service
public class AuthService {

	static final Duration VERIFICATION_CODE_VALIDITY = Duration.ofMinutes(15);
	static final int MAX_VERIFICATION_ATTEMPTS = 5;

	// Acima de 72 bytes o BCrypt lança IllegalArgumentException em vez de gerar o hash.
	private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

	// Nome da constraint UNIQUE criada pela V1 em recruiter.email.
	private static final String EMAIL_UNIQUE_CONSTRAINT = "recruiter_email_key";

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final RecruiterRepository recruiterRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final VerificationEmailSender verificationEmailSender;

	public AuthService(RecruiterRepository recruiterRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
			VerificationEmailSender verificationEmailSender) {
		this.recruiterRepository = recruiterRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.verificationEmailSender = verificationEmailSender;
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		Recruiter recruiter = recruiterRepository.findByEmail(request.email())
				.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(request.password(), recruiter.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}

		// Só depois da senha correta, para não revelar a quem não tem a senha que o e-mail está pendente.
		if (recruiter.getEmailVerifiedAt() == null) {
			throw new EmailNotVerifiedException();
		}

		return new LoginResponse(
				"Login realizado com sucesso",
				jwtService.generateToken(recruiter.getId()),
				recruiter.getId(),
				recruiter.getName(),
				recruiter.getEmail());
	}

	// O e-mail é enviado dentro da transação: se o envio falhar, o cadastro é desfeito e pode ser repetido.
	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
			throw new PasswordTooLongException();
		}

		// Verificação antecipada só para a mensagem amigável; a UNIQUE do banco é a garantia final.
		if (recruiterRepository.findByEmail(request.email()).isPresent()) {
			throw new DuplicateRecruiterEmailException();
		}

		Recruiter recruiter = new Recruiter(
				UUID.randomUUID(), request.name(), request.email(), passwordEncoder.encode(request.password()));
		String code = generateVerificationCode();
		applyNewVerificationCode(recruiter, code);

		Recruiter saved;
		try {
			saved = recruiterRepository.saveAndFlush(recruiter);
		} catch (DataIntegrityViolationException ex) {
			if (isEmailUniqueViolation(ex)) {
				throw new DuplicateRecruiterEmailException();
			}
			throw ex;
		}

		verificationEmailSender.sendVerificationCode(saved.getEmail(), code, VERIFICATION_CODE_VALIDITY);

		return new RegisterResponse(
				"Cadastro realizado. Enviamos um código de verificação para o seu e-mail.",
				saved.getId(),
				saved.getName(),
				saved.getEmail());
	}

	// Sem rollback no código inválido: a tentativa incorreta precisa ser gravada.
	@Transactional(noRollbackFor = InvalidVerificationCodeException.class)
	public MessageResponse verifyEmail(VerifyEmailRequest request) {
		Recruiter recruiter = recruiterRepository.findWithLockByEmail(request.email())
				.orElseThrow(InvalidVerificationCodeException::new);

		// Já verificado e código invalidado ou já usado caem aqui, com a mesma resposta genérica.
		if (recruiter.getEmailVerifiedAt() != null || recruiter.getVerificationCodeHash() == null) {
			throw new InvalidVerificationCodeException();
		}
		if (LocalDateTime.now().isAfter(recruiter.getVerificationCodeExpiresAt())) {
			throw new InvalidVerificationCodeException();
		}

		if (!passwordEncoder.matches(request.code(), recruiter.getVerificationCodeHash())) {
			int attempts = recruiter.getVerificationAttempts() + 1;
			recruiter.setVerificationAttempts(attempts);
			if (attempts >= MAX_VERIFICATION_ATTEMPTS) {
				clearVerificationCode(recruiter);
			}
			recruiterRepository.saveAndFlush(recruiter);
			throw new InvalidVerificationCodeException();
		}

		recruiter.setEmailVerifiedAt(LocalDateTime.now());
		clearVerificationCode(recruiter);
		recruiter.setVerificationAttempts(0);
		recruiterRepository.saveAndFlush(recruiter);

		return new MessageResponse("E-mail verificado com sucesso. Você já pode fazer login.");
	}

	// Mesma resposta para e-mail inexistente, já verificado ou pendente, para não revelar cadastros.
	@Transactional
	public MessageResponse resendVerification(ResendVerificationRequest request) {
		recruiterRepository.findWithLockByEmail(request.email())
				.filter(recruiter -> recruiter.getEmailVerifiedAt() == null)
				.ifPresent(recruiter -> {
					String code = generateVerificationCode();
					applyNewVerificationCode(recruiter, code);
					recruiterRepository.saveAndFlush(recruiter);
					verificationEmailSender.sendVerificationCode(recruiter.getEmail(), code, VERIFICATION_CODE_VALIDITY);
				});

		return new MessageResponse(
				"Se o e-mail estiver cadastrado e ainda não verificado, enviaremos um novo código de verificação.");
	}

	private static String generateVerificationCode() {
		return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
	}

	// Substitui qualquer código anterior: só o hash do novo código passa a valer.
	private void applyNewVerificationCode(Recruiter recruiter, String code) {
		recruiter.setVerificationCodeHash(passwordEncoder.encode(code));
		recruiter.setVerificationCodeExpiresAt(LocalDateTime.now().plus(VERIFICATION_CODE_VALIDITY));
		recruiter.setVerificationAttempts(0);
	}

	private static void clearVerificationCode(Recruiter recruiter) {
		recruiter.setVerificationCodeHash(null);
		recruiter.setVerificationCodeExpiresAt(null);
	}

	private static boolean isEmailUniqueViolation(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException cve) {
				return EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName());
			}
		}
		return false;
	}

}
