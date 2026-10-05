package com.fernando.recruitervisual.vaga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fernando.recruitervisual.auth.JwtService;

/**
 * Testes de integração de POST /vagas, GET /vagas, GET /vagas/{id}, PUT /vagas/{id} e PUT /vagas/{vagaId}/status
 * contra o PostgreSQL real.
 * Cada teste cria seus próprios recruiters e remove tudo o que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VagaControllerTests {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtService jwtService;

	private UUID recruiterA;
	private UUID recruiterB;
	// Sufixo aleatório para não colidir com vagas reais do banco de desenvolvimento.
	private String suffix;

	@BeforeEach
	void setUp() {
		recruiterA = insertRecruiter("A");
		recruiterB = insertRecruiter("B");
		suffix = UUID.randomUUID().toString().substring(0, 8);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("DELETE FROM vaga WHERE recruiter_id IN (?, ?)", recruiterA, recruiterB);
		jdbcTemplate.update("DELETE FROM recruiter WHERE id IN (?, ?)", recruiterA, recruiterB);
	}

	@Test
	void createsVagaForAuthenticatedRecruiter() throws Exception {
		postVaga(tokenFor(recruiterA), vagaJson("JAVA-001-" + suffix, "Desenvolvedor Java Sênior",
				"Vaga para desenvolvimento backend."))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$.code").value("JAVA-001-" + suffix))
				.andExpect(jsonPath("$.title").value("Desenvolvedor Java Sênior"))
				.andExpect(jsonPath("$.description").value("Vaga para desenvolvimento backend."))
				.andExpect(jsonPath("$.status").value("ATUANDO"))
				.andExpect(jsonPath("$.recruiterId").value(recruiterA.toString()))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.recruiter").doesNotExist());

		assertThat(recruiterIdOfVaga("JAVA-001-" + suffix)).isEqualTo(recruiterA);
	}

	@Test
	void rejectsRequestWithoutToken() throws Exception {
		mockMvc.perform(post("/vagas")
				.contentType(MediaType.APPLICATION_JSON)
				.content(vagaJson("JAVA-001-" + suffix, "Título", "Descrição")))
				.andExpect(status().isUnauthorized());

		assertThat(countVagas("JAVA-001-" + suffix)).isZero();
	}

	@Test
	void rejectsDuplicateCodeFromSameOrAnotherRecruiter() throws Exception {
		String code = "JAVA-001-" + suffix;
		postVaga(tokenFor(recruiterA), vagaJson(code, "Desenvolvedor Java Sênior", "Vaga para desenvolvimento backend."))
				.andExpect(status().isCreated());

		postVaga(tokenFor(recruiterA), vagaJson(code, "Outra vaga", "Outra descrição."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(containsString("Já existe uma vaga com o código")));

		postVaga(tokenFor(recruiterB), vagaJson(code, "Outra vaga", "Outra descrição."))
				.andExpect(status().isConflict());

		assertThat(countVagas(code)).isEqualTo(1);
	}

	@Test
	void rejectsTitleLongerThan150() throws Exception {
		postVaga(tokenFor(recruiterA), vagaJson("JAVA-001-" + suffix, "a".repeat(151), "Descrição"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("deve ter no máximo 150 caracteres"));
	}

	@Test
	void rejectsDescriptionLongerThan5000() throws Exception {
		postVaga(tokenFor(recruiterA), vagaJson("JAVA-001-" + suffix, "Título", "a".repeat(5001)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.description").value("deve ter no máximo 5000 caracteres"));
	}

	@Test
	void acceptsTitleAndDescriptionAtMaximumLength() throws Exception {
		postVaga(tokenFor(recruiterA), vagaJson("JAVA-001-" + suffix, "a".repeat(150), "a".repeat(5000)))
				.andExpect(status().isCreated());
	}

	@Test
	void rejectsMissingFields() throws Exception {
		postVaga(tokenFor(recruiterA), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.code").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.title").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.description").value("é obrigatório"));
	}

	@Test
	void rejectsBlankFields() throws Exception {
		postVaga(tokenFor(recruiterA), vagaJson("", "", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.code").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.title").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.description").value("é obrigatório"));
	}

	@Test
	void ignoresRecruiterIdFromRequestBody() throws Exception {
		String code = "JAVA-002-" + suffix;
		String body = """
				{"code":"%s","title":"Teste","description":"Teste","recruiterId":"%s"}
				""".formatted(code, recruiterB);

		postVaga(tokenFor(recruiterA), body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.recruiterId").value(recruiterA.toString()));

		assertThat(recruiterIdOfVaga(code)).isEqualTo(recruiterA);
	}

	@Test
	void rejectsValidTokenOfNonexistentRecruiter() throws Exception {
		postVaga(tokenFor(UUID.randomUUID()), vagaJson("JAVA-001-" + suffix, "Título", "Descrição"))
				.andExpect(status().isUnauthorized());

		assertThat(countVagas("JAVA-001-" + suffix)).isZero();
	}

	@Test
	void listReturnsOnlyVagasOfEachAuthenticatedRecruiter() throws Exception {
		String javaOne = "JAVA-001-" + suffix;
		String javaTwo = "JAVA-002-" + suffix;
		String awsOne = "AWS-001-" + suffix;
		createVaga(recruiterA, javaOne);
		createVaga(recruiterA, javaTwo);
		createVaga(recruiterB, awsOne);

		getVagas(tokenFor(recruiterA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].code", containsInAnyOrder(javaOne, javaTwo)))
				.andExpect(jsonPath("$[*].code", not(hasItem(awsOne))))
				.andExpect(jsonPath("$[*].recruiterId", everyItem(equalTo(recruiterA.toString()))));

		getVagas(tokenFor(recruiterB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].code").value(awsOne))
				.andExpect(jsonPath("$[*].code", not(hasItem(javaOne))))
				.andExpect(jsonPath("$[*].code", not(hasItem(javaTwo))))
				.andExpect(jsonPath("$[*].recruiterId", everyItem(equalTo(recruiterB.toString()))));
	}

	@Test
	void listReturnsAllResponseFieldsAndNoSensitiveData() throws Exception {
		createVaga(recruiterA, "JAVA-001-" + suffix);

		getVagas(tokenFor(recruiterA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$[0].code").value("JAVA-001-" + suffix))
				.andExpect(jsonPath("$[0].title").value("Título"))
				.andExpect(jsonPath("$[0].description").value("Descrição"))
				.andExpect(jsonPath("$[0].status").value("ATUANDO"))
				.andExpect(jsonPath("$[0].recruiterId").value(recruiterA.toString()))
				.andExpect(jsonPath("$[0].createdAt").value(notNullValue()))
				.andExpect(jsonPath("$[0].recruiter").doesNotExist())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist());
	}

	@Test
	void listReturnsEmptyArrayWhenRecruiterHasNoVagas() throws Exception {
		createVaga(recruiterA, "JAVA-001-" + suffix);

		getVagas(tokenFor(recruiterB))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void listRejectsRequestWithoutToken() throws Exception {
		createVaga(recruiterA, "JAVA-001-" + suffix);

		mockMvc.perform(get("/vagas"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("JAVA-001"))));
	}

	@Test
	void listIgnoresRecruiterIdQueryParameter() throws Exception {
		createVaga(recruiterA, "JAVA-001-" + suffix);
		createVaga(recruiterB, "AWS-001-" + suffix);

		mockMvc.perform(get("/vagas")
				.param("recruiterId", recruiterB.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(recruiterA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].code").value("JAVA-001-" + suffix))
				.andExpect(jsonPath("$[*].recruiterId", everyItem(equalTo(recruiterA.toString()))));
	}

	@Test
	void getByIdReturnsVagaOfAuthenticatedRecruiter() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		getVaga(tokenFor(recruiterA), vagaId.toString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(vagaId.toString()))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.title").value("Título"))
				.andExpect(jsonPath("$.description").value("Descrição"))
				.andExpect(jsonPath("$.status").value("ATUANDO"))
				.andExpect(jsonPath("$.recruiterId").value(recruiterA.toString()))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()))
				.andExpect(jsonPath("$.recruiter").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void getByIdReturnsNotFoundForVagaOfAnotherRecruiter() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		getVaga(tokenFor(recruiterB), idOfVaga(code).toString())
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT));
	}

	@Test
	void getByIdReturnsSameNotFoundBodyForNonexistentVaga() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		String otherRecruiterBody = getVaga(tokenFor(recruiterB), idOfVaga(code).toString())
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();

		String nonexistentBody = getVaga(tokenFor(recruiterA), UUID.randomUUID().toString())
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT))
				.andReturn().getResponse().getContentAsString();

		assertThat(nonexistentBody).isEqualTo(otherRecruiterBody);
	}

	@Test
	void getByIdRejectsRequestWithoutToken() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		mockMvc.perform(get("/vagas/{id}", idOfVaga(code)))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("JAVA-001"))));
	}

	@Test
	void getByIdRejectsInvalidId() throws Exception {
		getVaga(tokenFor(recruiterA), "abc")
				.andExpect(status().isBadRequest());
	}

	@Test
	void updateChangesTitleAndDescriptionOfOwnVaga() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		putVaga(tokenFor(recruiterA), vagaId.toString(),
				updateJson("Desenvolvedor Java Senior AWS", "Nova descrição da vaga."))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(vagaId.toString()))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.title").value("Desenvolvedor Java Senior AWS"))
				.andExpect(jsonPath("$.description").value("Nova descrição da vaga."))
				.andExpect(jsonPath("$.status").value("ATUANDO"))
				.andExpect(jsonPath("$.recruiterId").value(recruiterA.toString()))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()))
				.andExpect(jsonPath("$.recruiter").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		assertThat(countVagas(code)).isEqualTo(1);
		assertThat(recruiterIdOfVaga(code)).isEqualTo(recruiterA);
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM vaga WHERE id = ?", String.class, vagaId))
				.isEqualTo("Desenvolvedor Java Senior AWS");
	}

	@Test
	void updateReturnsNotFoundForVagaOfAnotherRecruiter() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		putVaga(tokenFor(recruiterB), idOfVaga(code).toString(), updateJson("Invadido", "Invadido"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT));

		assertThat(jdbcTemplate.queryForObject("SELECT title FROM vaga WHERE code = ?", String.class, code))
				.isEqualTo("Título");
	}

	@Test
	void updateReturnsSameNotFoundBodyForNonexistentVaga() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		String otherRecruiterBody = putVaga(tokenFor(recruiterB), idOfVaga(code).toString(), updateJson("T", "D"))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();

		String nonexistentBody = putVaga(tokenFor(recruiterA), UUID.randomUUID().toString(), updateJson("T", "D"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT))
				.andReturn().getResponse().getContentAsString();

		assertThat(nonexistentBody).isEqualTo(otherRecruiterBody);
	}

	@Test
	void updateRejectsRequestWithoutToken() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		mockMvc.perform(put("/vagas/{id}", idOfVaga(code))
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateJson("Novo", "Nova")))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("JAVA-001"))));

		assertThat(jdbcTemplate.queryForObject("SELECT title FROM vaga WHERE code = ?", String.class, code))
				.isEqualTo("Título");
	}

	@Test
	void updateRejectsMissingTitle() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		putVaga(tokenFor(recruiterA), idOfVaga(code).toString(), "{\"description\":\"Nova\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("é obrigatório"));
	}

	@Test
	void updateRejectsMissingDescription() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		putVaga(tokenFor(recruiterA), idOfVaga(code).toString(), "{\"title\":\"Novo\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.description").value("é obrigatório"));
	}

	@Test
	void updateRejectsTitleLongerThan150() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		putVaga(tokenFor(recruiterA), idOfVaga(code).toString(), updateJson("a".repeat(151), "Nova"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("deve ter no máximo 150 caracteres"));
	}

	@Test
	void updateRejectsDescriptionLongerThan5000() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		putVaga(tokenFor(recruiterA), idOfVaga(code).toString(), updateJson("Novo", "a".repeat(5001)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.description").value("deve ter no máximo 5000 caracteres"));
	}

	@Test
	void updateRejectsInvalidId() throws Exception {
		putVaga(tokenFor(recruiterA), "abc", updateJson("Novo", "Nova"))
				.andExpect(status().isBadRequest());
	}

	// ---------- Status ----------

	@Test
	void statusChangesFromAtuandoToPausadaAndBack() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("PAUSADA"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(vagaId.toString()))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.title").value("Título"))
				.andExpect(jsonPath("$.status").value("PAUSADA"))
				.andExpect(jsonPath("$.recruiterId").value(recruiterA.toString()));
		assertThat(statusOfVaga(vagaId)).isEqualTo("PAUSADA");

		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("ATUANDO"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ATUANDO"));
		assertThat(statusOfVaga(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void statusChangesFromAtuandoToCancelada() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("CANCELADA"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELADA"));
		assertThat(statusOfVaga(vagaId)).isEqualTo("CANCELADA");
	}

	@Test
	void statusChangesFromPausadaToCancelada() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);
		setStatus(vagaId, "PAUSADA");

		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("CANCELADA"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELADA"));
		assertThat(statusOfVaga(vagaId)).isEqualTo("CANCELADA");
	}

	@Test
	void canceladaVagaCannotHaveStatusChanged() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);
		setStatus(vagaId, "CANCELADA");

		// Cancelada é final: não é retomada, pausada nem cancelada de novo.
		for (String target : new String[] { "ATUANDO", "PAUSADA", "CANCELADA", "FECHADA" }) {
			putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson(target))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message")
							.value("Não é possível alterar o status da vaga de CANCELADA para " + target));
		}
		assertThat(statusOfVaga(vagaId)).isEqualTo("CANCELADA");
	}

	@Test
	void fechadaVagaCannotHaveStatusChanged() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);
		setStatus(vagaId, "FECHADA");

		for (String target : new String[] { "ATUANDO", "PAUSADA", "CANCELADA", "FECHADA" }) {
			putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson(target))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message")
							.value("Uma vaga fechada não pode ter o status alterado manualmente"));
		}
		assertThat(statusOfVaga(vagaId)).isEqualTo("FECHADA");
	}

	@Test
	void statusRejectsTransitionsOutsideTheRules() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		// FECHADA só é definida pelo avanço de candidato à Proposta.
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("FECHADA"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Não é possível alterar o status da vaga de ATUANDO para FECHADA"));
		// Retomar uma vaga que já está atuando.
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("ATUANDO"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Não é possível alterar o status da vaga de ATUANDO para ATUANDO"));
		assertThat(statusOfVaga(vagaId)).isEqualTo("ATUANDO");

		// Pausar uma vaga que já está pausada.
		setStatus(vagaId, "PAUSADA");
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("PAUSADA"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Não é possível alterar o status da vaga de PAUSADA para PAUSADA"));
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("FECHADA"))
				.andExpect(status().isConflict());
		assertThat(statusOfVaga(vagaId)).isEqualTo("PAUSADA");
	}

	@Test
	void statusReturnsNotFoundForVagaOfAnotherRecruiter() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		putStatus(tokenFor(recruiterB), vagaId.toString(), statusJson("PAUSADA"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT));

		assertThat(statusOfVaga(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void statusReturnsSameNotFoundBodyForNonexistentVaga() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);

		String otherRecruiterBody = putStatus(tokenFor(recruiterB), idOfVaga(code).toString(), statusJson("PAUSADA"))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();

		String nonexistentBody = putStatus(tokenFor(recruiterA), UUID.randomUUID().toString(), statusJson("PAUSADA"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"message\":\"Vaga não encontrada\"}", JsonCompareMode.STRICT))
				.andReturn().getResponse().getContentAsString();

		assertThat(nonexistentBody).isEqualTo(otherRecruiterBody);
	}

	@Test
	void statusRejectsRequestWithoutToken() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		mockMvc.perform(put("/vagas/{vagaId}/status", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(statusJson("PAUSADA")))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("JAVA-001"))));

		assertThat(statusOfVaga(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void statusRejectsMissingOrInvalidValueAndInvalidId() throws Exception {
		String code = "JAVA-001-" + suffix;
		createVaga(recruiterA, code);
		UUID vagaId = idOfVaga(code);

		putStatus(tokenFor(recruiterA), vagaId.toString(), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.status").value("é obrigatório"));
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("ARQUIVADA"))
				.andExpect(status().isBadRequest());
		putStatus(tokenFor(recruiterA), vagaId.toString(), statusJson("pausada"))
				.andExpect(status().isBadRequest());
		putStatus(tokenFor(recruiterA), "abc", statusJson("PAUSADA"))
				.andExpect(status().isBadRequest());

		assertThat(statusOfVaga(vagaId)).isEqualTo("ATUANDO");
	}

	private void createVaga(UUID recruiterId, String code) throws Exception {
		postVaga(tokenFor(recruiterId), vagaJson(code, "Título", "Descrição"))
				.andExpect(status().isCreated());
	}

	private ResultActions getVagas(String token) throws Exception {
		return mockMvc.perform(get("/vagas").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions getVaga(String token, String id) throws Exception {
		return mockMvc.perform(get("/vagas/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions postVaga(String token, String body) throws Exception {
		return mockMvc.perform(post("/vagas")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions putVaga(String token, String id, String body) throws Exception {
		return mockMvc.perform(put("/vagas/{id}", id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions putStatus(String token, String id, String body) throws Exception {
		return mockMvc.perform(put("/vagas/{vagaId}/status", id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String tokenFor(UUID recruiterId) {
		return jwtService.generateToken(recruiterId);
	}

	private static String vagaJson(String code, String title, String description) {
		return """
				{"code":"%s","title":"%s","description":"%s"}
				""".formatted(code, title, description);
	}

	private static String updateJson(String title, String description) {
		return """
				{"title":"%s","description":"%s"}
				""".formatted(title, description);
	}

	private static String statusJson(String status) {
		return """
				{"status":"%s"}
				""".formatted(status);
	}

	private UUID insertRecruiter(String label) {
		UUID id = UUID.randomUUID();
		// Hash fictício: estes recruiters nunca fazem login, só recebem token direto do JwtService.
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, "test-" + id + "@example.com", "not-a-real-hash", "Test Recruiter " + label);
		return id;
	}

	private UUID recruiterIdOfVaga(String code) {
		return jdbcTemplate.queryForObject("SELECT recruiter_id FROM vaga WHERE code = ?", UUID.class, code);
	}

	private UUID idOfVaga(String code) {
		return jdbcTemplate.queryForObject("SELECT id FROM vaga WHERE code = ?", UUID.class, code);
	}

	private int countVagas(String code) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vaga WHERE code = ?", Integer.class, code);
	}

	private String statusOfVaga(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT status FROM vaga WHERE id = ?", String.class, vagaId);
	}

	// Prepara o status inicial direto no banco, sem depender das transições do endpoint.
	private void setStatus(UUID vagaId, String status) {
		jdbcTemplate.update("UPDATE vaga SET status = ? WHERE id = ?", status, vagaId);
	}

}
