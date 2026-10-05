package com.fernando.recruitervisual.vaga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
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
 * Testes de integração da avaliação de candidato por etapa
 * (PUT /vagas/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao e GET .../candidatos/{candidatoId}/avaliacoes)
 * contra o PostgreSQL real. Cada teste cria seus próprios recruiters e remove tudo o que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvaliacaoControllerTests {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String VAGA_NOT_FOUND = "{\"message\":\"Vaga não encontrada\"}";
	private static final String CANDIDATO_NOT_FOUND = "{\"message\":\"Candidato não encontrado\"}";
	private static final String ETAPA_NOT_FOUND = "{\"message\":\"Etapa não encontrada\"}";
	private static final String ETAPA_NOT_REACHED = "{\"message\":\"O candidato não passou pela etapa informada\"}";

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
		// Etapas, candidatos, histórico e avaliações saem junto com a vaga (ON DELETE CASCADE).
		jdbcTemplate.update("DELETE FROM vaga WHERE recruiter_id IN (?, ?)", recruiterA, recruiterB);
		jdbcTemplate.update("DELETE FROM recruiter WHERE id IN (?, ?)", recruiterA, recruiterB);
	}

	// ---------- Criar e atualizar ----------

	@Test
	void createsAvaliacaoForEtapaOfCandidato() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(4, "Boa experiência com Java e Spring."), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$.etapaId").value(shortlist.toString()))
				.andExpect(jsonPath("$.etapaNome").value("Envio de Shortlist"))
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.observacao").value("Boa experiência com Java e Spring."))
				.andExpect(jsonPath("$.createdAt").value(notNullValue()))
				.andExpect(jsonPath("$.updatedAt").value(notNullValue()))
				.andExpect(jsonPath("$.candidato").doesNotExist())
				.andExpect(jsonPath("$.etapa").doesNotExist());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candidato_etapa_avaliacao WHERE candidato_id = ?", ana);
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("etapa_id")).isEqualTo(shortlist);
		assertThat(row.get("rating")).isEqualTo(4);
		assertThat(row.get("observacao")).isEqualTo("Boa experiência com Java e Spring.");
	}

	@Test
	void updatesExistingAvaliacaoInsteadOfCreatingAnother() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(2, "Primeira impressão."), recruiterA)
				.andExpect(status().isOk());
		Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM candidato_etapa_avaliacao WHERE candidato_id = ?", ana);

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(5, "Revisado após conversa."), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(before.get("id").toString()))
				.andExpect(jsonPath("$.rating").value(5))
				.andExpect(jsonPath("$.observacao").value("Revisado após conversa."));

		// Continua existindo uma única avaliação para candidato + etapa.
		assertThat(countAvaliacoes(ana)).isEqualTo(1);
		Map<String, Object> after = jdbcTemplate.queryForMap("SELECT * FROM candidato_etapa_avaliacao WHERE candidato_id = ?", ana);
		assertThat(after.get("id")).isEqualTo(before.get("id"));
		assertThat(after.get("rating")).isEqualTo(5);
		assertThat(after.get("observacao")).isEqualTo("Revisado após conversa.");
		assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
		assertThat(((java.sql.Timestamp) after.get("updated_at")))
				.isAfterOrEqualTo((java.sql.Timestamp) before.get("updated_at"));
	}

	@Test
	void avaliacoesOfDifferentEtapasAreIndependent() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0));

		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Boa aderência ao perfil."), recruiterA)
				.andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(5, "Excelente domínio de Java."), recruiterA)
				.andExpect(status().isOk());
		assertThat(countAvaliacoes(ana)).isEqualTo(2);

		// Alterar a avaliação da Liderança não mexe na da Shortlist.
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(3, "Revisado."), recruiterA)
				.andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].etapaId", contains(ids.get(0).toString(), ids.get(1).toString())))
				.andExpect(jsonPath("$[*].rating", contains(4, 3)))
				.andExpect(jsonPath("$[*].observacao", contains("Boa aderência ao perfil.", "Revisado.")));
	}

	@Test
	void avaliacoesOfDifferentCandidatosInTheSameEtapaAreIndependent() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(5, "Ana"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, bruno, shortlist, avaliacaoJson(1, "Bruno"), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(jsonPath("$[*].rating", contains(5)));
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, bruno), recruiterA)
				.andExpect(jsonPath("$[*].rating", contains(1)));
	}

	// ---------- Validação ----------

	@Test
	void ratingMustBeBetweenOneAndFive() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(0, "x"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Dados inválidos"))
				.andExpect(jsonPath("$.errors.rating").value("deve ser no mínimo 1"));
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(6, "x"), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("deve ser no máximo 5"));
		putAvaliacao(vagaId, ana, shortlist, "{\"observacao\":\"sem nota\"}", recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("é obrigatório"));
		putAvaliacao(vagaId, ana, shortlist, "{\"rating\":null}", recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.rating").value("é obrigatório"));
		assertThat(countAvaliacoes(ana)).isZero();

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(1, "x"), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(1));
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(5, "x"), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(5));
	}

	@Test
	void observacaoIsOptionalAndLimitedTo5000Characters() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(3, "a".repeat(5001)), recruiterA)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.observacao").value("deve ter no máximo 5000 caracteres"));
		assertThat(countAvaliacoes(ana)).isZero();

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(3, "a".repeat(5000)), recruiterA)
				.andExpect(status().isOk());

		// Sem observação: a nota é gravada e a observação anterior é removida.
		putAvaliacao(vagaId, ana, shortlist, "{\"rating\":4}", recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(4))
				.andExpect(jsonPath("$.observacao").value(nullValue()));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT observacao FROM candidato_etapa_avaliacao WHERE candidato_id = ?", String.class, ana)).isNull();
	}

	@Test
	void invalidIdsReturnBadRequest() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");

		perform(put("/vagas/{vagaId}/candidatos/{candidatoId}/etapas/abc/avaliacao", vagaId, ana)
				.contentType(MediaType.APPLICATION_JSON).content(avaliacaoJson(4, "x")), recruiterA)
				.andExpect(status().isBadRequest());
		perform(put("/vagas/{vagaId}/candidatos/abc/etapas/{etapaId}/avaliacao", vagaId, shortlist)
				.contentType(MediaType.APPLICATION_JSON).content(avaliacaoJson(4, "x")), recruiterA)
				.andExpect(status().isBadRequest());
		perform(get("/vagas/{vagaId}/candidatos/abc/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isBadRequest());
	}

	// ---------- Etapa e candidato precisam ser da vaga ----------

	@Test
	void nonexistentEtapaReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");

		putAvaliacao(vagaId, ana, UUID.randomUUID(), avaliacaoJson(4, "x"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(ETAPA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(countAvaliacoes(ana)).isZero();
	}

	@Test
	void etapaOfAnotherVagaReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");

		putAvaliacao(vagaId, ana, etapaIds(otherVagaId).get(0), avaliacaoJson(4, "x"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(ETAPA_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(countAvaliacoes(ana)).isZero();
	}

	@Test
	void nonexistentCandidatoReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);

		putAvaliacao(vagaId, UUID.randomUUID(), shortlist, avaliacaoJson(4, "x"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));
	}

	@Test
	void candidatoOfAnotherVagaReturnsNotFound() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID ana = createCandidato(otherVagaId, "Ana");
		putAvaliacao(otherVagaId, ana, etapaIds(otherVagaId).get(0), avaliacaoJson(5, "própria vaga"), recruiterA)
				.andExpect(status().isOk());

		putAvaliacao(vagaId, ana, etapaIds(vagaId).get(0), avaliacaoJson(1, "x"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(countAvaliacoes(ana)).isEqualTo(1);
	}

	@Test
	void anotherRecruiterCannotSaveOrReadAvaliacoes() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(4, "Do recruiter A."), recruiterA)
				.andExpect(status().isOk());
		UUID vagaOfB = createVaga(recruiterB, "AWS-001-" + suffix);

		// Mesma resposta da vaga inexistente: não revela que a vaga, o candidato ou a avaliação existem.
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(1, "Invasão"), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		putAvaliacao(UUID.randomUUID(), ana, shortlist, avaliacaoJson(1, "x"), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		// Nem pela própria vaga o recruiter B alcança o candidato ou a etapa do recruiter A.
		putAvaliacao(vagaOfB, ana, etapaIds(vagaOfB).get(0), avaliacaoJson(1, "Invasão"), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(CANDIDATO_NOT_FOUND, JsonCompareMode.STRICT));

		assertThat(countAvaliacoes(ana)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT rating FROM candidato_etapa_avaliacao WHERE candidato_id = ?", Integer.class, ana)).isEqualTo(4);
	}

	@Test
	void requestsWithoutTokenAreRejected() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");

		mockMvc.perform(put("/vagas/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao", vagaId, ana, shortlist)
				.contentType(MediaType.APPLICATION_JSON).content(avaliacaoJson(4, "x")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana))
				.andExpect(status().isUnauthorized());

		assertThat(countAvaliacoes(ana)).isZero();
	}

	@Test
	void candidatoCanOnlyBeEvaluatedInEtapasItHasReached() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");

		// Ana está na Shortlist: ainda não chegou à Liderança nem à Proposta.
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(4, "x"), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ETAPA_NOT_REACHED, JsonCompareMode.STRICT));
		putAvaliacao(vagaId, ana, ids.get(3), avaliacaoJson(4, "x"), recruiterA)
				.andExpect(status().isConflict())
				.andExpect(content().json(ETAPA_NOT_REACHED, JsonCompareMode.STRICT));
		assertThat(countAvaliacoes(ana)).isZero();

		advance(vagaId, ana, ids.get(0));

		// Depois de avançar, vale tanto a etapa atual quanto a anterior.
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(4, "atual"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(3, "anterior"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(2), avaliacaoJson(4, "x"), recruiterA).andExpect(status().isConflict());
		assertThat(countAvaliacoes(ana)).isEqualTo(2);
	}

	// ---------- Consulta ----------

	@Test
	void listReturnsAllAvaliacoesOfCandidatoInCurrentEtapaOrder() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");
		advance(vagaId, ana, ids.get(0));
		advance(vagaId, ana, ids.get(1));
		// Gravadas fora de ordem: a resposta segue a posição das etapas, não a ordem de gravação.
		putAvaliacao(vagaId, ana, ids.get(2), avaliacaoJson(3, "RH"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Shortlist"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(5, "Liderança"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, bruno, ids.get(0), avaliacaoJson(1, "Bruno"), recruiterA).andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].etapaId",
						contains(ids.get(0).toString(), ids.get(1).toString(), ids.get(2).toString())))
				.andExpect(jsonPath("$[*].etapaNome",
						contains("Envio de Shortlist", "Entrevista Liderança", "Entrevista RH")))
				.andExpect(jsonPath("$[*].rating", contains(4, 5, 3)))
				.andExpect(jsonPath("$[*].observacao", contains("Shortlist", "Liderança", "RH")))
				.andExpect(jsonPath("$[0].id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$[0].createdAt").value(notNullValue()))
				.andExpect(jsonPath("$[0].updatedAt").value(notNullValue()));

		// Reordenar e renomear etapas reflete na consulta: RH, Shortlist, Liderança, Proposta.
		reorder(vagaId, List.of(ids.get(2), ids.get(0), ids.get(1), ids.get(3)));
		renameEtapa(vagaId, ids.get(2), "Entrevista People");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].etapaNome",
						contains("Entrevista People", "Envio de Shortlist", "Entrevista Liderança")))
				.andExpect(jsonPath("$[*].rating", contains(3, 4, 5)));
	}

	@Test
	void listReturnsEmptyArrayWhenCandidatoHasNoAvaliacoes() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(content().json("[]", JsonCompareMode.STRICT));
	}

	// ---------- Consulta de todas as avaliações da vaga ----------

	@Test
	void listByVagaReturnsAvaliacoesOfAllCandidatosAndEtapas() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");
		createCandidato(vagaId, "Carla");
		// Datas explícitas: a ordem dos candidatos é por createdAt, como nas listagens de candidatos.
		setCreatedAt(ana, "2026-01-02 10:00:00");
		setCreatedAt(bruno, "2026-01-01 10:00:00");
		advance(vagaId, ana, ids.get(0));
		advance(vagaId, ana, ids.get(1));
		putAvaliacao(vagaId, ana, ids.get(2), avaliacaoJson(3, "Boa comunicação."), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Boa aderência ao perfil."), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, bruno, ids.get(0), avaliacaoJson(1, "Fora do perfil."), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), "{\"rating\":5}", recruiterA).andExpect(status().isOk());

		// Bruno (mais antigo) primeiro; dentro de Ana, na ordem das etapas. Carla não tem avaliação e não aparece.
		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(4)))
				.andExpect(jsonPath("$[*].candidatoId",
						contains(bruno.toString(), ana.toString(), ana.toString(), ana.toString())))
				.andExpect(jsonPath("$[*].etapaId", contains(ids.get(0).toString(), ids.get(0).toString(),
						ids.get(1).toString(), ids.get(2).toString())))
				.andExpect(jsonPath("$[*].etapaNome", contains("Envio de Shortlist", "Envio de Shortlist",
						"Entrevista Liderança", "Entrevista RH")))
				.andExpect(jsonPath("$[*].rating", contains(1, 4, 5, 3)))
				.andExpect(jsonPath("$[*].observacao",
						contains("Fora do perfil.", "Boa aderência ao perfil.", null, "Boa comunicação.")))
				.andExpect(jsonPath("$[0].id").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$[0].createdAt").value(notNullValue()))
				.andExpect(jsonPath("$[0].updatedAt").value(notNullValue()))
				.andExpect(jsonPath("$[0].candidato").doesNotExist())
				.andExpect(jsonPath("$[0].etapa").doesNotExist());

		// Mesmos ids da consulta individual do candidato.
		assertThat(jdbcTemplate.queryForList(
				"SELECT id FROM candidato_etapa_avaliacao WHERE vaga_id = ?", UUID.class, vagaId)).hasSize(4);
		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, bruno), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].candidatoId").doesNotExist());
	}

	@Test
	void listByVagaIncludesReprovadosAndFollowsCurrentEtapaNameAndOrder() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0));
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Shortlist"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(2, "Liderança"), recruiterA).andExpect(status().isOk());
		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, ana)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(ids.get(1))), recruiterA)
				.andExpect(status().isOk());
		// Liderança, Shortlist, RH, Proposta.
		reorder(vagaId, List.of(ids.get(1), ids.get(0), ids.get(2), ids.get(3)));
		renameEtapa(vagaId, ids.get(0), "Triagem");

		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].candidatoId", contains(ana.toString(), ana.toString())))
				.andExpect(jsonPath("$[*].etapaNome", contains("Entrevista Liderança", "Triagem")))
				.andExpect(jsonPath("$[*].rating", contains(2, 4)));
	}

	@Test
	void listByVagaReturnsEmptyArrayWhenVagaHasNoAvaliacoes() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		createCandidato(vagaId, "Ana");

		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(content().json("[]", JsonCompareMode.STRICT));
	}

	@Test
	void listByVagaDoesNotReturnAvaliacoesOfOtherVagasOrRecruiters() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID vagaOfB = createVaga(recruiterB, "AWS-001-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(otherVagaId, "Bruno");
		putAvaliacao(vagaId, ana, etapaIds(vagaId).get(0), avaliacaoJson(4, "Vaga 1"), recruiterA).andExpect(status().isOk());
		putAvaliacao(otherVagaId, bruno, etapaIds(otherVagaId).get(0), avaliacaoJson(2, "Vaga 2"), recruiterA)
				.andExpect(status().isOk());
		// Candidato e avaliação do recruiter B, criados direto no banco: os helpers de candidato são do recruiter A.
		UUID carla = insertCandidato(vagaOfB, etapaIds(vagaOfB).get(0), "Carla");
		insertAvaliacao(vagaOfB, carla, etapaIds(vagaOfB).get(0), 5);

		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].candidatoId").value(ana.toString()))
				.andExpect(jsonPath("$[0].observacao").value("Vaga 1"));
		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", otherVagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].candidatoId").value(bruno.toString()))
				.andExpect(jsonPath("$[0].observacao").value("Vaga 2"));
		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaOfB), recruiterB)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].candidatoId").value(carla.toString()))
				.andExpect(jsonPath("$[0].rating").value(5));
	}

	@Test
	void listByVagaReturnsNotFoundForNonexistentVagaOrVagaOfAnotherRecruiter() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, etapaIds(vagaId).get(0), avaliacaoJson(4, "Segredo do recruiter A."), recruiterA)
				.andExpect(status().isOk());

		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", UUID.randomUUID()), recruiterA)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterB)
				.andExpect(status().isNotFound())
				.andExpect(content().json(VAGA_NOT_FOUND, JsonCompareMode.STRICT));
		mockMvc.perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId))
				.andExpect(status().isUnauthorized());
		perform(get("/vagas/abc/candidatos/avaliacoes"), recruiterA)
				.andExpect(status().isBadRequest());
	}

	@Test
	void listByVagaWorksInPausadaVaga() throws Exception {
		assertListByVagaWorksInVagaWithStatus("PAUSADA");
	}

	@Test
	void listByVagaWorksInFechadaVaga() throws Exception {
		assertListByVagaWorksInVagaWithStatus("FECHADA");
	}

	@Test
	void listByVagaWorksInCanceladaVaga() throws Exception {
		assertListByVagaWorksInVagaWithStatus("CANCELADA");
	}

	@Test
	void listByVagaDoesNotChangeAnyData() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0));
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Shortlist"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(5, "Liderança"), recruiterA).andExpect(status().isOk());
		List<Map<String, Object>> avaliacoesBefore = rowsOf("candidato_etapa_avaliacao", vagaId);
		List<Map<String, Object>> candidatosBefore = rowsOf("candidato", vagaId);
		List<Map<String, Object>> etapasBefore = rowsOf("etapa", vagaId);
		List<Map<String, Object>> historicoBefore = rowsOf("historico", vagaId);

		for (int i = 0; i < 2; i++) {
			perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$", hasSize(2)));
		}

		assertThat(rowsOf("candidato_etapa_avaliacao", vagaId)).isEqualTo(avaliacoesBefore);
		assertThat(rowsOf("candidato", vagaId)).isEqualTo(candidatosBefore);
		assertThat(rowsOf("etapa", vagaId)).isEqualTo(etapasBefore);
		assertThat(rowsOf("historico", vagaId)).isEqualTo(historicoBefore);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	private void assertListByVagaWorksInVagaWithStatus(String status) throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(4, "Antes da mudança de status."), recruiterA)
				.andExpect(status().isOk());
		jdbcTemplate.update("UPDATE vaga SET status = ? WHERE id = ?", status, vagaId);

		perform(get("/vagas/{vagaId}/candidatos/avaliacoes", vagaId), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].candidatoId").value(ana.toString()))
				.andExpect(jsonPath("$[0].etapaId").value(shortlist.toString()))
				.andExpect(jsonPath("$[0].etapaNome").value("Envio de Shortlist"))
				.andExpect(jsonPath("$[0].rating").value(4))
				.andExpect(jsonPath("$[0].observacao").value("Antes da mudança de status."));

		assertThat(vagaStatus(vagaId)).isEqualTo(status);
	}

	// Todas as linhas da tabela para a vaga, em ordem estável, para comparar antes e depois.
	private List<Map<String, Object>> rowsOf(String table, UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE vaga_id = ? ORDER BY id", vagaId);
	}

	private void setCreatedAt(UUID candidatoId, String timestamp) {
		jdbcTemplate.update("UPDATE candidato SET created_at = ?::timestamp WHERE id = ?", timestamp, candidatoId);
	}

	private UUID insertCandidato(UUID vagaId, UUID etapaId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO candidato (id, vaga_id, etapa_id, name, stack) VALUES (?, ?, ?, ?, 'Java')",
				id, vagaId, etapaId, name);
		return id;
	}

	// ---------- Avaliar não movimenta nem altera o candidato ----------

	@Test
	void avaliacaoDoesNotChangeCandidatoVagaOrHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		perform(post("/vagas/{vagaId}/candidatos", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Ana","stack":"Java","rating":2,"recruiterOpinion":"Opinião geral."}
						"""), recruiterA)
				.andExpect(status().isCreated());
		UUID ana = jdbcTemplate.queryForObject("SELECT id FROM candidato WHERE vaga_id = ?", UUID.class, vagaId);
		advance(vagaId, ana, ids.get(0));
		Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);
		int historicoBefore = countHistorico(vagaId);

		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(5, "Etapa anterior."), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(4, "Etapa atual."), recruiterA).andExpect(status().isOk());

		// Etapa atual, nota geral (Candidato.rating) e demais dados do candidato ficam como estavam.
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana)).isEqualTo(before);
		assertThat(before.get("etapa_id")).isEqualTo(ids.get(1));
		assertThat(before.get("rating")).isEqualTo(2);
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);

		// O candidato segue o fluxo normalmente, sem ganhar avaliação automática na nova etapa.
		advance(vagaId, ana, ids.get(1));
		assertThat(countAvaliacoes(ana)).isEqualTo(2);
	}

	@Test
	void avaliacaoDoesNotChangeReprovacaoData() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0));
		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/reprovar", vagaId, ana)
				.contentType(MediaType.APPLICATION_JSON).content(etapaJson(ids.get(1))), recruiterA)
				.andExpect(status().isOk());
		Map<String, Object> before = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);

		// Reprovado também pode ser avaliado nas etapas por onde passou.
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(1, "Motivo da reprovação."), recruiterA)
				.andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Foi bem na triagem."), recruiterA)
				.andExpect(status().isOk());

		Map<String, Object> after = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", ana);
		assertThat(after).isEqualTo(before);
		assertThat(after.get("reprovado")).isEqualTo(true);
		assertThat(after.get("etapa_reprovacao_id")).isEqualTo(ids.get(1));
		assertThat(after.get("etapa_reprovacao_nome")).isEqualTo("Entrevista Liderança");

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].rating", contains(4, 1)));
	}

	// ---------- Status da vaga ----------

	@Test
	void avaliacaoWorksInPausadaVaga() throws Exception {
		assertAvaliacaoWorksInVagaWithStatus("PAUSADA");
	}

	@Test
	void avaliacaoWorksInFechadaVaga() throws Exception {
		assertAvaliacaoWorksInVagaWithStatus("FECHADA");
	}

	@Test
	void avaliacaoWorksInCanceladaVaga() throws Exception {
		assertAvaliacaoWorksInVagaWithStatus("CANCELADA");
	}

	// ---------- Garantias do banco e exclusões ----------

	@Test
	void databaseRejectsSecondAvaliacaoForSameCandidatoAndEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(4, "x"), recruiterA).andExpect(status().isOk());

		assertThatThrownBy(() -> insertAvaliacao(vagaId, ana, shortlist, 5))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_avaliacao_candidato_etapa");

		assertThat(countAvaliacoes(ana)).isEqualTo(1);
	}

	@Test
	void databaseRejectsInvalidRatingAndEtapaOrCandidatoOfAnotherVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(otherVagaId, "Bruno");

		assertThatThrownBy(() -> insertAvaliacao(vagaId, ana, shortlist, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAvaliacao(vagaId, ana, shortlist, 6))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAvaliacao(vagaId, ana, etapaIds(otherVagaId).get(0), 4))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAvaliacao(vagaId, bruno, shortlist, 4))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(countAvaliacoes(ana)).isZero();
		assertThat(countAvaliacoes(bruno)).isZero();
	}

	@Test
	void deletingEtapaRemovesItsAvaliacoesAndKeepsTheOthers() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		advance(vagaId, ana, ids.get(0));
		putAvaliacao(vagaId, ana, ids.get(0), avaliacaoJson(4, "Shortlist"), recruiterA).andExpect(status().isOk());
		putAvaliacao(vagaId, ana, ids.get(1), avaliacaoJson(5, "Liderança"), recruiterA).andExpect(status().isOk());

		// A Shortlist não tem mais candidatos: pode ser excluída, e a avaliação feita nela sai junto.
		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)), recruiterA)
				.andExpect(status().isNoContent());

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].etapaId").value(ids.get(1).toString()))
				.andExpect(jsonPath("$[0].rating").value(5));
	}

	@Test
	void deletingVagaRemovesItsAvaliacoes() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID ana = createCandidato(vagaId, "Ana");
		putAvaliacao(vagaId, ana, etapaIds(vagaId).get(0), avaliacaoJson(4, "x"), recruiterA).andExpect(status().isOk());

		jdbcTemplate.update("DELETE FROM vaga WHERE id = ?", vagaId);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM candidato_etapa_avaliacao WHERE vaga_id = ?", Integer.class, vagaId)).isZero();
	}

	// Cria, atualiza e consulta a avaliação com a vaga no status informado; nada além da avaliação muda.
	private void assertAvaliacaoWorksInVagaWithStatus(String status) throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID shortlist = etapaIds(vagaId).get(0);
		UUID ana = createCandidato(vagaId, "Ana");
		jdbcTemplate.update("UPDATE vaga SET status = ? WHERE id = ?", status, vagaId);
		int historicoBefore = countHistorico(vagaId);

		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(3, "Criada."), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(3));
		putAvaliacao(vagaId, ana, shortlist, avaliacaoJson(5, "Atualizada."), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rating").value(5));

		perform(get("/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes", vagaId, ana), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].rating", contains(5)))
				.andExpect(jsonPath("$[*].observacao", contains("Atualizada.")));

		assertThat(vagaStatus(vagaId)).isEqualTo(status);
		assertThat(jdbcTemplate.queryForObject("SELECT etapa_id FROM candidato WHERE id = ?", UUID.class, ana))
				.isEqualTo(shortlist);
		assertThat(countHistorico(vagaId)).isEqualTo(historicoBefore);
	}

	private ResultActions putAvaliacao(UUID vagaId, UUID candidatoId, UUID etapaId, String body, UUID recruiterId)
			throws Exception {
		return perform(put("/vagas/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao", vagaId, candidatoId, etapaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterId);
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
		perform(post("/vagas/{vagaId}/candidatos", vagaId).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"%s","stack":"Java"}
						""".formatted(name)), recruiterA)
				.andExpect(status().isCreated());
		return jdbcTemplate.queryForObject("SELECT id FROM candidato WHERE vaga_id = ? AND name = ?", UUID.class, vagaId, name);
	}

	private void advance(UUID vagaId, UUID candidatoId, UUID etapaId) throws Exception {
		perform(post("/vagas/{vagaId}/candidatos/{candidatoId}/avancar", vagaId, candidatoId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(etapaJson(etapaId)), recruiterA)
				.andExpect(status().isOk());
	}

	private void reorder(UUID vagaId, List<UUID> ids) throws Exception {
		String body = ids.stream()
				.map(id -> "\"" + id + "\"")
				.collect(Collectors.joining(",", "{\"etapaIds\":[", "]}"));
		perform(put("/vagas/{vagaId}/etapas/ordem", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterA)
				.andExpect(status().isOk());
	}

	private void renameEtapa(UUID vagaId, UUID etapaId, String name) throws Exception {
		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\"}".formatted(name)), recruiterA)
				.andExpect(status().isOk());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, UUID recruiterId) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(recruiterId)));
	}

	private static String avaliacaoJson(int rating, String observacao) {
		return """
				{"rating":%d,"observacao":"%s"}
				""".formatted(rating, observacao);
	}

	private static String etapaJson(UUID etapaId) {
		return """
				{"etapaId":"%s"}
				""".formatted(etapaId);
	}

	private List<UUID> etapaIds(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT id FROM etapa WHERE vaga_id = ? ORDER BY position", UUID.class, vagaId);
	}

	private String vagaStatus(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT status FROM vaga WHERE id = ?", String.class, vagaId);
	}

	private int countAvaliacoes(UUID candidatoId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM candidato_etapa_avaliacao WHERE candidato_id = ?", Integer.class, candidatoId);
	}

	private int countHistorico(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historico WHERE vaga_id = ?", Integer.class, vagaId);
	}

	// Insert direto no banco, sem passar pelo endpoint, para exercitar as constraints.
	private void insertAvaliacao(UUID vagaId, UUID candidatoId, UUID etapaId, int rating) {
		jdbcTemplate.update("""
				INSERT INTO candidato_etapa_avaliacao (id, vaga_id, candidato_id, etapa_id, rating)
				VALUES (?, ?, ?, ?, ?)
				""", UUID.randomUUID(), vagaId, candidatoId, etapaId, rating);
	}

	private UUID insertRecruiter(String label) {
		UUID id = UUID.randomUUID();
		// Hash fictício: estes recruiters nunca fazem login, só recebem token direto do JwtService.
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, "test-" + id + "@example.com", "not-a-real-hash", "Test Recruiter " + label);
		return id;
	}

}
