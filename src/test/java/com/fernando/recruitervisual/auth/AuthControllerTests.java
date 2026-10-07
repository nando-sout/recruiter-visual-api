package com.fernando.recruitervisual.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Testes de integração de POST /auth/register, /auth/verify-email, /auth/resend-verification, /auth/login,
 * /auth/forgot-password e /auth/reset-password contra o PostgreSQL real. Nenhum e-mail é enviado: o
 * VerificationEmailSender é um mock, que também expõe o código gerado.
 * Cada teste usa e-mails com um sufixo próprio e remove os recruiters que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTests {

	private static final String PASSWORD = "senha-segura-123";
	private static final String INVALID_CODE_MESSAGE = "Código inválido ou expirado";
	private static final String RESEND_MESSAGE =
			"Se o e-mail estiver cadastrado e ainda não verificado, enviaremos um novo código de verificação.";
	private static final String NEW_PASSWORD = "nova-senha-segura-456";
	private static final String FORGOT_PASSWORD_MESSAGE =
			"Se o e-mail estiver cadastrado, enviaremos um código para a recuperação de senha.";
	private static final Duration VALIDITY = Duration.ofMinutes(15);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private VerificationEmailSender verificationEmailSender;

	// Todos os e-mails do teste terminam com este sufixo, para a limpeza não tocar em outros dados.
	private String suffix;

	@BeforeEach
	void setUp() {
		suffix = "-" + UUID.randomUUID().toString().substring(0, 8) + "@auth-test.example.com";
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("DELETE FROM recruiter WHERE email LIKE ?", "%" + suffix);
	}

	// ---------- POST /auth/register ----------

	@Test
	void registersRecruiterWithoutReturningToken() throws Exception {
		String email = email("ana");

		register("Ana Souza", email, PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.message").value("Cadastro realizado. Enviamos um código de verificação para o seu e-mail."))
				.andExpect(jsonPath("$.recruiterId").value(idOf(email).toString()))
				.andExpect(jsonPath("$.name").value("Ana Souza"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void storesPasswordOnlyAsBcryptHash() throws Exception {
		String email = email("ana");
		register("Ana", email, PASSWORD).andExpect(status().isCreated());

		String hash = column(email, "password_hash", String.class);
		assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
	}

	@Test
	void createsRecruiterAsNotVerifiedWithZeroAttempts() throws Exception {
		String email = email("ana");
		register("Ana", email, PASSWORD).andExpect(status().isCreated());

		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
		assertThat(column(email, "created_at", LocalDateTime.class)).isNotNull();
	}

	@Test
	void sendsSixDigitCodeByEmail() throws Exception {
		String email = email("ana");
		register("Ana", email, PASSWORD).andExpect(status().isCreated());

		verify(verificationEmailSender, times(1)).sendVerificationCode(eq(email), anyString(), eq(VALIDITY));
		assertThat(lastSentCode(email, 1)).matches("\\d{6}");
	}

	@Test
	void storesOnlyBcryptHashOfCode() throws Exception {
		String email = email("ana");
		register("Ana", email, PASSWORD).andExpect(status().isCreated());
		String code = lastSentCode(email, 1);

		String codeHash = column(email, "verification_code_hash", String.class);
		assertThat(codeHash).isNotEqualTo(code).doesNotContain(code).startsWith("$2");
		assertThat(passwordEncoder.matches(code, codeHash)).isTrue();
	}

	@Test
	void codeExpiresInFifteenMinutes() throws Exception {
		String email = email("ana");
		LocalDateTime before = LocalDateTime.now();
		register("Ana", email, PASSWORD).andExpect(status().isCreated());
		LocalDateTime after = LocalDateTime.now();

		LocalDateTime expiresAt = column(email, "verification_code_expires_at", LocalDateTime.class);
		// Margem de 1s para o arredondamento do TIMESTAMP do banco.
		assertThat(expiresAt).isBetween(before.plus(VALIDITY).minusSeconds(1), after.plus(VALIDITY).plusSeconds(1));
	}

	@Test
	void normalizesNameAndEmail() throws Exception {
		String email = email("ana");

		register("  Ana Souza  ", "  " + email.toUpperCase() + "  ", PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Ana Souza"))
				.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void rejectsDuplicateEmailWithConflict() throws Exception {
		String email = email("ana");
		register("Ana", email, PASSWORD).andExpect(status().isCreated());

		register("Outra Ana", email, "outra-senha-456")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Este e-mail já está cadastrado"));
		// Mesmo e-mail com maiúsculas e espaços também é duplicado.
		register("Outra Ana", " " + email.toUpperCase() + " ", "outra-senha-456")
				.andExpect(status().isConflict());

		assertThat(countByEmail(email)).isEqualTo(1);
		assertThat(column(email, "name", String.class)).isEqualTo("Ana");
		verify(verificationEmailSender, times(1)).sendVerificationCode(eq(email), anyString(), any());
	}

	@Test
	void rejectsPasswordShorterThanEight() throws Exception {
		String email = email("ana");

		register("Ana", email, "1234567")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.password").value("deve ter entre 8 e 72 caracteres"));

		assertThat(countByEmail(email)).isZero();
		verify(verificationEmailSender, never()).sendVerificationCode(anyString(), anyString(), any());
	}

	@Test
	void rejectsPasswordLongerThanSeventyTwo() throws Exception {
		String email = email("ana");

		register("Ana", email, "a".repeat(73))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("deve ter entre 8 e 72 caracteres"));

		assertThat(countByEmail(email)).isZero();
	}

	@Test
	void acceptsPasswordWithExactlyEightAndSeventyTwoCharacters() throws Exception {
		register("Ana", email("oito"), "12345678").andExpect(status().isCreated());
		register("Ana", email("setenta-e-dois"), "a".repeat(72)).andExpect(status().isCreated());
	}

	@Test
	void rejectsPasswordOverSeventyTwoBytesEvenWithinSeventyTwoCharacters() throws Exception {
		String email = email("ana");

		// 37 caracteres, 74 bytes em UTF-8: o BCrypt não aceitaria.
		register("Ana", email, "é".repeat(37))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.password").value("deve ter no máximo 72 bytes"));

		assertThat(countByEmail(email)).isZero();
	}

	@Test
	void rejectsMissingAndInvalidFields() throws Exception {
		mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.email").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.password").value("é obrigatório"));

		register("   ", "nao-e-email", PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.email").value("deve ser um e-mail válido"));
	}

	@Test
	void undoesRegistrationWhenEmailCannotBeSent() throws Exception {
		String email = email("ana");
		doThrow(new MailSendException("SMTP indisponível"))
				.when(verificationEmailSender).sendVerificationCode(anyString(), anyString(), any());

		register("Ana", email, PASSWORD)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message")
						.value("Não foi possível enviar o e-mail de verificação. Tente novamente mais tarde."));

		assertThat(countByEmail(email)).isZero();
	}

	// ---------- POST /auth/verify-email ----------

	@Test
	void correctCodeVerifiesEmail() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);

		verifyEmail(email, code)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("E-mail verificado com sucesso. Você já pode fazer login."))
				.andExpect(jsonPath("$.token").doesNotExist());

		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNotNull();
		assertThat(column(email, "verification_code_hash", String.class)).isNull();
		assertThat(column(email, "verification_code_expires_at", LocalDateTime.class)).isNull();
		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
	}

	@Test
	void verifyAcceptsEmailWithDifferentCaseAndSpaces() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);

		verifyEmail("  " + email.toUpperCase() + "  ", code).andExpect(status().isOk());
	}

	@Test
	void codeCannotBeReused() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		verifyEmail(email, code).andExpect(status().isOk());

		verifyEmail(email, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
	}

	@Test
	void wrongCodeIncrementsAttempts() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);

		verifyEmail(email, wrongCode(code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "verification_attempts", Integer.class)).isEqualTo(1);

		verifyEmail(email, wrongCode(code)).andExpect(status().isBadRequest());
		assertThat(column(email, "verification_attempts", Integer.class)).isEqualTo(2);
		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
	}

	@Test
	void fifthWrongCodeInvalidatesCode() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);

		for (int i = 1; i <= 4; i++) {
			verifyEmail(email, wrongCode(code)).andExpect(status().isBadRequest());
		}
		assertThat(column(email, "verification_code_hash", String.class)).isNotNull();

		verifyEmail(email, wrongCode(code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "verification_attempts", Integer.class)).isEqualTo(5);
		assertThat(column(email, "verification_code_hash", String.class)).isNull();
		assertThat(column(email, "verification_code_expires_at", LocalDateTime.class)).isNull();

		// Nem o código correto vale mais.
		verifyEmail(email, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
	}

	@Test
	void expiredCodeIsRejected() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		jdbcTemplate.update("UPDATE recruiter SET verification_code_expires_at = ? WHERE email = ?",
				LocalDateTime.now().minusMinutes(1), email);

		verifyEmail(email, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));

		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
	}

	@Test
	void unknownEmailGetsSameGenericMessage() throws Exception {
		verifyEmail(email("inexistente"), "123456")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
	}

	@Test
	void verifiedRecruiterCannotUseVerificationFlowAgain() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		verifyEmail(email, code).andExpect(status().isOk());
		LocalDateTime verifiedAt = column(email, "email_verified_at", LocalDateTime.class);

		verifyEmail(email, "123456")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));

		// Reenvio não gera código para quem já verificou, e a resposta é a mesma de sempre.
		resend(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(RESEND_MESSAGE));
		verify(verificationEmailSender, times(1)).sendVerificationCode(eq(email), anyString(), any());
		assertThat(column(email, "verification_code_hash", String.class)).isNull();
		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isEqualTo(verifiedAt);
	}

	@Test
	void rejectsCodeWithoutExactlySixDigits() throws Exception {
		String email = email("ana");
		registerAndGetCode(email);

		for (String code : List.of("12345", "1234567", "12a456", " 123456")) {
			verifyEmail(email, code)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.code").value("deve ter exatamente 6 dígitos"));
		}
		// Formato inválido não conta como tentativa.
		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
	}

	// ---------- POST /auth/resend-verification ----------

	@Test
	void resendGeneratesNewCodeAndInvalidatesPrevious() throws Exception {
		String email = email("ana");
		String firstCode = registerAndGetCode(email);
		String firstHash = column(email, "verification_code_hash", String.class);
		jdbcTemplate.update("UPDATE recruiter SET verification_code_expires_at = ? WHERE email = ?",
				LocalDateTime.now().plusMinutes(1), email);

		LocalDateTime before = LocalDateTime.now();
		resend(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

		String secondCode = lastSentCode(email, 2);
		assertThat(secondCode).matches("\\d{6}");
		String secondHash = column(email, "verification_code_hash", String.class);
		assertThat(secondHash).isNotEqualTo(firstHash);
		assertThat(passwordEncoder.matches(secondCode, secondHash)).isTrue();
		assertThat(column(email, "verification_code_expires_at", LocalDateTime.class))
				.isAfterOrEqualTo(before.plus(VALIDITY).minusSeconds(1));

		if (!firstCode.equals(secondCode)) {
			verifyEmail(email, firstCode).andExpect(status().isBadRequest());
		}
		verifyEmail(email, secondCode).andExpect(status().isOk());
	}

	@Test
	void resendResetsAttempts() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		verifyEmail(email, wrongCode(code)).andExpect(status().isBadRequest());
		verifyEmail(email, wrongCode(code)).andExpect(status().isBadRequest());
		assertThat(column(email, "verification_attempts", Integer.class)).isEqualTo(2);

		resend(email).andExpect(status().isOk());

		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
	}

	@Test
	void resendRestoresCodeInvalidatedByFiveWrongAttempts() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		for (int i = 1; i <= 5; i++) {
			verifyEmail(email, wrongCode(code)).andExpect(status().isBadRequest());
		}

		resend(email).andExpect(status().isOk());

		verifyEmail(email, lastSentCode(email, 2)).andExpect(status().isOk());
	}

	@Test
	void resendForUnknownEmailGetsSameResponseAndSendsNothing() throws Exception {
		resend(email("inexistente"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(RESEND_MESSAGE));

		verify(verificationEmailSender, never()).sendVerificationCode(anyString(), anyString(), any());
	}

	// ---------- POST /auth/login ----------

	@Test
	void loginWithCorrectPasswordButUnverifiedEmailReturnsForbidden() throws Exception {
		String email = email("ana");
		registerAndGetCode(email);

		login(email, PASSWORD)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message")
						.value("E-mail ainda não verificado. Confirme o código enviado para o seu e-mail antes de entrar."))
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void loginWithWrongPasswordOnUnverifiedEmailStillReturnsInvalidCredentials() throws Exception {
		String email = email("ana");
		registerAndGetCode(email);

		// Sem a senha correta, não se descobre que o e-mail está pendente.
		login(email, "senha-errada-999")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("E-mail ou senha inválidos"));
	}

	@Test
	void loginAfterVerificationReturnsJwt() throws Exception {
		String email = email("ana");
		String code = registerAndGetCode(email);
		verifyEmail(email, code).andExpect(status().isOk());
		UUID id = idOf(email);

		String body = login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Login realizado com sucesso"))
				.andExpect(jsonPath("$.recruiterId").value(id.toString()))
				.andExpect(jsonPath("$.email").value(email))
				.andReturn().getResponse().getContentAsString();

		String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
		assertThat(jwtService.extractRecruiterId(token)).contains(id);
	}

	@Test
	void loginWithEmailExactlyAsRegistered() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void loginNormalizesEmailCase() throws Exception {
		String email = email("ana");
		// Cadastro com maiúsculas: fica gravado em minúsculas.
		register("Ana", "Ana" + suffix.toUpperCase(), PASSWORD).andExpect(status().isCreated());
		verifyEmail(email, lastSentCode(email, 1)).andExpect(status().isOk());
		UUID id = idOf(email);

		for (String typed : List.of(email.toUpperCase(), "aNa" + suffix.toUpperCase(), "Ana" + suffix)) {
			login(typed, PASSWORD)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.recruiterId").value(id.toString()))
					.andExpect(jsonPath("$.email").value(email))
					.andExpect(jsonPath("$.token").isNotEmpty());
		}
	}

	@Test
	void loginTrimsSpacesAroundEmail() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		for (String typed : List.of("  " + email, email + "  ", "  " + email.toUpperCase() + "  ")) {
			login(typed, PASSWORD)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.email").value(email))
					.andExpect(jsonPath("$.token").isNotEmpty());
		}
	}

	@Test
	void loginWithInvalidCredentialsStillReturnsUnauthorized() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		// Senha errada, com e-mail exato e com e-mail normalizável.
		for (String typed : List.of(email, "  " + email.toUpperCase() + "  ")) {
			login(typed, "senha-errada-999")
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value("E-mail ou senha inválidos"))
					.andExpect(jsonPath("$.token").doesNotExist());
		}
		// E-mail não cadastrado, também em maiúsculas.
		login(email("inexistente").toUpperCase(), PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("E-mail ou senha inválidos"));
		// Senha é comparada exatamente: maiúsculas e espaços não são normalizados.
		login(email, PASSWORD.toUpperCase()).andExpect(status().isUnauthorized());
		login(email, " " + PASSWORD + " ").andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithInvalidEmailFormatStillReturnsBadRequest() throws Exception {
		login("nao-e-email", PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.email").exists());
		login("   ", PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").exists());
	}

	@Test
	void migrationMarkedAllPreexistingRecruitersAsVerified() {
		// Todo recruiter criado antes da V8 precisa estar verificado, senão perderia o acesso.
		Integer unverifiedBeforeV8 = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM recruiter
				WHERE email_verified_at IS NULL
				  AND created_at < (SELECT installed_on FROM flyway_schema_history WHERE version = '8')
				""", Integer.class);
		assertThat(unverifiedBeforeV8).isZero();
	}

	@Test
	void existingVerifiedRecruiterStillLogsIn() throws Exception {
		// Recruiter no formato anterior ao cadastro (inserido direto no banco, como o seeder),
		// no estado em que a V8 deixa os existentes: verificado e sem código.
		String email = email("existente");
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, email, passwordEncoder.encode(PASSWORD), "Existente");
		assertThat(column(email, "verification_attempts", Integer.class)).isZero();
		jdbcTemplate.update("UPDATE recruiter SET email_verified_at = NOW() WHERE id = ?", id);

		login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recruiterId").value(id.toString()))
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	// ---------- POST /auth/forgot-password ----------

	@Test
	void forgotPasswordSendsSixDigitCodeByEmail() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		forgotPassword(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(FORGOT_PASSWORD_MESSAGE))
				.andExpect(jsonPath("$.code").doesNotExist());

		verify(verificationEmailSender, times(1)).sendPasswordResetCode(eq(email), anyString(), eq(VALIDITY));
		assertThat(lastSentResetCode(email, 1)).matches("\\d{6}");
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
	}

	@Test
	void forgotPasswordStoresOnlyBcryptHashOfCode() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		String codeHash = column(email, "password_reset_code_hash", String.class);
		assertThat(codeHash).isNotEqualTo(code).doesNotContain(code).startsWith("$2");
		assertThat(passwordEncoder.matches(code, codeHash)).isTrue();
	}

	@Test
	void resetCodeExpiresInFifteenMinutes() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		LocalDateTime before = LocalDateTime.now();
		forgotPassword(email).andExpect(status().isOk());
		LocalDateTime after = LocalDateTime.now();

		LocalDateTime expiresAt = column(email, "password_reset_code_expires_at", LocalDateTime.class);
		// Margem de 1s para o arredondamento do TIMESTAMP do banco.
		assertThat(expiresAt).isBetween(before.plus(VALIDITY).minusSeconds(1), after.plus(VALIDITY).plusSeconds(1));
	}

	@Test
	void forgotPasswordAcceptsEmailWithDifferentCaseAndSpaces() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		forgotPassword("  " + email.toUpperCase() + "  ").andExpect(status().isOk());

		verify(verificationEmailSender, times(1)).sendPasswordResetCode(eq(email), anyString(), eq(VALIDITY));
	}

	@Test
	void forgotPasswordForUnknownEmailGetsSameResponseAndSendsNothing() throws Exception {
		forgotPassword(email("inexistente"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(FORGOT_PASSWORD_MESSAGE));

		verify(verificationEmailSender, never()).sendPasswordResetCode(anyString(), anyString(), any());
	}

	@Test
	void forgotPasswordWithinCooldownKeepsCurrentCodeAndSendsNothing() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);
		String hash = column(email, "password_reset_code_hash", String.class);
		LocalDateTime expiresAt = column(email, "password_reset_code_expires_at", LocalDateTime.class);

		// Segundo pedido dentro dos 60 segundos: mesma resposta, nenhum código novo.
		forgotPassword(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(FORGOT_PASSWORD_MESSAGE));

		verify(verificationEmailSender, times(1)).sendPasswordResetCode(eq(email), anyString(), any());
		assertThat(column(email, "password_reset_code_hash", String.class)).isEqualTo(hash);
		assertThat(column(email, "password_reset_code_expires_at", LocalDateTime.class)).isEqualTo(expiresAt);
		resetPassword(email, code, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void forgotPasswordAfterCooldownGeneratesNewCodeAndInvalidatesPrevious() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String firstCode = requestResetCode(email);
		String firstHash = column(email, "password_reset_code_hash", String.class);
		resetPassword(email, wrongCode(firstCode), NEW_PASSWORD).andExpect(status().isBadRequest());
		passResetCooldown(email);

		forgotPassword(email).andExpect(status().isOk());

		String secondCode = lastSentResetCode(email, 2);
		String secondHash = column(email, "password_reset_code_hash", String.class);
		assertThat(secondHash).isNotEqualTo(firstHash);
		assertThat(passwordEncoder.matches(secondCode, secondHash)).isTrue();
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();

		if (!firstCode.equals(secondCode)) {
			resetPassword(email, firstCode, NEW_PASSWORD).andExpect(status().isBadRequest());
		}
		resetPassword(email, secondCode, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void cooldownStillAppliesAfterCodeIsInvalidatedByWrongAttempts() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);
		for (int i = 1; i <= 5; i++) {
			resetPassword(email, wrongCode(code), NEW_PASSWORD).andExpect(status().isBadRequest());
		}

		// Esgotar as tentativas não libera um código novo antes dos 60 segundos.
		forgotPassword(email).andExpect(status().isOk());
		verify(verificationEmailSender, times(1)).sendPasswordResetCode(eq(email), anyString(), any());
		assertThat(column(email, "password_reset_code_hash", String.class)).isNull();

		passResetCooldown(email);
		forgotPassword(email).andExpect(status().isOk());
		resetPassword(email, lastSentResetCode(email, 2), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void forgotPasswordDoesNotRevealEmailFailure() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		doThrow(new MailSendException("SMTP indisponível"))
				.doNothing()
				.when(verificationEmailSender).sendPasswordResetCode(anyString(), anyString(), any());

		// Mesma resposta de um e-mail inexistente, sem 503 e sem detalhe do erro.
		forgotPassword(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(FORGOT_PASSWORD_MESSAGE))
				.andExpect(jsonPath("$.length()").value(1));

		// O código que não foi entregue é descartado.
		assertThat(column(email, "password_reset_code_hash", String.class)).isNull();
		assertThat(column(email, "password_reset_code_expires_at", LocalDateTime.class)).isNull();
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();

		// E um novo pedido não fica preso no cooldown.
		forgotPassword(email).andExpect(status().isOk());
		resetPassword(email, lastSentResetCode(email, 2), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void forgotPasswordRejectsMissingAndInvalidEmail() throws Exception {
		mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("é obrigatório"));
		forgotPassword("nao-e-email")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("deve ser um e-mail válido"));
	}

	// ---------- POST /auth/reset-password ----------

	@Test
	void correctCodeChangesPassword() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		resetPassword(email, code, NEW_PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Senha alterada com sucesso. Você já pode fazer login com a nova senha."))
				.andExpect(jsonPath("$.token").doesNotExist());

		String hash = column(email, "password_hash", String.class);
		assertThat(hash).isNotEqualTo(NEW_PASSWORD).startsWith("$2");
		assertThat(passwordEncoder.matches(NEW_PASSWORD, hash)).isTrue();
		assertThat(column(email, "password_reset_code_hash", String.class)).isNull();
		assertThat(column(email, "password_reset_code_expires_at", LocalDateTime.class)).isNull();
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();

		// A senha antiga deixa de funcionar; a nova entra.
		login(email, PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("E-mail ou senha inválidos"));
		login(email, NEW_PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void resetAcceptsEmailWithDifferentCaseAndSpaces() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		resetPassword("  " + email.toUpperCase() + "  ", code, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void resetCodeCannotBeReused() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);
		resetPassword(email, code, NEW_PASSWORD).andExpect(status().isOk());

		resetPassword(email, code, "outra-senha-456")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));

		assertThat(passwordEncoder.matches(NEW_PASSWORD, column(email, "password_hash", String.class))).isTrue();
	}

	@Test
	void wrongResetCodeIncrementsAttempts() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		resetPassword(email, wrongCode(code), NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "password_reset_attempts", Integer.class)).isEqualTo(1);

		resetPassword(email, wrongCode(code), NEW_PASSWORD).andExpect(status().isBadRequest());
		assertThat(column(email, "password_reset_attempts", Integer.class)).isEqualTo(2);
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();
	}

	@Test
	void fifthWrongResetCodeInvalidatesCode() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		for (int i = 1; i <= 4; i++) {
			resetPassword(email, wrongCode(code), NEW_PASSWORD).andExpect(status().isBadRequest());
		}
		assertThat(column(email, "password_reset_code_hash", String.class)).isNotNull();

		resetPassword(email, wrongCode(code), NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "password_reset_attempts", Integer.class)).isEqualTo(5);
		assertThat(column(email, "password_reset_code_hash", String.class)).isNull();

		// Nem o código correto vale mais, e a senha continua a mesma.
		resetPassword(email, code, NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();
	}

	@Test
	void expiredResetCodeIsRejected() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);
		jdbcTemplate.update("UPDATE recruiter SET password_reset_code_expires_at = ? WHERE email = ?",
				LocalDateTime.now().minusMinutes(1), email);

		resetPassword(email, code, NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));

		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();
	}

	@Test
	void resetWithoutRequestedCodeGetsSameGenericMessage() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		// Conta existente que nunca pediu recuperação.
		resetPassword(email, "123456", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();

		// E-mail não cadastrado.
		resetPassword(email("inexistente"), "123456", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(INVALID_CODE_MESSAGE));
	}

	@Test
	void resetRejectsInvalidPasswordAndCodeFormat() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		for (String password : List.of("1234567", "a".repeat(73))) {
			resetPassword(email, code, password)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Dados inválidos"))
					.andExpect(jsonPath("$.errors.newPassword").value("deve ter entre 8 e 72 caracteres"));
		}
		for (String invalidCode : List.of("12345", "1234567", "12a456", " 123456")) {
			resetPassword(email, invalidCode, NEW_PASSWORD)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.code").value("deve ter exatamente 6 dígitos"));
		}
		mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.code").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.newPassword").value("é obrigatório"));

		// Dados inválidos não contam como tentativa nem trocam a senha; o código continua valendo.
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();
		resetPassword(email, code, "a".repeat(72)).andExpect(status().isOk());
	}

	@Test
	void resetRejectsPasswordOverSeventyTwoBytesEvenWithinSeventyTwoCharacters() throws Exception {
		String email = email("ana");
		registerAndVerify(email);
		String code = requestResetCode(email);

		// 37 caracteres, 74 bytes em UTF-8: o BCrypt não aceitaria.
		resetPassword(email, code, "é".repeat(37))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.newPassword").value("deve ter no máximo 72 bytes"));

		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
		assertThat(column(email, "password_reset_code_hash", String.class)).isNotNull();
		assertThat(passwordEncoder.matches(PASSWORD, column(email, "password_hash", String.class))).isTrue();
	}

	@Test
	void unverifiedAccountCanResetPasswordButStaysUnverified() throws Exception {
		String email = email("ana");
		String verificationCode = registerAndGetCode(email);
		String verificationHash = column(email, "verification_code_hash", String.class);

		String resetCode = requestResetCode(email);
		resetPassword(email, resetCode, NEW_PASSWORD).andExpect(status().isOk());

		assertThat(passwordEncoder.matches(NEW_PASSWORD, column(email, "password_hash", String.class))).isTrue();
		assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
		assertThat(column(email, "verification_code_hash", String.class)).isEqualTo(verificationHash);

		// O login com a nova senha continua exigindo a verificação do e-mail.
		login(email, NEW_PASSWORD).andExpect(status().isForbidden());
		verifyEmail(email, verificationCode).andExpect(status().isOk());
		login(email, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void resetCodeDoesNotVerifyEmail() throws Exception {
		String email = email("ana");
		String verificationCode = registerAndGetCode(email);
		String resetCode = requestResetCode(email);

		// Os dois códigos são independentes: o de recuperação não serve em /auth/verify-email.
		if (!resetCode.equals(verificationCode)) {
			verifyEmail(email, resetCode).andExpect(status().isBadRequest());
			assertThat(column(email, "email_verified_at", LocalDateTime.class)).isNull();
		}
		assertThat(column(email, "password_reset_attempts", Integer.class)).isZero();
	}

	// ---------- Segurança ----------

	@Test
	void registerVerifyAndResendArePublic() throws Exception {
		String email = email("ana");

		// Nenhuma das chamadas envia Authorization.
		register("Ana", email, PASSWORD).andExpect(status().isCreated());
		verifyEmail(email, wrongCode(lastSentCode(email, 1))).andExpect(status().isBadRequest());
		resend(email).andExpect(status().isOk());
	}

	@Test
	void forgotAndResetPasswordArePublic() throws Exception {
		String email = email("ana");
		registerAndVerify(email);

		// Nenhuma das chamadas envia Authorization.
		forgotPassword(email).andExpect(status().isOk());
		resetPassword(email, lastSentResetCode(email, 1), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void otherRoutesStayProtected() throws Exception {
		mockMvc.perform(get("/vagas")).andExpect(status().isUnauthorized());
		// Só o POST das rotas de auth é público.
		mockMvc.perform(get("/auth/register")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/auth/verify-email")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/auth/forgot-password")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/auth/reset-password")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/outra-rota").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().is(not(401)));
	}

	// ---------- Helpers ----------

	private String email(String label) {
		return label + suffix;
	}

	private String registerAndGetCode(String email) throws Exception {
		register("Ana", email, PASSWORD).andExpect(status().isCreated());
		return lastSentCode(email, 1);
	}

	private void registerAndVerify(String email) throws Exception {
		verifyEmail(email, registerAndGetCode(email)).andExpect(status().isOk());
	}

	// Código do último e-mail enviado para o endereço; confere também quantos foram enviados.
	private String lastSentCode(String email, int expectedSends) {
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(verificationEmailSender, times(expectedSends)).sendVerificationCode(eq(email), code.capture(), eq(VALIDITY));
		return code.getValue();
	}

	private String requestResetCode(String email) throws Exception {
		forgotPassword(email).andExpect(status().isOk());
		return lastSentResetCode(email, 1);
	}

	// Código do último e-mail de recuperação enviado para o endereço; confere também quantos foram enviados.
	private String lastSentResetCode(String email, int expectedSends) {
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(verificationEmailSender, times(expectedSends)).sendPasswordResetCode(eq(email), code.capture(), eq(VALIDITY));
		return code.getValue();
	}

	// Recua a geração do código atual para antes do cooldown de 60 segundos, como se o tempo tivesse passado.
	private void passResetCooldown(String email) {
		jdbcTemplate.update("UPDATE recruiter SET password_reset_code_expires_at = ? WHERE email = ?",
				LocalDateTime.now().plus(VALIDITY).minusSeconds(61), email);
	}

	private static String wrongCode(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private ResultActions register(String name, String email, String password) throws Exception {
		return postJson("/auth/register", Map.of("name", name, "email", email, "password", password));
	}

	private ResultActions verifyEmail(String email, String code) throws Exception {
		return postJson("/auth/verify-email", Map.of("email", email, "code", code));
	}

	private ResultActions resend(String email) throws Exception {
		return postJson("/auth/resend-verification", Map.of("email", email));
	}

	private ResultActions forgotPassword(String email) throws Exception {
		return postJson("/auth/forgot-password", Map.of("email", email));
	}

	private ResultActions resetPassword(String email, String code, String newPassword) throws Exception {
		return postJson("/auth/reset-password", Map.of("email", email, "code", code, "newPassword", newPassword));
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/auth/login", Map.of("email", email, "password", password));
	}

	private ResultActions postJson(String path, Map<String, String> fields) throws Exception {
		StringBuilder json = new StringBuilder("{");
		fields.forEach((key, value) -> {
			if (json.length() > 1) {
				json.append(',');
			}
			json.append('"').append(key).append("\":\"").append(value).append('"');
		});
		json.append('}');
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.toString()));
	}

	private <T> T column(String email, String column, Class<T> type) {
		return jdbcTemplate.queryForObject("SELECT " + column + " FROM recruiter WHERE email = ?", type, email);
	}

	private UUID idOf(String email) {
		return column(email, "id", UUID.class);
	}

	private int countByEmail(String email) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recruiter WHERE email = ?", Integer.class, email);
	}

}
