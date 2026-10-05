package com.fernando.recruitervisual.vaga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fernando.recruitervisual.auth.JwtService;

/**
 * Testes de integração das etapas de vaga (/vagas/{vagaId}/etapas), incluindo chegaramCount e taxaReprovacao,
 * contra o PostgreSQL real.
 * Cada teste cria seus próprios recruiters e remove tudo o que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EtapaControllerTests {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final List<String> DEFAULT_NAMES = List.of(
			"Envio de Shortlist", "Entrevista Liderança", "Entrevista RH", "Proposta");
	private static final String VAGA_NOT_FOUND = "{\"message\":\"Vaga não encontrada\"}";
	private static final String ETAPA_NOT_FOUND = "{\"message\":\"Etapa não encontrada\"}";

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
		// Etapas, candidatos e histórico saem junto com a vaga (ON DELETE CASCADE).
		jdbcTemplate.update("DELETE FROM vaga WHERE recruiter_id IN (?, ?)", recruiterA, recruiterB);
		jdbcTemplate.update("DELETE FROM recruiter WHERE id IN (?, ?)", recruiterA, recruiterB);
	}

	@Test
	void creatingVagaCreatesDefaultEtapasInOrder() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3, 4);
		assertThat(jdbcTemplate.queryForList(
				"SELECT proposta FROM etapa WHERE vaga_id = ? ORDER BY position", Boolean.class, vagaId))
				.containsExactly(false, false, false, true);
	}

	// ---------- Etapas configuradas na criação da vaga ----------

	@Test
	void creatingVagaWithNullEtapasCreatesDefaultEtapas() throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, "null").andExpect(status().isCreated());

		UUID vagaId = vagaId(code);
		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3, 4);
		assertThat(etapaPropostas(vagaId)).containsExactly(false, false, false, true);
	}

	@Test
	void creatingVagaWithEtapasSavesExactlyTheReceivedEtapasInOrder() throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, etapasJson(etapa("Triagem", false), etapa("Entrevista Técnica", false),
				etapa("Case", false), etapa("Painel", false), etapa("Oferta", true)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.status").value("ATUANDO"));

		UUID vagaId = vagaId(code);
		assertThat(etapaNames(vagaId)).containsExactly("Triagem", "Entrevista Técnica", "Case", "Painel", "Oferta");
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3, 4, 5);
		assertThat(etapaPropostas(vagaId)).containsExactly(false, false, false, false, true);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("Triagem", "Entrevista Técnica", "Case", "Painel", "Oferta")))
				.andExpect(jsonPath("$[*].position", contains(1, 2, 3, 4, 5)))
				.andExpect(jsonPath("$[*].proposta", contains(false, false, false, false, true)))
				.andExpect(jsonPath("$[0].id").value(matchesPattern(UUID_PATTERN)));
	}

	@Test
	void creatingVagaWithEtapasIgnoresIdsAndPositionsFromRequest() throws Exception {
		String code = "JAVA-001-" + suffix;
		UUID sentId = UUID.randomUUID();

		postVagaWithEtapas(code, """
				[{"id":"%s","name":"Triagem","proposta":false,"position":9},
				 {"id":"%s","name":"Oferta","proposta":true,"position":1}]
				""".formatted(sentId, sentId))
				.andExpect(status().isCreated());

		UUID vagaId = vagaId(code);
		assertThat(etapaNames(vagaId)).containsExactly("Triagem", "Oferta");
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2);
		assertThat(etapaIds(vagaId)).doesNotContain(sentId).doesNotHaveDuplicates();
	}

	@Test
	void customPropostaKeepsItsNameAndClosesTheVaga() throws Exception {
		String code = "JAVA-001-" + suffix;
		postVagaWithEtapas(code, etapasJson(etapa("Triagem", false), etapa("Oferta", true)))
				.andExpect(status().isCreated());
		UUID vagaId = vagaId(code);
		List<UUID> ids = etapaIds(vagaId);

		assertThat(jdbcTemplate.queryForObject("SELECT name FROM etapa WHERE id = ?", String.class, propostaId(vagaId)))
				.isEqualTo("Oferta");
		assertThat(propostaId(vagaId)).isEqualTo(ids.get(1));

		// Mesma regra de fechamento das etapas padrão: chegar à proposta fecha a vaga.
		UUID candidato = createCandidatos(vagaId, 1).get(0);
		advance(vagaId, candidato, ids.get(0));
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM vaga WHERE id = ?", String.class, vagaId))
				.isEqualTo("FECHADA");

		// Os endpoints de etapa seguem valendo: a proposta customizada continua sem poder ser excluída.
		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(1)), recruiterA)
				.andExpect(status().isConflict());
	}

	@Test
	void creatingVagaWithOnlyPropostaIsAccepted() throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, etapasJson(etapa("Oferta", true))).andExpect(status().isCreated());

		UUID vagaId = vagaId(code);
		assertThat(etapaNames(vagaId)).containsExactly("Oferta");
		assertThat(etapaPropostas(vagaId)).containsExactly(true);
	}

	@Test
	void creatingVagaRejectsTwoPropostas() throws Exception {
		assertEtapasRejected(etapasJson(etapa("Triagem", false), etapa("Oferta", true), etapa("Proposta", true)),
				"A vaga deve ter exatamente uma etapa de proposta");
	}

	@Test
	void creatingVagaRejectsEtapasWithoutProposta() throws Exception {
		assertEtapasRejected(etapasJson(etapa("Triagem", false), etapa("Entrevista", false)),
				"A vaga deve ter exatamente uma etapa de proposta");
		assertEtapasRejected("[]", "A vaga deve ter exatamente uma etapa de proposta");
	}

	@Test
	void creatingVagaRejectsPropostaThatIsNotTheLastEtapa() throws Exception {
		assertEtapasRejected(etapasJson(etapa("Triagem", false), etapa("Oferta", true), etapa("Entrevista", false)),
				"A etapa de proposta deve ser a última");
		assertEtapasRejected(etapasJson(etapa("Oferta", true), etapa("Triagem", false)),
				"A etapa de proposta deve ser a última");
	}

	@Test
	void creatingVagaRejectsBlankEtapaName() throws Exception {
		String code = "JAVA-001-" + suffix;

		for (String name : List.of("", "   ")) {
			postVagaWithEtapas(code, etapasJson(etapa("Triagem", false), etapa(name, false), etapa("Oferta", true)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Dados inválidos"))
					.andExpect(jsonPath("$.errors['etapas[1].name']").value("é obrigatório"));
		}
		postVagaWithEtapas(code, "[{\"proposta\":false},{\"name\":\"Oferta\",\"proposta\":true}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['etapas[0].name']").value("é obrigatório"));

		assertThat(countVagas(code)).isZero();
	}

	@Test
	void creatingVagaRejectsEtapaNameLongerThan100() throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, etapasJson(etapa("a".repeat(101), false), etapa("Oferta", true)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['etapas[0].name']").value("deve ter no máximo 100 caracteres"));
		assertThat(countVagas(code)).isZero();

		postVagaWithEtapas(code, etapasJson(etapa("a".repeat(100), false), etapa("Oferta", true)))
				.andExpect(status().isCreated());
	}

	@Test
	void creatingVagaRejectsEtapaWithoutPropostaFlagOrNullItem() throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, "[{\"name\":\"Triagem\"},{\"name\":\"Oferta\",\"proposta\":true}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['etapas[0].proposta']").value("é obrigatório"));
		postVagaWithEtapas(code, "[null,{\"name\":\"Oferta\",\"proposta\":true}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"));

		assertThat(countVagas(code)).isZero();
	}

	// Configuração inválida: nem a vaga nem etapa alguma são gravadas.
	private void assertEtapasRejected(String etapasJson, String message) throws Exception {
		String code = "JAVA-001-" + suffix;

		postVagaWithEtapas(code, etapasJson)
				.andExpect(status().isBadRequest())
				.andExpect(content().json("{\"message\":\"%s\"}".formatted(message), JsonCompareMode.STRICT));

		assertThat(countVagas(code)).isZero();
	}

	private ResultActions postVagaWithEtapas(String code, String etapasJson) throws Exception {
		return perform(post("/vagas").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code":"%s","title":"Título","description":"Descrição","etapas":%s}
						""".formatted(code, etapasJson)), recruiterA);
	}

	private static String etapa(String name, boolean proposta) {
		return "{\"name\":\"%s\",\"proposta\":%s}".formatted(name, proposta);
	}

	private static String etapasJson(String... etapas) {
		return "[" + String.join(",", etapas) + "]";
	}

	private UUID vagaId(String code) {
		return jdbcTemplate.queryForObject("SELECT id FROM vaga WHERE code = ?", UUID.class, code);
	}

	private int countVagas(String code) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vaga WHERE code = ?", Integer.class, code);
	}

	private List<Boolean> etapaPropostas(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT proposta FROM etapa WHERE vaga_id = ? ORDER BY position", Boolean.class, vagaId);
	}

	@Test
	void listReturnsEtapasInCurrentOrder() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(4)))
				.andExpect(jsonPath("$[*].name", contains(DEFAULT_NAMES.toArray())))
				.andExpect(jsonPath("$[*].position", contains(1, 2, 3, 4)))
				.andExpect(jsonPath("$[*].proposta", contains(false, false, false, true)))
				.andExpect(jsonPath("$[0].id").value(etapaIds(vagaId).get(0).toString()))
				.andExpect(jsonPath("$[0].vaga").doesNotExist());
	}

	@Test
	void createEtapaInsertsItBeforeProposta() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Entrevista Técnica")), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$.name").value("Entrevista Técnica"))
				.andExpect(jsonPath("$.position").value(4))
				.andExpect(jsonPath("$.proposta").value(false));

		assertThat(etapaNames(vagaId)).containsExactly(
				"Envio de Shortlist", "Entrevista Liderança", "Entrevista RH", "Entrevista Técnica", "Proposta");
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3, 4, 5);
	}

	@Test
	void renameEtapaChangesOnlyItsName() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID rh = etapaId(vagaId, "Entrevista RH");

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, rh).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Entrevista People")), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(rh.toString()))
				.andExpect(jsonPath("$.name").value("Entrevista People"))
				.andExpect(jsonPath("$.position").value(3))
				.andExpect(jsonPath("$.proposta").value(false));

		assertThat(etapaNames(vagaId)).containsExactly(
				"Envio de Shortlist", "Entrevista Liderança", "Entrevista People", "Proposta");
		assertThat(etapaIds(vagaId).get(2)).isEqualTo(rh);
	}

	@Test
	void renamedPropostaRemainsTheClosingEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID proposta = propostaId(vagaId);

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, proposta).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Oferta")), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Oferta"))
				.andExpect(jsonPath("$.position").value(4))
				.andExpect(jsonPath("$.proposta").value(true));

		assertThat(propostaId(vagaId)).isEqualTo(proposta);

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, proposta), recruiterA)
				.andExpect(status().isConflict());

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Entrevista Técnica")), recruiterA)
				.andExpect(status().isCreated());

		assertThat(etapaNames(vagaId)).containsExactly(
				"Envio de Shortlist", "Entrevista Liderança", "Entrevista RH", "Entrevista Técnica", "Oferta");
	}

	@Test
	void deleteCommonEtapaRemovesItAndRenumbers() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId(vagaId, "Entrevista Liderança")), recruiterA)
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(etapaNames(vagaId)).containsExactly("Envio de Shortlist", "Entrevista RH", "Proposta");
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3);
	}

	@Test
	void deletePropostaIsRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, propostaId(vagaId)), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json("{\"message\":\"A etapa de proposta não pode ser excluída\"}",
						JsonCompareMode.STRICT));

		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
	}

	@Test
	void reorderCommonEtapas() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		reorder(vagaId, List.of(ids.get(2), ids.get(0), ids.get(1), ids.get(3)), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name",
						contains("Entrevista RH", "Envio de Shortlist", "Entrevista Liderança", "Proposta")))
				.andExpect(jsonPath("$[*].position", contains(1, 2, 3, 4)));

		assertThat(etapaIds(vagaId)).containsExactly(ids.get(2), ids.get(0), ids.get(1), ids.get(3));
		assertThat(etapaPositions(vagaId)).containsExactly(1, 2, 3, 4);
	}

	@Test
	void reorderRejectsMovingProposta() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		reorder(vagaId, List.of(ids.get(3), ids.get(0), ids.get(1), ids.get(2)), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json("{\"message\":\"A etapa de proposta não pode mudar de posição\"}",
						JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
	}

	@Test
	void reorderRejectsDuplicatedOmittedOrForeignEtapas() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterB, "AWS-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		String invalidOrder = "{\"message\":\"A nova ordem deve conter exatamente todas as etapas da vaga, sem repetições\"}";

		reorder(vagaId, List.of(ids.get(0), ids.get(0), ids.get(1), ids.get(3)), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(content().json(invalidOrder, JsonCompareMode.STRICT));

		reorder(vagaId, List.of(ids.get(1), ids.get(0), ids.get(3)), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(content().json(invalidOrder, JsonCompareMode.STRICT));

		reorder(vagaId, List.of(ids.get(1), ids.get(0), etapaIds(otherVagaId).get(0), ids.get(3)), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(content().json(invalidOrder, JsonCompareMode.STRICT));

		reorder(vagaId, List.of(ids.get(1), ids.get(0), ids.get(2), ids.get(3), UUID.randomUUID()), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(content().json(invalidOrder, JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(etapaNames(otherVagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
	}

	@Test
	void reorderRejectsMissingOrNullIds() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(put("/vagas/{vagaId}/etapas/ordem", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content("{}"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.etapaIds").value("é obrigatório"));

		perform(put("/vagas/{vagaId}/etapas/ordem", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"etapaIds\":[null]}"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"));
	}

	@Test
	void anotherRecruiterCannotReadOrChangeEtapas() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Invasão")), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Invasão")), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		reorder(vagaId, List.of(ids.get(1), ids.get(0), ids.get(2), ids.get(3)), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
	}

	@Test
	void nonexistentVagaReturnsSameNotFoundBody() throws Exception {
		UUID vagaId = UUID.randomUUID();
		UUID etapaId = UUID.randomUUID();

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Nova")), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Nova")), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));

		reorder(vagaId, List.of(etapaId), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void etapaOfAnotherVagaIsNotFoundThroughOwnVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID foreignEtapa = etapaIds(otherVagaId).get(0);

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, foreignEtapa).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Alterada")), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(ETAPA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, foreignEtapa), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(ETAPA_NOT_FOUND, JsonCompareMode.STRICT));

		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(ETAPA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(etapaNames(otherVagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
	}

	@Test
	void requestsWithoutTokenAreRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		mockMvc.perform(get("/vagas/{vagaId}/etapas", vagaId))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(not(containsString("Shortlist"))));

		mockMvc.perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Nova")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0))
				.contentType(MediaType.APPLICATION_JSON).content(nameJson("Nova")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(put("/vagas/{vagaId}/etapas/ordem", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(idsJson(List.of(ids.get(1), ids.get(0), ids.get(2), ids.get(3)))))
				.andExpect(status().isUnauthorized());

		assertThat(etapaIds(vagaId)).containsExactlyElementsOf(ids);
		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);
	}

	@Test
	void createAndRenameValidateName() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID etapaId = etapaIds(vagaId).get(0);

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content("{}"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"));

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("   ")), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"));

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("a".repeat(101))), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("deve ter no máximo 100 caracteres"));

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("")), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("é obrigatório"));

		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("a".repeat(101))), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("deve ter no máximo 100 caracteres"));

		assertThat(etapaNames(vagaId)).containsExactlyElementsOf(DEFAULT_NAMES);

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("a".repeat(100))), recruiterA)
				.andExpect(status().isCreated());
	}

	@Test
	void invalidIdsReturnBadRequest() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(get("/vagas/abc/etapas"), recruiterA)
				.andExpect(status().isBadRequest());

		perform(put("/vagas/{vagaId}/etapas/abc", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Nova")), recruiterA)
				.andExpect(status().isBadRequest());

		perform(delete("/vagas/{vagaId}/etapas/abc", vagaId), recruiterA)
				.andExpect(status().isBadRequest());
	}

	// ---------- chegaramCount e taxaReprovacao ----------

	@Test
	void taxaReprovacaoCountsCandidatosThatAlreadyLeftTheEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		List<UUID> candidatos = createCandidatos(vagaId, 10);
		for (UUID candidato : candidatos) {
			advance(vagaId, candidato, ids.get(0));
		}
		// 10 chegaram à Liderança: 2 reprovados nela e 8 seguem para o RH.
		for (UUID candidato : candidatos.subList(0, 2)) {
			reprovar(vagaId, candidato, ids.get(1));
		}
		for (UUID candidato : candidatos.subList(2, 10)) {
			advance(vagaId, candidato, ids.get(1));
		}

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].candidatesCount", contains(0, 0, 8, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 2, 0, 0)))
				.andExpect(jsonPath("$[*].chegaramCount", contains(10, 10, 8, 0)))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(0.0))
				.andExpect(jsonPath("$[1].taxaReprovacao").value(20.0))
				.andExpect(jsonPath("$[2].taxaReprovacao").value(0.0))
				.andExpect(jsonPath("$[3].taxaReprovacao").value(nullValue()));
	}

	@Test
	void taxaReprovacaoOneOfSixIsRoundedToOneDecimal() throws Exception {
		assertTaxaNaPrimeiraEtapa(6, 1, 16.7);
	}

	@Test
	void taxaReprovacaoThreeOfFive() throws Exception {
		assertTaxaNaPrimeiraEtapa(5, 3, 60.0);
	}

	@Test
	void taxaReprovacaoTwoOfTwo() throws Exception {
		assertTaxaNaPrimeiraEtapa(2, 2, 100.0);
	}

	@Test
	void taxaReprovacaoIsZeroWhenCandidatosArrivedAndNoneWasReprovado() throws Exception {
		assertTaxaNaPrimeiraEtapa(5, 0, 0.0);
	}

	@Test
	void taxaReprovacaoIsNullWhenNoCandidatoArrived() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].chegaramCount", contains(0, 0, 0, 0)))
				// O campo vem no JSON, com valor null: não é omitido nem vira 0.
				.andExpect(jsonPath("$[*]", everyItem(hasKey("taxaReprovacao"))))
				.andExpect(jsonPath("$[*].taxaReprovacao", contains(nullValue(), nullValue(), nullValue(), nullValue())));

		perform(post("/vagas/{vagaId}/etapas", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Entrevista Técnica")), recruiterA)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.chegaramCount").value(0))
				.andExpect(jsonPath("$", hasKey("taxaReprovacao")))
				.andExpect(jsonPath("$.taxaReprovacao").value(nullValue()));
	}

	@Test
	void candidatoArrivingTwiceAtTheSameEtapaCountsOnce() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID shortlist = ids.get(0);
		UUID lideranca = ids.get(1);
		UUID candidato = createCandidatos(vagaId, 1).get(0);
		advance(vagaId, candidato, shortlist);
		// Liderança, Shortlist, RH, Proposta: o próximo avanço leva o candidato de volta à Shortlist.
		reorder(vagaId, List.of(lideranca, shortlist, ids.get(2), ids.get(3)), recruiterA)
				.andExpect(status().isOk());
		advance(vagaId, candidato, lideranca);

		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM historico
				WHERE etapa_id = ? AND candidato_id = ? AND acao IN ('CANDIDATO_CRIADO', 'CANDIDATO_AVANCADO')
				""", Integer.class, shortlist, candidato)).isEqualTo(2);

		reprovar(vagaId, candidato, shortlist);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("Entrevista Liderança", "Envio de Shortlist", "Entrevista RH", "Proposta")))
				.andExpect(jsonPath("$[*].chegaramCount", contains(1, 1, 0, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 1, 0, 0)))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(0.0))
				.andExpect(jsonPath("$[1].taxaReprovacao").value(100.0));

		// A renomeação devolve os mesmos números da listagem.
		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, shortlist).contentType(MediaType.APPLICATION_JSON)
				.content(nameJson("Triagem")), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chegaramCount").value(1))
				.andExpect(jsonPath("$.reprovadosCount").value(1))
				.andExpect(jsonPath("$.taxaReprovacao").value(100.0));
	}

	@Test
	void chegaramCountAndTaxaReprovacaoAreIsolatedBetweenVagasAndRecruiters() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID vagaOfB = createVaga(recruiterB, "AWS-001-" + suffix);
		List<UUID> candidatos = createCandidatos(vagaId, 4);
		reprovar(vagaId, candidatos.get(0), etapaIds(vagaId).get(0));
		createCandidatos(otherVagaId, 2);

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].chegaramCount", contains(4, 0, 0, 0)))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(25.0));

		perform(get("/vagas/{vagaId}/etapas", otherVagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].chegaramCount", contains(2, 0, 0, 0)))
				.andExpect(jsonPath("$[*].reprovadosCount", contains(0, 0, 0, 0)))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(0.0));

		perform(get("/vagas/{vagaId}/etapas", vagaOfB), recruiterB)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].chegaramCount", contains(0, 0, 0, 0)))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(nullValue()));

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
	}

	// "chegaram" candidatos são criados na primeira etapa e "reprovados" deles são reprovados nela.
	private void assertTaxaNaPrimeiraEtapa(int chegaram, int reprovados, double taxa) throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID primeira = etapaIds(vagaId).get(0);
		for (UUID candidato : createCandidatos(vagaId, chegaram).subList(0, reprovados)) {
			reprovar(vagaId, candidato, primeira);
		}

		perform(get("/vagas/{vagaId}/etapas", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].candidatesCount").value(chegaram - reprovados))
				.andExpect(jsonPath("$[0].reprovadosCount").value(reprovados))
				.andExpect(jsonPath("$[0].chegaramCount").value(chegaram))
				.andExpect(jsonPath("$[0].taxaReprovacao").value(taxa));
	}

	// Sempre com o recruiter A, dono das vagas usadas pelos helpers de candidato.
	private List<UUID> createCandidatos(UUID vagaId, int quantidade) throws Exception {
		for (int i = 1; i <= quantidade; i++) {
			perform(post("/vagas/{vagaId}/candidatos", vagaId).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"name":"Candidato %d","stack":"Java"}
							""".formatted(i)), recruiterA)
					.andExpect(status().isCreated());
		}
		return jdbcTemplate.queryForList("SELECT id FROM candidato WHERE vaga_id = ?", UUID.class, vagaId);
	}

	private void advance(UUID vagaId, UUID candidatoId, UUID etapaId) throws Exception {
		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(etapaId)), recruiterA)
				.andExpect(status().isOk());
	}

	private void reprovar(UUID vagaId, UUID candidatoId, UUID etapaId) throws Exception {
		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(etapaId)), recruiterA)
				.andExpect(status().isOk());
	}

	private static String etapaJson(UUID etapaId) {
		return """
				{"etapaId":"%s"}
				""".formatted(etapaId);
	}

	private UUID createVaga(UUID recruiterId, String code) throws Exception {
		perform(post("/vagas").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code":"%s","title":"Título","description":"Descrição"}
						""".formatted(code)), recruiterId)
				.andExpect(status().isCreated());
		return jdbcTemplate.queryForObject("SELECT id FROM vaga WHERE code = ?", UUID.class, code);
	}

	private ResultActions reorder(UUID vagaId, List<UUID> ids, UUID recruiterId) throws Exception {
		return perform(put("/vagas/{vagaId}/etapas/ordem", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content(idsJson(ids)), recruiterId);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, UUID recruiterId) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(recruiterId)));
	}

	private static String nameJson(String name) {
		return """
				{"name":"%s"}
				""".formatted(name);
	}

	private static String idsJson(List<UUID> ids) {
		return ids.stream()
				.map(id -> "\"" + id + "\"")
				.collect(Collectors.joining(",", "{\"etapaIds\":[", "]}"));
	}

	private List<String> etapaNames(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT name FROM etapa WHERE vaga_id = ? ORDER BY position", String.class, vagaId);
	}

	private List<Integer> etapaPositions(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT position FROM etapa WHERE vaga_id = ? ORDER BY position", Integer.class, vagaId);
	}

	private List<UUID> etapaIds(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT id FROM etapa WHERE vaga_id = ? ORDER BY position", UUID.class, vagaId);
	}

	private UUID etapaId(UUID vagaId, String name) {
		return jdbcTemplate.queryForObject("SELECT id FROM etapa WHERE vaga_id = ? AND name = ?", UUID.class, vagaId, name);
	}

	private UUID propostaId(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT id FROM etapa WHERE vaga_id = ? AND proposta", UUID.class, vagaId);
	}

	private UUID insertRecruiter(String label) {
		UUID id = UUID.randomUUID();
		// Hash fictício: estes recruiters nunca fazem login, só recebem token direto do JwtService.
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, "test-" + id + "@example.com", "not-a-real-hash", "Test Recruiter " + label);
		return id;
	}

}
