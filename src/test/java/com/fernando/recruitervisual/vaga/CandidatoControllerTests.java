package com.fernando.recruitervisual.vaga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fernando.recruitervisual.auth.JwtService;

/**
 * Testes de integração de candidatos (/vagas/{vagaId}/candidatos), do avanço entre etapas,
 * do candidatesCount das etapas e da proteção de etapas com candidatos, contra o PostgreSQL real.
 * Cada teste cria seus próprios recruiters e remove tudo o que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidatoControllerTests {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String VAGA_NOT_FOUND = "{\"message\":\"Vaga não encontrada\"}";
	private static final String CANDIDATO_NOT_FOUND = "{\"message\":\"Candidato não encontrado\"}";
	private static final String ETAPA_HAS_CANDIDATOS = "{\"message\":\"Não é possível excluir etapas que possuem candidatos.\"}";
	private static final String PROPOSTA_NOT_DELETABLE = "{\"message\":\"A etapa de proposta não pode ser excluída\"}";
	private static final String STALE_ETAPA = "{\"message\":\"O candidato não está mais na etapa informada\"}";
	private static final String ALREADY_IN_PROPOSTA = "{\"message\":\"O candidato já está na etapa final\"}";
	private static final String REPROVADO_CANNOT_ADVANCE = "{\"message\":\"O candidato foi reprovado e não pode avançar\"}";
	private static final String ALREADY_REPROVADO = "{\"message\":\"O candidato já foi reprovado\"}";
	private static final String REPROVAR_IN_PROPOSTA = "{\"message\":\"Não é possível reprovar um candidato na etapa de proposta\"}";
	private static final String CADASTRO_NOT_ALLOWED = "{\"message\":\"Não é possível cadastrar candidatos em uma vaga %s\"}";
	private static final String ADVANCE_NOT_ALLOWED = "{\"message\":\"Não é possível avançar candidatos em uma vaga %s\"}";
	private static final String REPROVAR_NOT_ALLOWED = "{\"message\":\"Não é possível reprovar candidatos em uma vaga %s\"}";

	private static final String FULL_BODY = """
			{
			  "name": "Fernando Silva",
			  "linkedin": "https://linkedin.com/in/fernando",
			  "stack": "Java, Spring, AWS, Kafka",
			  "rating": 4,
			  "linkedinAbout": "Experiência com sistemas distribuídos.",
			  "recruiterOpinion": "Boa aderência à vaga.",
			  "technicalOpinion": "Boa experiência com backend."
			}
			""";

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
		// Etapas e candidatos saem junto com a vaga (ON DELETE CASCADE).
		jdbcTemplate.update("DELETE FROM vaga WHERE recruiter_id IN (?, ?)", recruiterA, recruiterB);
		jdbcTemplate.update("DELETE FROM recruiter WHERE id IN (?, ?)", recruiterA, recruiterB);
	}

	// ---------- Cadastro ----------

	@Test
	void createCandidatoReturnsAllFieldsAndEntersFirstEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);

		postCandidato(vagaId, FULL_BODY, recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(firstEtapa.toString()))
				.andExpect(jsonPath("$.name").value("Fernando Silva"))
				.andExpect(jsonPath("$.linkedin").value("https://linkedin.com/in/fernando"))
				.andExpect(jsonPath("$.stack").value("Java, Spring, AWS, Kafka"))
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.linkedinAbout").value("Experiência com sistemas distribuídos."))
				.andExpect(jsonPath("$.recruiterOpinion").value("Boa aderência à vaga."))
				.andExpect(jsonPath("$.technicalOpinion").value("Boa experiência com backend."))
				.andExpect(jsonPath("$.reprovado").value(false))
				.andExpect(jsonPath("$.etapaReprovacaoId").value(nullValue()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value(nullValue()))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()))
				.andExpect(jsonPath("$.vaga").doesNotExist())
				.andExpect(jsonPath("$.etapa").doesNotExist());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE vaga_id = ?", vagaId);
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("etapa_id")).isEqualTo(firstEtapa);
		assertThat(row.get("name")).isEqualTo("Fernando Silva");
		assertThat(row.get("linkedin")).isEqualTo("https://linkedin.com/in/fernando");
		assertThat(row.get("stack")).isEqualTo("Java, Spring, AWS, Kafka");
		assertThat(row.get("rating")).isEqualTo(4);
		assertThat(row.get("linkedin_about")).isEqualTo("Experiência com sistemas distribuídos.");
		assertThat(row.get("recruiter_opinion")).isEqualTo("Boa aderência à vaga.");
		assertThat(row.get("technical_opinion")).isEqualTo("Boa experiência com backend.");
		assertThat(row.get("reprovado")).isEqualTo(false);
		assertThat(row.get("etapa_reprovacao_id")).isNull();
		assertThat(row.get("etapa_reprovacao_nome")).isNull();
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void createCandidatoWithOnlyRequiredFields() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		postCandidato(vagaId, candidatoJson("Ana", "Kotlin"), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Ana"))
				.andExpect(jsonPath("$.stack").value("Kotlin"))
				.andExpect(jsonPath("$.linkedin").value(nullValue()))
				.andExpect(jsonPath("$.rating").value(nullValue()))
				.andExpect(jsonPath("$.linkedinAbout").value(nullValue()))
				.andExpect(jsonPath("$.recruiterOpinion").value(nullValue()))
				.andExpect(jsonPath("$.technicalOpinion").value(nullValue()));

		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	@Test
	void createCandidatoUsesCurrentFirstEtapaByPosition() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		// Entrevista RH passa a ser a primeira etapa.
		reorder(vagaId, List.of(ids.get(2), ids.get(0), ids.get(1), ids.get(3)));

		postCandidato(vagaId, candidatoJson("Ana", "Kotlin"), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.etapaId").value(ids.get(2).toString()));

		assertThat(etapaOfCandidato(vagaId, "Ana")).isEqualTo(ids.get(2));
	}

	@Test
	void createCandidatoIgnoresEtapaIdFromRequestBody() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		String body = """
				{"name":"Ana","stack":"Kotlin","etapaId":"%s"}
				""".formatted(ids.get(3));

		postCandidato(vagaId, body, recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.etapaId").value(ids.get(0).toString()));

		assertThat(etapaOfCandidato(vagaId, "Ana")).isEqualTo(ids.get(0));
	}

	@Test
	void createCandidatoInVagaOfAnotherRecruiterReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		postCandidato(vagaId, FULL_BODY, recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(countCandidatos(vagaId)).isZero();
	}

	@Test
	void createCandidatoInNonexistentVagaReturnsNotFound() throws Exception {
		postCandidato(UUID.randomUUID(), FULL_BODY, recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void createCandidatoWithoutTokenIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		mockMvc.perform(post("/vagas/{vagaId}/candidatos", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(FULL_BODY))
				.andExpect(status().isUnauthorized());

		assertThat(countCandidatos(vagaId)).isZero();
	}

	@Test
	void createCandidatoRequiresNameAndStack() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		postCandidato(vagaId, "{}", recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.stack").value("é obrigatório"));

		postCandidato(vagaId, candidatoJson("   ", ""), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.stack").value("é obrigatório"));

		assertThat(countCandidatos(vagaId)).isZero();
	}

	@Test
	void createCandidatoRejectsFieldsAboveMaximumLength() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		String body = """
				{"name":"%s","linkedin":"%s","stack":"%s","linkedinAbout":"%s","recruiterOpinion":"%s","technicalOpinion":"%s"}
				""".formatted("a".repeat(151), "a".repeat(501), "a".repeat(501),
				"a".repeat(5001), "a".repeat(5001), "a".repeat(5001));

		postCandidato(vagaId, body, recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("deve ter no máximo 150 caracteres"))
				.andExpect(jsonPath("$.errors.linkedin").value("deve ter no máximo 500 caracteres"))
				.andExpect(jsonPath("$.errors.stack").value("deve ter no máximo 500 caracteres"))
				.andExpect(jsonPath("$.errors.linkedinAbout").value("deve ter no máximo 5000 caracteres"))
				.andExpect(jsonPath("$.errors.recruiterOpinion").value("deve ter no máximo 5000 caracteres"))
				.andExpect(jsonPath("$.errors.technicalOpinion").value("deve ter no máximo 5000 caracteres"));

		assertThat(countCandidatos(vagaId)).isZero();
	}

	@Test
	void createCandidatoAcceptsFieldsAtMaximumLength() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		String body = """
				{"name":"%s","linkedin":"%s","stack":"%s","linkedinAbout":"%s","recruiterOpinion":"%s","technicalOpinion":"%s"}
				""".formatted("a".repeat(150), "a".repeat(500), "a".repeat(500),
				"a".repeat(5000), "a".repeat(5000), "a".repeat(5000));

		postCandidato(vagaId, body, recruiterA)
				.andExpect(status().isCreated());

		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	@Test
	void createCandidatoValidatesRatingRange() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		postCandidato(vagaId, ratingJson(-1), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("deve ser no mínimo 0"));

		postCandidato(vagaId, ratingJson(6), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("deve ser no máximo 5"));

		assertThat(countCandidatos(vagaId)).isZero();

		postCandidato(vagaId, ratingJson(0), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rating").value(0));

		postCandidato(vagaId, ratingJson(5), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rating").value(5));
	}

	// ---------- Consulta ----------

	@Test
	void listReturnsCandidatosOfOwnVagaOrderedByCreatedAt() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");
		UUID carla = createCandidato(vagaId, "Carla");
		createCandidato(otherVagaId, "Outra Vaga");

		// Datas explícitas, em ordem diferente da de inserção, para provar que a ordenação é por createdAt.
		setCreatedAt(ana, "2026-01-03 10:00:00");
		setCreatedAt(bruno, "2026-01-01 10:00:00");
		setCreatedAt(carla, "2026-01-02 10:00:00");

		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].name", contains("Bruno", "Carla", "Ana")))
				.andExpect(jsonPath("$[*].vagaId", everyItem(equalTo(vagaId.toString()))))
				.andExpect(jsonPath("$[*].etapaId", everyItem(equalTo(etapaIds(vagaId).get(0).toString()))));
	}

	@Test
	void listReturnsEmptyArrayWhenVagaHasNoCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void getCandidatoReturnsItsData() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		postCandidato(vagaId, FULL_BODY, recruiterA).andExpect(status().isCreated());
		UUID candidatoId = candidatoId(vagaId, "Fernando Silva");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(candidatoId.toString()))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(etapaIds(vagaId).get(0).toString()))
				.andExpect(jsonPath("$.name").value("Fernando Silva"))
				.andExpect(jsonPath("$.linkedin").value("https://linkedin.com/in/fernando"))
				.andExpect(jsonPath("$.stack").value("Java, Spring, AWS, Kafka"))
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.linkedinAbout").value("Experiência com sistemas distribuídos."))
				.andExpect(jsonPath("$.recruiterOpinion").value("Boa aderência à vaga."))
				.andExpect(jsonPath("$.technicalOpinion").value("Boa experiência com backend."))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()));
	}

	@Test
	void candidatoOfAnotherVagaCannotBeAccessedThroughOwnVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID candidatoId = createCandidato(otherVagaId, "Ana");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void anotherRecruiterCannotReadCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void nonexistentVagaReturnsNotFoundOnQueries() throws Exception {
		UUID vagaId = UUID.randomUUID();

		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void queriesWithoutTokenAreRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana Secreta");

		mockMvc.perform(get("/vagas/{vagaId}/candidatos", vagaId))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("Ana Secreta"))));

		mockMvc.perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("Ana Secreta"))));
	}

	// ---------- Edição ----------

	@Test
	void updateCandidatoChangesName() throws Exception {
		assertUpdateFromFullBody("Fernando Souza", "https://linkedin.com/in/fernando", "Java, Spring, AWS, Kafka", 4,
				"Experiência com sistemas distribuídos.", "Boa aderência à vaga.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesLinkedin() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando-novo", "Java, Spring, AWS, Kafka", 4,
				"Experiência com sistemas distribuídos.", "Boa aderência à vaga.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesStack() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando", "Kotlin, GCP", 4,
				"Experiência com sistemas distribuídos.", "Boa aderência à vaga.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesRating() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando", "Java, Spring, AWS, Kafka", 2,
				"Experiência com sistemas distribuídos.", "Boa aderência à vaga.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesLinkedinAbout() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando", "Java, Spring, AWS, Kafka", 4,
				"Novo resumo do perfil.", "Boa aderência à vaga.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesRecruiterOpinion() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando", "Java, Spring, AWS, Kafka", 4,
				"Experiência com sistemas distribuídos.", "Comunicação excelente.", "Boa experiência com backend.");
	}

	@Test
	void updateCandidatoChangesTechnicalOpinion() throws Exception {
		assertUpdateFromFullBody("Fernando Silva", "https://linkedin.com/in/fernando", "Java, Spring, AWS, Kafka", 4,
				"Experiência com sistemas distribuídos.", "Boa aderência à vaga.", "Precisa evoluir em arquitetura.");
	}

	@Test
	void updateCandidatoChangesAllFieldsTogether() throws Exception {
		assertUpdateFromFullBody("Ana Lima", "https://linkedin.com/in/ana", "Python, Django", 5,
				"Dez anos de experiência.", "Perfil sênior.", "Domina o stack.");
	}

	@Test
	void updateCandidatoClearsOptionalFieldsThatAreNotSent() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		postCandidato(vagaId, FULL_BODY, recruiterA).andExpect(status().isCreated());
		UUID candidatoId = candidatoId(vagaId, "Fernando Silva");

		putCandidato(vagaId, candidatoId, candidatoJson("Fernando Silva", "Java"), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Fernando Silva"))
				.andExpect(jsonPath("$.stack").value("Java"))
				.andExpect(jsonPath("$.linkedin").value(nullValue()))
				.andExpect(jsonPath("$.rating").value(nullValue()))
				.andExpect(jsonPath("$.linkedinAbout").value(nullValue()))
				.andExpect(jsonPath("$.recruiterOpinion").value(nullValue()))
				.andExpect(jsonPath("$.technicalOpinion").value(nullValue()));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", candidatoId);
		assertThat(row.get("rating")).isNull();
		assertThat(row.get("linkedin")).isNull();
	}

	@Test
	void updateCandidatoValidatesRatingFromOneToFive() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		postCandidato(vagaId, FULL_BODY, recruiterA).andExpect(status().isCreated());
		UUID candidatoId = candidatoId(vagaId, "Fernando Silva");

		putCandidato(vagaId, candidatoId, ratingJson(0), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.rating").value("deve ser no mínimo 1"));
		putCandidato(vagaId, candidatoId, ratingJson(-1), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("deve ser no mínimo 1"));
		putCandidato(vagaId, candidatoId, ratingJson(6), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("deve ser no máximo 5"));

		// Nada mudou: nem a nota nem o nome enviado nas requisições rejeitadas.
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, rating FROM candidato WHERE id = ?", candidatoId);
		assertThat(row.get("name")).isEqualTo("Fernando Silva");
		assertThat(row.get("rating")).isEqualTo(4);

		putCandidato(vagaId, candidatoId, ratingJson(1), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(1));
		putCandidato(vagaId, candidatoId, ratingJson(5), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(5));
		// Null continua significando "sem nota".
		putCandidato(vagaId, candidatoId, "{\"name\":\"Ana\",\"stack\":\"Java\",\"rating\":null}", recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(nullValue()));
	}

	@Test
	void updateCandidatoRequiresNameAndStackAndValidatesMaximumLength() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		putCandidato(vagaId, candidatoId, "{}", recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.stack").value("é obrigatório"));
		putCandidato(vagaId, candidatoId, candidatoJson("   ", ""), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"))
				.andExpect(jsonPath("$.errors.stack").value("é obrigatório"));

		String tooLong = """
				{"name":"%s","linkedin":"%s","stack":"%s","linkedinAbout":"%s","recruiterOpinion":"%s","technicalOpinion":"%s"}
				""".formatted("a".repeat(151), "a".repeat(501), "a".repeat(501),
				"a".repeat(5001), "a".repeat(5001), "a".repeat(5001));
		putCandidato(vagaId, candidatoId, tooLong, recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("deve ter no máximo 150 caracteres"))
				.andExpect(jsonPath("$.errors.linkedin").value("deve ter no máximo 500 caracteres"))
				.andExpect(jsonPath("$.errors.stack").value("deve ter no máximo 500 caracteres"))
				.andExpect(jsonPath("$.errors.linkedinAbout").value("deve ter no máximo 5000 caracteres"))
				.andExpect(jsonPath("$.errors.recruiterOpinion").value("deve ter no máximo 5000 caracteres"))
				.andExpect(jsonPath("$.errors.technicalOpinion").value("deve ter no máximo 5000 caracteres"));

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM candidato WHERE id = ?", String.class, candidatoId))
				.isEqualTo("Ana");

		String atMaximum = """
				{"name":"%s","linkedin":"%s","stack":"%s","linkedinAbout":"%s","recruiterOpinion":"%s","technicalOpinion":"%s"}
				""".formatted("a".repeat(150), "a".repeat(500), "a".repeat(500),
				"a".repeat(5000), "a".repeat(5000), "a".repeat(5000));
		putCandidato(vagaId, candidatoId, atMaximum, recruiterA)
				.andExpect(status().isOk());
	}

	@Test
	void updateOfCandidatoFromAnotherVagaOrNonexistentReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID candidatoId = createCandidato(otherVagaId, "Ana");

		putCandidato(vagaId, candidatoId, candidatoJson("Invasão", "Java"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));
		putCandidato(vagaId, UUID.randomUUID(), candidatoJson("Invasão", "Java"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM candidato WHERE id = ?", String.class, candidatoId))
				.isEqualTo("Ana");
	}

	@Test
	void anotherRecruiterCannotUpdateCandidato() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		UUID vagaOfB = createVaga(recruiterB, "AWS-001-" + suffix);

		// Mesma resposta da vaga inexistente: não revela que a vaga ou o candidato existem.
		putCandidato(vagaId, candidatoId, candidatoJson("Invasão", "Java"), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		putCandidato(UUID.randomUUID(), candidatoId, candidatoJson("Invasão", "Java"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		// Nem pela própria vaga o recruiter B alcança o candidato do recruiter A.
		putCandidato(vagaOfB, candidatoId, candidatoJson("Invasão", "Java"), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM candidato WHERE id = ?", String.class, candidatoId))
				.isEqualTo("Ana");
	}

	@Test
	void updateCandidatoWithoutTokenIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		mockMvc.perform(put("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(candidatoJson("Invasão", "Java")))
				.andExpect(status().isUnauthorized());

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM candidato WHERE id = ?", String.class, candidatoId))
				.isEqualTo("Ana");
	}

	@Test
	void updateCandidatoDoesNotChangeEtapaIdentityOrHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());
		Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);
		int historicoBefore = countHistorico(vagaId);

		// Campos que não fazem parte da edição são ignorados, mesmo se enviados.
		String body = """
				{"name":"Ana Lima","stack":"Go","id":"%s","vagaId":"%s","etapaId":"%s","reprovado":true,
				 "etapaReprovacaoId":"%s","etapaReprovacaoNome":"X","createdAt":"2020-01-01T00:00:00"}
				""".formatted(UUID.randomUUID(), otherVagaId, ids.get(2), ids.get(2));
		putCandidato(vagaId, ana, body, recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(ana.toString()))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.name").value("Ana Lima"))
				.andExpect(jsonPath("$.reprovado").value(false))
				.andExpect(jsonPath("$.etapaReprovacaoId").value(nullValue()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value(nullValue()));

		Map<String, Object> after = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);
		assertThat(after.get("name")).isEqualTo("Ana Lima");
		assertThat(after.get("stack")).isEqualTo("Go");
		for (String column : List.of("id", "vaga_id", "etapa_id", "reprovado", "etapa_reprovacao_id",
				"etapa_reprovacao_nome", "created_at")) {
			assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
		}
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);

		// O candidato segue o fluxo normalmente depois da edição.
		advance(vagaId, ana, ids.get(1), recruiterA).andExpect(status().isOk());
		assertThat(etapaOf(ana)).isEqualTo(ids.get(2));
	}

	@Test
	void updateReprovadoCandidatoKeepsReprovacaoData() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());
		reprovar(vagaId, ana, ids.get(1), recruiterA).andExpect(status().isOk());
		Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);

		putCandidato(vagaId, ana, FULL_BODY, recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Fernando Silva"))
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.reprovado").value(true))
				.andExpect(jsonPath("$.etapaReprovacaoId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value("Entrevista Liderança"));

		Map<String, Object> after = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);
		assertThat(after.get("name")).isEqualTo("Fernando Silva");
		for (String column : List.of("etapa_id", "reprovado", "etapa_reprovacao_id", "etapa_reprovacao_nome", "created_at")) {
			assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
		}

		perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("Fernando Silva")));
		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 1, 0, 0)));
	}

	@Test
	void updateCandidatoWorksInPausadaFechadaAndCanceladaVagas() throws Exception {
		for (String status : List.of("PAUSADA", "FECHADA", "CANCELADA")) {
			UUID vagaId = createVaga(recruiterA, status + "-" + suffix);
			UUID firstEtapa = etapaIds(vagaId).get(0);
			UUID ana = createCandidato(vagaId, "Ana");
			setVagaStatus(vagaId, status);

			putCandidato(vagaId, ana, FULL_BODY, recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.name").value("Fernando Silva"))
					.andExpect(jsonPath("$.rating").value(4))
					.andExpect(jsonPath("$.etapaId").value(firstEtapa.toString()));

			assertThat(vagaStatus(vagaId)).isEqualTo(status);
			assertThat(etapaOf(ana)).isEqualTo(firstEtapa);
			// A edição não libera movimentação: avançar e reprovar continuam bloqueados.
			advance(vagaId, ana, firstEtapa, recruiterA).andExpect(status().isConflict());
			reprovar(vagaId, ana, firstEtapa, recruiterA).andExpect(status().isConflict());
		}
	}

	// Cria o candidato com FULL_BODY, edita com os valores recebidos e confere resposta e banco.
	private void assertUpdateFromFullBody(String name, String linkedin, String stack, int rating, String linkedinAbout,
			String recruiterOpinion, String technicalOpinion) throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		postCandidato(vagaId, FULL_BODY, recruiterA).andExpect(status().isCreated());
		UUID candidatoId = candidatoId(vagaId, "Fernando Silva");
		String body = """
				{"name":"%s","linkedin":"%s","stack":"%s","rating":%d,"linkedinAbout":"%s","recruiterOpinion":"%s","technicalOpinion":"%s"}
				""".formatted(name, linkedin, stack, rating, linkedinAbout, recruiterOpinion, technicalOpinion);

		putCandidato(vagaId, candidatoId, body, recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(candidatoId.toString()))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(firstEtapa.toString()))
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.linkedin").value(linkedin))
				.andExpect(jsonPath("$.stack").value(stack))
				.andExpect(jsonPath("$.rating").value(rating))
				.andExpect(jsonPath("$.linkedinAbout").value(linkedinAbout))
				.andExpect(jsonPath("$.recruiterOpinion").value(recruiterOpinion))
				.andExpect(jsonPath("$.technicalOpinion").value(technicalOpinion))
				.andExpect(jsonPath("$.reprovado").value(false))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", candidatoId);
		assertThat(row.get("name")).isEqualTo(name);
		assertThat(row.get("linkedin")).isEqualTo(linkedin);
		assertThat(row.get("stack")).isEqualTo(stack);
		assertThat(row.get("rating")).isEqualTo(rating);
		assertThat(row.get("linkedin_about")).isEqualTo(linkedinAbout);
		assertThat(row.get("recruiter_opinion")).isEqualTo(recruiterOpinion);
		assertThat(row.get("technical_opinion")).isEqualTo(technicalOpinion);
		assertThat(row.get("etapa_id")).isEqualTo(firstEtapa);

		// A consulta devolve os dados editados.
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.rating").value(rating));
	}

	private ResultActions putCandidato(UUID vagaId, UUID candidatoId, String body, UUID recruiterId) throws Exception {
		return perform(put("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterId);
	}

	// ---------- candidatesCount ----------

	@Test
	void etapasWithoutCandidatosReturnZero() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].candidatesCount", contains(0, 0, 0, 0)));
	}

	@Test
	void candidatesCountFollowsCreatedCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		createCandidato(vagaId, "Ana");
		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 0, 0, 0)));

		createCandidato(vagaId, "Bruno");
		createCandidato(vagaId, "Carla");
		createCandidato(otherVagaId, "Outra Vaga");
		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(3, 0, 0, 0)));

		// Após reordenar, o próximo candidato entra na nova primeira etapa e a contagem acompanha cada etapa.
		reorder(vagaId, List.of(ids.get(2), ids.get(0), ids.get(1), ids.get(3)))
				.andExpect(jsonPath("$[*].candidatesCount", contains(0, 3, 0, 0)));

		createCandidato(vagaId, "Daniel");
		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].name",
						contains("Entrevista RH", "Envio de Shortlist", "Entrevista Liderança", "Proposta")))
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 3, 0, 0)));

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Shortlist\"}"), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidatesCount").value(3));

		perform(post("/vagas/{vagaId}/etapas", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Entrevista Técnica\"}"), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.candidatesCount").value(0));

		perform(get("/vagas/{vagaId}/etapas", otherVagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 0, 0, 0)));
	}

	// ---------- Exclusão de etapa ----------

	@Test
	void etapaWithoutCandidatosCanStillBeDeleted() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		createCandidato(vagaId, "Ana");

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(2)), recruiterA)
				.andExpect(status().isNoContent());

		assertThat(etapaIds(vagaId)).containsExactly(ids.get(0), ids.get(1), ids.get(3));
		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	@Test
	void etapaWithCandidatosCannotBeDeleted() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ETAPA_HAS_CANDIDATOS, JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(jdbcTemplate.queryForObject("SELECT etapa_id FROM candidato WHERE id = ?", UUID.class, candidatoId))
				.isEqualTo(ids.get(0));
	}

	@Test
	void propostaRemainsProtectedWithOrWithoutCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID proposta = ids.get(3);

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, proposta), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(PROPOSTA_NOT_DELETABLE, JsonCompareMode.STRICT));

		// Sem etapas comuns, a Proposta vira a primeira etapa e recebe o próximo candidato.
		for (UUID etapaId : ids.subList(0, 3)) {
			perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId), recruiterA)
					.andExpect(status().isNoContent());
		}
		createCandidato(vagaId, "Ana");
		assertThat(etapaOfCandidato(vagaId, "Ana")).isEqualTo(proposta);

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, proposta), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(PROPOSTA_NOT_DELETABLE, JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactly(proposta);
		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	// ---------- Avançar ----------

	@Test
	void advanceMovesCandidatoFromFirstToSecondEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		postCandidato(vagaId, FULL_BODY, recruiterA).andExpect(status().isCreated());
		UUID candidatoId = candidatoId(vagaId, "Fernando Silva");

		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(candidatoId.toString()))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.name").value("Fernando Silva"))
				.andExpect(jsonPath("$.stack").value("Java, Spring, AWS, Kafka"))
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(1));
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void consecutiveAdvancesMoveOneEtapaAtATime() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()));
		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(1));

		advance(vagaId, candidatoId, ids.get(1), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(2).toString()));
		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(2));
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void advanceFollowsNewOrderAfterReorder() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		// Shortlist, RH, Liderança, Proposta.
		reorder(vagaId, List.of(ids.get(0), ids.get(2), ids.get(1), ids.get(3)));

		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(2).toString()));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(2));
	}

	@Test
	void advanceSkipsDeletedIntermediateEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(1)), recruiterA)
				.andExpect(status().isNoContent());

		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(2).toString()));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(2));
	}

	@Test
	void candidatesCountMovesFromOriginToDestination() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		createCandidato(vagaId, "Bruno");

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(2, 0, 0, 0)));

		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 1, 0, 0)));
	}

	@Test
	void advancingToPropostaClosesVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");

		advance(vagaId, candidatoId, ids.get(2), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(3).toString()));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(3));
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");

		perform(get("/vagas/{id}", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FECHADA"));

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(0, 0, 0, 1)));
	}

	@Test
	void advancingToRenamedPropostaClosesVagaAndEtapaNamedPropostaDoesNot() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		renameEtapa(vagaId, ids.get(3), "Oferta");
		// Uma etapa comum com o nome "Proposta" não fecha a vaga: vale a flag, não o nome.
		renameEtapa(vagaId, ids.get(1), "Proposta");

		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");

		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(2), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(3).toString()));

		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
	}

	@Test
	void advancingCandidatoAlreadyInPropostaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID proposta = ids.get(3);
		// Sem etapas comuns, o candidato é cadastrado direto na Proposta e a vaga continua ATUANDO.
		for (UUID etapaId : ids.subList(0, 3)) {
			perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId), recruiterA)
					.andExpect(status().isNoContent());
		}
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, proposta, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ALREADY_IN_PROPOSTA, JsonCompareMode.STRICT));

		assertThat(etapaOf(candidatoId)).isEqualTo(proposta);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void advancingWithStaleEtapaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(1), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(STALE_ETAPA, JsonCompareMode.STRICT));
		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(0));

		// Duplo clique: a segunda requisição ainda informa a etapa de origem.
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(STALE_ETAPA, JsonCompareMode.STRICT));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(1));
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void anotherRecruiterCannotAdvanceCandidato() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(0), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(0));
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void advanceInNonexistentVagaReturnsNotFound() throws Exception {
		advance(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void advanceOfCandidatoFromAnotherVagaOrNonexistentReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID otherEtapa = etapaIds(otherVagaId).get(0);
		UUID foreignCandidato = createCandidato(otherVagaId, "Ana");

		advance(vagaId, foreignCandidato, otherEtapa, recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		advance(vagaId, UUID.randomUUID(), etapaIds(vagaId).get(0), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(etapaOf(foreignCandidato)).isEqualTo(otherEtapa);
	}

	@Test
	void advanceWithoutTokenIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		mockMvc.perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(ids.get(0))))
				.andExpect(status().isUnauthorized());

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(0));
	}

	@Test
	void advanceWithInvalidIdsOrBodyReturnsBadRequest() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		perform(post("/vagas/abc/candidatos/{candidatoId}/avancar", candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(ids.get(0))), recruiterA)
				.andExpect(status().isBadRequest());

		perform(post("/vagas/{vagaId}/candidatos/abc/avancar", vagaId)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(ids.get(0))), recruiterA)
				.andExpect(status().isBadRequest());

		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content("{}"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.etapaId").value("é obrigatório"));

		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"etapaId\":\"abc\"}"), recruiterA)
				.andExpect(status().isBadRequest());

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(0));
	}

	// ---------- Reprovar ----------

	@Test
	void reprovarMarksCandidatoAndKeepsItsEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());

		reprovar(vagaId, candidatoId, ids.get(1), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(candidatoId.toString()))
				.andExpect(jsonPath("$.vagaId").value(vagaId.toString()))
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.name").value("Ana"))
				.andExpect(jsonPath("$.reprovado").value(true))
				.andExpect(jsonPath("$.etapaReprovacaoId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value("Entrevista Liderança"));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", candidatoId);
		assertThat(row.get("reprovado")).isEqualTo(true);
		assertThat(row.get("etapa_reprovacao_id")).isEqualTo(ids.get(1));
		assertThat(row.get("etapa_reprovacao_nome")).isEqualTo("Entrevista Liderança");
		assertThat(row.get("etapa_id")).isEqualTo(ids.get(1));
		assertThat(countCandidatos(vagaId)).isEqualTo(1);
		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void reprovadoLeavesListButRemainsAccessibleById() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		createCandidato(vagaId, "Bruno");

		reprovar(vagaId, ana, firstEtapa, recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[*].name", contains("Bruno")))
				.andExpect(jsonPath("$[0].reprovado").value(false));

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Ana"))
				.andExpect(jsonPath("$.etapaId").value(firstEtapa.toString()))
				.andExpect(jsonPath("$.reprovado").value(true))
				.andExpect(jsonPath("$.etapaReprovacaoId").value(firstEtapa.toString()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value("Envio de Shortlist"));
	}

	@Test
	void reprovarMovesCountFromCandidatesToReprovadosOnlyInItsEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		createCandidato(vagaId, "Bruno");
		UUID carla = createCandidato(vagaId, "Carla");
		createCandidato(otherVagaId, "Outra Vaga");
		advance(vagaId, carla, ids.get(0), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(2, 1, 0, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 0, 0, 0)));

		reprovar(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 1, 0, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(1, 0, 0, 0)));

		// A resposta de renomear usa as mesmas contagens.
		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Shortlist\"}"), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidatesCount").value(1))
				.andExpect(jsonPath("$.reprovadosCount").value(1));

		perform(get("/vagas/{vagaId}/etapas", otherVagaId), recruiterA)
				.andExpect(jsonPath("$[*].candidatesCount", contains(1, 0, 0, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 0, 0, 0)));
		perform(get("/vagas/{vagaId}/candidatos", otherVagaId), recruiterA)
				.andExpect(jsonPath("$[*].name", contains("Outra Vaga")));
	}

	@Test
	void renamingEtapaAfterReprovacaoKeepsHistoricalName() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		reprovar(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());

		renameEtapa(vagaId, ids.get(1), "Entrevista com Gestor");

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM etapa WHERE id = ?", String.class, ids.get(1)))
				.isEqualTo("Entrevista com Gestor");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT etapa_reprovacao_nome FROM candidato WHERE id = ?", String.class, candidatoId))
				.isEqualTo("Entrevista Liderança");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, candidatoId), recruiterA)
				.andExpect(jsonPath("$.etapaReprovacaoId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$.etapaReprovacaoNome").value("Entrevista Liderança"));
	}

	@Test
	void reprovarTwiceIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		reprovar(vagaId, candidatoId, firstEtapa, recruiterA).andExpect(status().isOk());
		reprovar(vagaId, candidatoId, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ALREADY_REPROVADO, JsonCompareMode.STRICT));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT etapa_reprovacao_id FROM candidato WHERE id = ?", UUID.class, candidatoId))
				.isEqualTo(firstEtapa);
	}

	@Test
	void reprovarCandidatoInPropostaIsRejectedAndVagaStaysClosed() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(2), recruiterA).andExpect(status().isOk());
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");

		reprovar(vagaId, candidatoId, ids.get(3), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(REPROVAR_IN_PROPOSTA, JsonCompareMode.STRICT));

		assertNotReprovado(candidatoId);
		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(3));
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
	}

	@Test
	void advancingReprovadoCandidatoIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		reprovar(vagaId, candidatoId, firstEtapa, recruiterA).andExpect(status().isOk());

		advance(vagaId, candidatoId, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(REPROVADO_CANNOT_ADVANCE, JsonCompareMode.STRICT));

		assertThat(etapaOf(candidatoId)).isEqualTo(firstEtapa);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void reprovarWithStaleEtapaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		reprovar(vagaId, candidatoId, ids.get(1), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(STALE_ETAPA, JsonCompareMode.STRICT));

		// Outra aba avançou o candidato; a tela ainda mostra a etapa de origem.
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		reprovar(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(STALE_ETAPA, JsonCompareMode.STRICT));

		assertNotReprovado(candidatoId);
		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(1));
	}

	@Test
	void reprovarOfCandidatoFromAnotherVagaOrNonexistentReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID otherEtapa = etapaIds(otherVagaId).get(0);
		UUID foreignCandidato = createCandidato(otherVagaId, "Ana");

		reprovar(vagaId, foreignCandidato, otherEtapa, recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		reprovar(vagaId, UUID.randomUUID(), etapaIds(vagaId).get(0), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertNotReprovado(foreignCandidato);
	}

	@Test
	void anotherRecruiterCannotReprovarCandidato() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		reprovar(vagaId, candidatoId, firstEtapa, recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		assertNotReprovado(candidatoId);
	}

	@Test
	void reprovarInNonexistentVagaReturnsNotFound() throws Exception {
		reprovar(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void reprovarWithoutTokenIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		mockMvc.perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(firstEtapa)))
				.andExpect(status().isUnauthorized());

		assertNotReprovado(candidatoId);
	}

	@Test
	void reprovarWithInvalidIdsOrBodyReturnsBadRequest() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		perform(post("/vagas/abc/candidatos/{candidatoId}/reprovar", candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(firstEtapa)), recruiterA)
				.andExpect(status().isBadRequest());

		perform(post("/vagas/{vagaId}/candidatos/abc/reprovar", vagaId)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(firstEtapa)), recruiterA)
				.andExpect(status().isBadRequest());

		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content("{}"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.etapaId").value("é obrigatório"));

		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"etapaId\":\"abc\"}"), recruiterA)
				.andExpect(status().isBadRequest());

		assertNotReprovado(candidatoId);
	}

	@Test
	void etapaWithReprovadoCandidatoCannotBeDeleted() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		reprovar(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ETAPA_HAS_CANDIDATOS, JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	// ---------- Listagem de reprovados ----------

	@Test
	void listReprovadosReturnsOnlyReprovadosOfOwnVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		createCandidato(vagaId, "Bruno");
		UUID carla = createCandidato(vagaId, "Carla");
		UUID outraVaga = createCandidato(otherVagaId, "Outra Vaga");
		advance(vagaId, carla, ids.get(0), recruiterA).andExpect(status().isOk());

		reprovar(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());
		reprovar(vagaId, carla, ids.get(1), recruiterA).andExpect(status().isOk());
		reprovar(otherVagaId, outraVaga, etapaIds(otherVagaId).get(0), recruiterA).andExpect(status().isOk());

		// Datas explícitas, em ordem diferente da de inserção, para provar que a ordenação é por createdAt.
		setCreatedAt(ana, "2026-01-02 10:00:00");
		setCreatedAt(carla, "2026-01-01 10:00:00");

		perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].name", contains("Carla", "Ana")))
				.andExpect(jsonPath("$[*].vagaId", everyItem(equalTo(vagaId.toString()))))
				.andExpect(jsonPath("$[*].reprovado", everyItem(equalTo(true))))
				.andExpect(jsonPath("$[0].id").value(carla.toString()))
				.andExpect(jsonPath("$[0].etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$[0].etapaReprovacaoId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$[0].etapaReprovacaoNome").value("Entrevista Liderança"))
				.andExpect(jsonPath("$[1].id").value(ana.toString()))
				.andExpect(jsonPath("$[1].etapaReprovacaoId").value(ids.get(0).toString()))
				.andExpect(jsonPath("$[1].etapaReprovacaoNome").value("Envio de Shortlist"));

		// A listagem de ativos continua só com quem não foi reprovado.
		perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("Bruno")));
	}

	@Test
	void listReprovadosReturnsEmptyArrayWhenVagaHasNoReprovados() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		createCandidato(vagaId, "Ana");

		perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void listReprovadosKeepsHistoricalEtapaNameAfterRename() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		reprovar(vagaId, candidatoId, firstEtapa, recruiterA).andExpect(status().isOk());

		renameEtapa(vagaId, firstEtapa, "Shortlist");

		perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].etapaReprovacaoId").value(firstEtapa.toString()))
				.andExpect(jsonPath("$[0].etapaReprovacaoNome").value("Envio de Shortlist"));
	}

	@Test
	void anotherRecruiterCannotListReprovados() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana Secreta");
		reprovar(vagaId, candidatoId, etapaIds(vagaId).get(0), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT))
				.andExpect(content().string(not(containsString("Ana Secreta"))));
	}

	@Test
	void listReprovadosInNonexistentVagaReturnsNotFound() throws Exception {
		perform(get("/vagas/{vagaId}/candidatos/reprovados", UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void listReprovadosWithoutTokenIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana Secreta");
		reprovar(vagaId, candidatoId, etapaIds(vagaId).get(0), recruiterA).andExpect(status().isOk());

		mockMvc.perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("Ana Secreta"))));
	}

	// ---------- Garantias do banco ----------

	@Test
	void databaseRejectsInconsistentReprovacao() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE candidato SET reprovado = TRUE WHERE id = ?", candidatoId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE candidato SET etapa_reprovacao_id = ?, etapa_reprovacao_nome = 'X' WHERE id = ?",
				firstEtapa, candidatoId))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertNotReprovado(candidatoId);
	}

	@Test
	void databaseRejectsReprovacaoInEtapaOfAnotherVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID foreignEtapa = etapaIds(otherVagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE candidato SET reprovado = TRUE, etapa_reprovacao_id = ?, etapa_reprovacao_nome = 'X' WHERE id = ?",
				foreignEtapa, candidatoId))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertNotReprovado(candidatoId);
	}

	@Test
	void databaseRejectsDirectDeletionOfEtapaWithCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		createCandidato(vagaId, "Ana");

		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM etapa WHERE id = ?", firstEtapa))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	@Test
	void databaseRejectsCandidatoInEtapaOfAnotherVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID foreignEtapa = etapaIds(otherVagaId).get(0);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO candidato (id, vaga_id, etapa_id, name, stack) VALUES (?, ?, ?, 'Ana', 'Java')",
				UUID.randomUUID(), vagaId, foreignEtapa))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingVagaRemovesItsCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		createCandidato(vagaId, "Ana");
		createCandidato(vagaId, "Bruno");

		jdbcTemplate.update("DELETE FROM vaga WHERE id = ?", vagaId);

		assertThat(countCandidatos(vagaId)).isZero();
		assertThat(etapaIds(vagaId)).isEmpty();
	}

	// ---------- Status da vaga ----------

	@Test
	void createCandidatoInAtuandoVagaSucceeds() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");

		postCandidato(vagaId, candidatoJson("Ana", "Java"), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.etapaId").value(etapaIds(vagaId).get(0).toString()));

		assertThat(countCandidatos(vagaId)).isEqualTo(1);
	}

	@Test
	void createCandidatoInPausadaVagaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "PAUSADA");

		assertCreateRejected(vagaId, "pausada");
	}

	@Test
	void createCandidatoInCanceladaVagaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "CANCELADA");

		assertCreateRejected(vagaId, "cancelada");
	}

	@Test
	void createCandidatoInFechadaVagaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		closeVagaThroughProposta(vagaId);

		assertCreateRejected(vagaId, "fechada");
	}

	@Test
	void pausadaVagaRejectsAdvance() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		setVagaStatus(vagaId, "PAUSADA");
		int historicoBefore = countHistorico(vagaId);

		advance(vagaId, ana, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ADVANCE_NOT_ALLOWED.formatted("pausada"), JsonCompareMode.STRICT));

		assertThat(etapaOf(ana)).isEqualTo(firstEtapa);
		assertThat(vagaStatus(vagaId)).isEqualTo("PAUSADA");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void pausadaVagaRejectsReprovar() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		setVagaStatus(vagaId, "PAUSADA");
		int historicoBefore = countHistorico(vagaId);

		reprovar(vagaId, ana, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(REPROVAR_NOT_ALLOWED.formatted("pausada"), JsonCompareMode.STRICT));

		assertNotReprovado(ana);
		assertThat(vagaStatus(vagaId)).isEqualTo("PAUSADA");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void pausadaVagaRejectsAdvanceToPropostaAndDoesNotClose() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, ana, ids.get(1), recruiterA).andExpect(status().isOk());
		setVagaStatus(vagaId, "PAUSADA");

		advance(vagaId, ana, ids.get(2), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ADVANCE_NOT_ALLOWED.formatted("pausada"), JsonCompareMode.STRICT));

		assertThat(etapaOf(ana)).isEqualTo(ids.get(2));
		assertThat(vagaStatus(vagaId)).isEqualTo("PAUSADA");
	}

	@Test
	void pausadaCanceladaAndFechadaVagasRemainReadable() throws Exception {
		for (String status : List.of("PAUSADA", "CANCELADA", "FECHADA")) {
			UUID vagaId = createVaga(recruiterA, status + "-" + suffix);
			UUID firstEtapa = etapaIds(vagaId).get(0);
			UUID ana = createCandidato(vagaId, "Ana");
			UUID bruno = createCandidato(vagaId, "Bruno");
			reprovar(vagaId, bruno, firstEtapa, recruiterA).andExpect(status().isOk());
			setVagaStatus(vagaId, status);

			perform(get("/vagas/{vagaId}", vagaId), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value(status));
			perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[*].candidatesCount", contains(1, 0, 0, 0)))
					.andExpect(jsonPath("$[*].reprovadosCount", contains(1, 0, 0, 0)));
			perform(get("/vagas/{vagaId}/candidatos", vagaId), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[*].name", contains("Ana")));
			perform(get("/vagas/{vagaId}/candidatos/reprovados", vagaId), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[*].name", contains("Bruno")));
			perform(get("/vagas/{vagaId}/candidatos/{candidatoId}", vagaId, ana), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.name").value("Ana"));
		}
	}

	@Test
	void canceladaVagaRejectsAdvance() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		setVagaStatus(vagaId, "CANCELADA");
		int historicoBefore = countHistorico(vagaId);

		advance(vagaId, ana, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ADVANCE_NOT_ALLOWED.formatted("cancelada"), JsonCompareMode.STRICT));

		assertThat(etapaOf(ana)).isEqualTo(firstEtapa);
		assertThat(vagaStatus(vagaId)).isEqualTo("CANCELADA");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void canceladaVagaRejectsReprovar() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		setVagaStatus(vagaId, "CANCELADA");
		int historicoBefore = countHistorico(vagaId);

		reprovar(vagaId, ana, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(REPROVAR_NOT_ALLOWED.formatted("cancelada"), JsonCompareMode.STRICT));

		assertNotReprovado(ana);
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void fechadaVagaRejectsAdvanceOfOtherCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID bruno = createCandidato(vagaId, "Bruno");
		closeVagaThroughProposta(vagaId);
		int historicoBefore = countHistorico(vagaId);

		advance(vagaId, bruno, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ADVANCE_NOT_ALLOWED.formatted("fechada"), JsonCompareMode.STRICT));

		assertThat(etapaOf(bruno)).isEqualTo(firstEtapa);
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void fechadaVagaRejectsReprovarOfOtherCandidatos() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID bruno = createCandidato(vagaId, "Bruno");
		closeVagaThroughProposta(vagaId);
		int historicoBefore = countHistorico(vagaId);

		reprovar(vagaId, bruno, firstEtapa, recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(REPROVAR_NOT_ALLOWED.formatted("fechada"), JsonCompareMode.STRICT));

		assertNotReprovado(bruno);
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	@Test
	void pausadaVagaBackToAtuandoAllowsAllOperationsAgain() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");
		changeVagaStatus(vagaId, "PAUSADA");
		assertCreateRejected(vagaId, "pausada");
		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isConflict());
		reprovar(vagaId, bruno, ids.get(0), recruiterA).andExpect(status().isConflict());

		changeVagaStatus(vagaId, "ATUANDO");

		createCandidato(vagaId, "Carla");
		advance(vagaId, ana, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()));
		reprovar(vagaId, bruno, ids.get(0), recruiterA).andExpect(status().isOk());
		assertThat(countCandidatos(vagaId)).isEqualTo(3);
	}

	@Test
	void canceladaVagaCannotBeResumedAndKeepsRejectingOperations() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		changeVagaStatus(vagaId, "CANCELADA");

		perform(put("/vagas/{vagaId}/status", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ATUANDO\"}"), recruiterA)
				.andExpect(status().isConflict());

		assertThat(vagaStatus(vagaId)).isEqualTo("CANCELADA");
		assertCreateRejected(vagaId, "cancelada");
		advance(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isConflict());
		reprovar(vagaId, ana, ids.get(0), recruiterA).andExpect(status().isConflict());
		assertThat(etapaOf(ana)).isEqualTo(ids.get(0));
		assertNotReprovado(ana);
	}

	private void assertCreateRejected(UUID vagaId, String statusName) throws Exception {
		int candidatosBefore = countCandidatos(vagaId);
		int historicoBefore = countHistorico(vagaId);

		postCandidato(vagaId, candidatoJson("Carla", "Java"), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(CADASTRO_NOT_ALLOWED.formatted(statusName), JsonCompareMode.STRICT));

		assertThat(countCandidatos(vagaId)).isEqualTo(candidatosBefore);
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	// Fecha a vaga pelo caminho real: um candidato novo avança até a Proposta.
	private void closeVagaThroughProposta(UUID vagaId) throws Exception {
		List<UUID> ids = etapaIds(vagaId);
		UUID finalista = createCandidato(vagaId, "Finalista");
		for (UUID etapaId : ids.subList(0, ids.size() - 1)) {
			advance(vagaId, finalista, etapaId, recruiterA).andExpect(status().isOk());
		}
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
	}

	// Altera o status pelo endpoint, respeitando as transições permitidas.
	private void changeVagaStatus(UUID vagaId, String status) throws Exception {
		perform(put("/vagas/{vagaId}/status", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"%s\"}".formatted(status)), recruiterA)
				.andExpect(status().isOk());
	}

	// Prepara o status direto no banco, sem depender das transições do endpoint.
	private void setVagaStatus(UUID vagaId, String status) {
		jdbcTemplate.update("UPDATE vaga SET status = ? WHERE id = ?", status, vagaId);
	}

	private int countHistorico(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historico WHERE vaga_id = ?", Integer.class, vagaId);
	}

	private UUID createVaga(UUID recruiterId, String code) throws Exception {
		perform(post("/vagas").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code":"%s","title":"Título","description":"Descrição"}
						""".formatted(code)), recruiterId)
				.andExpect(status().isCreated());
		return jdbcTemplate.queryForObject("SELECT id FROM vaga WHERE code = ?", UUID.class, code);
	}

	// Sempre com o recruiter A, dono das vagas usadas pelos helpers.
	private UUID createCandidato(UUID vagaId, String name) throws Exception {
		postCandidato(vagaId, candidatoJson(name, "Java"), recruiterA)
				.andExpect(status().isCreated());
		return candidatoId(vagaId, name);
	}

	private ResultActions postCandidato(UUID vagaId, String body, UUID recruiterId) throws Exception {
		return perform(post("/vagas/{vagaId}/candidatos", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterId);
	}

	private ResultActions advance(UUID vagaId, UUID candidatoId, UUID etapaId, UUID recruiterId) throws Exception {
		return perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(etapaId)), recruiterId);
	}

	private ResultActions reprovar(UUID vagaId, UUID candidatoId, UUID etapaId, UUID recruiterId) throws Exception {
		return perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(etapaId)), recruiterId);
	}

	private void assertNotReprovado(UUID candidatoId) {
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT reprovado, etapa_reprovacao_id, etapa_reprovacao_nome FROM candidato WHERE id = ?", candidatoId);
		assertThat(row.get("reprovado")).isEqualTo(false);
		assertThat(row.get("etapa_reprovacao_id")).isNull();
		assertThat(row.get("etapa_reprovacao_nome")).isNull();
	}

	private void renameEtapa(UUID vagaId, UUID etapaId, String name) throws Exception {
		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\"}".formatted(name)), recruiterA)
				.andExpect(status().isOk());
	}

	private ResultActions reorder(UUID vagaId, List<UUID> ids) throws Exception {
		String body = ids.stream()
				.map(id -> "\"" + id + "\"")
				.collect(Collectors.joining(",", "{\"etapaIds\":[", "]}"));
		return perform(put("/vagas/{vagaId}/etapas/ordem", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterA)
				.andExpect(status().isOk());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, UUID recruiterId) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(recruiterId)));
	}

	private static String candidatoJson(String name, String stack) {
		return """
				{"name":"%s","stack":"%s"}
				""".formatted(name, stack);
	}

	private static String etapaJson(UUID etapaId) {
		return """
				{"etapaId":"%s"}
				""".formatted(etapaId);
	}

	private static String ratingJson(int rating) {
		return """
				{"name":"Ana","stack":"Java","rating":%d}
				""".formatted(rating);
	}

	private List<UUID> etapaIds(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT id FROM etapa WHERE vaga_id = ? ORDER BY position", UUID.class, vagaId);
	}

	private UUID candidatoId(UUID vagaId, String name) {
		return jdbcTemplate.queryForObject("SELECT id FROM candidato WHERE vaga_id = ? AND name = ?", UUID.class, vagaId, name);
	}

	private UUID etapaOfCandidato(UUID vagaId, String name) {
		return jdbcTemplate.queryForObject("SELECT etapa_id FROM candidato WHERE vaga_id = ? AND name = ?", UUID.class, vagaId, name);
	}

	private UUID etapaOf(UUID candidatoId) {
		return jdbcTemplate.queryForObject("SELECT etapa_id FROM candidato WHERE id = ?", UUID.class, candidatoId);
	}

	private String vagaStatus(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT status FROM vaga WHERE id = ?", String.class, vagaId);
	}

	private int countCandidatos(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM candidato WHERE vaga_id = ?", Integer.class, vagaId);
	}

	private void setCreatedAt(UUID candidatoId, String timestamp) {
		jdbcTemplate.update("UPDATE candidato SET created_at = CAST(? AS TIMESTAMP) WHERE id = ?", timestamp, candidatoId);
	}

	private UUID insertRecruiter(String label) {
		UUID id = UUID.randomUUID();
		// Hash fictício: estes recruiters nunca fazem login, só recebem token direto do JwtService.
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, "test-" + id + "@example.com", "not-a-real-hash", "Test Recruiter " + label);
		return id;
	}

}
