package com.fernando.recruitervisual.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/verify-email")
	public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		return authService.verifyEmail(request);
	}

	@PostMapping("/resend-verification")
	public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		return authService.resendVerification(request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return ResponseEntity.badRequest().body(Map.of("message", "Dados inválidos", "errors", errors));
	}

	// Mesmo formato da validação de campos: é uma regra da senha, só verificável em bytes.
	@ExceptionHandler(PasswordTooLongException.class)
	public ResponseEntity<Map<String, Object>> handlePasswordTooLong(PasswordTooLongException ex) {
		return ResponseEntity.badRequest()
				.body(Map.of("message", "Dados inválidos", "errors", Map.of("password", ex.getMessage())));
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(EmailNotVerifiedException.class)
	public ResponseEntity<Map<String, String>> handleEmailNotVerified(EmailNotVerifiedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(DuplicateRecruiterEmailException.class)
	public ResponseEntity<Map<String, String>> handleDuplicateEmail(DuplicateRecruiterEmailException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvalidVerificationCodeException.class)
	public ResponseEntity<Map<String, String>> handleInvalidVerificationCode(InvalidVerificationCodeException ex) {
		return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(MailException.class)
	public ResponseEntity<Map<String, String>> handleMail(MailException ex) {
		log.error("Falha ao enviar e-mail de verificação", ex);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("message", "Não foi possível enviar o e-mail de verificação. Tente novamente mais tarde."));
	}

}
