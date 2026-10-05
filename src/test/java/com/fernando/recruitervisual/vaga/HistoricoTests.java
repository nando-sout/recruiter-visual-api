package com.fernando.recruitervisual.vaga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fernando.recruitervisual.auth.JwtService;

/**
 * Testes de integração do histórico gravado pelas operações de candidato (criar, avançar, reprovar)
 * e pela alteração manual de status da vaga, contra o PostgreSQL real. Não há endpoint de histórico: os registros são conferidos direto na tabela.
 * Cada teste cria seus próprios recruiters e remove tudo o que criou ao final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoricoTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtService jwtService;

	// Espião com comportamento real; só um teste o faz falhar para simular erro na gravação do histórico.
	@MockitoSpyBean
	private HistoricoRepository historicoRepository;

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

	// ---------- Candidato criado ----------

	@Test
	void createCandidatoRegistersCandidatoCriado() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);

		UUID candidatoId = createCandidato(vagaId, "Ana");

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(0));
		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico).hasSize(1);
		Map<String, Object> row = historico.get(0);
		assertThat(row.get("acao")).isEqualTo("CANDIDATO_CRIADO");
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("recruiter_id")).isEqualTo(recruiterA);
		assertThat(row.get("candidato_id")).isEqualTo(candidatoId);
		assertThat(row.get("etapa_id")).isEqualTo(ids.get(0));
		assertThat(row.get("etapa_nome")).isEqualTo("Envio de Shortlist");
		assertThat(row.get("etapa_anterior_id")).isNull();
		assertThat(row.get("etapa_anterior_nome")).isNull();
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void createCandidatoRegistersCurrentFirstEtapaByPosition() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		// RH, Shortlist, Liderança, Proposta.
		reorder(vagaId, List.of(ids.get(2), ids.get(0), ids.get(1), ids.get(3)));

		UUID candidatoId = createCandidato(vagaId, "Ana");

		Map<String, Object> row = historicoOf(candidatoId).get(0);
		assertThat(row.get("etapa_id")).isEqualTo(ids.get(2));
		assertThat(row.get("etapa_nome")).isEqualTo("Entrevista RH");
	}

	// ---------- Candidato avançado ----------

	@Test
	void advanceRegistersCandidatoAvancadoWithPreviousAndNewEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(0), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.etapaId").value(ids.get(1).toString()));

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(1));
		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico).extracting(row -> row.get("acao"))
				.containsExactly("CANDIDATO_CRIADO", "CANDIDATO_AVANCADO");
		Map<String, Object> row = historico.get(1);
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("recruiter_id")).isEqualTo(recruiterA);
		assertThat(row.get("candidato_id")).isEqualTo(candidatoId);
		assertThat(row.get("etapa_anterior_id")).isEqualTo(ids.get(0));
		assertThat(row.get("etapa_anterior_nome")).isEqualTo("Envio de Shortlist");
		assertThat(row.get("etapa_id")).isEqualTo(ids.get(1));
		assertThat(row.get("etapa_nome")).isEqualTo("Entrevista Liderança");
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void advancingToPropostaRegistersEachStepAndClosesVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(2), recruiterA).andExpect(status().isOk());

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(3));
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico).extracting(row -> row.get("acao"))
				.containsExactly("CANDIDATO_CRIADO", "CANDIDATO_AVANCADO", "CANDIDATO_AVANCADO", "CANDIDATO_AVANCADO");
		assertThat(historico.subList(1, 4)).extracting(row -> row.get("etapa_anterior_id"))
				.containsExactly(ids.get(0), ids.get(1), ids.get(2));
		assertThat(historico.subList(1, 4)).extracting(row -> row.get("etapa_id"))
				.containsExactly(ids.get(1), ids.get(2), ids.get(3));
		assertThat(historico.get(3).get("etapa_nome")).isEqualTo("Proposta");
	}

	// ---------- Candidato reprovado ----------

	@Test
	void reprovarRegistersCandidatoReprovadoInCurrentEtapa() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());

		reprovar(vagaId, candidatoId, ids.get(1), recruiterA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reprovado").value(true));

		Map<String, Object> candidato = jdbcTemplate.queryForMap("SELECT * FROM candidato WHERE id = ?", candidatoId);
		assertThat(candidato.get("reprovado")).isEqualTo(true);
		assertThat(candidato.get("etapa_id")).isEqualTo(ids.get(1));
		assertThat(candidato.get("etapa_reprovacao_id")).isEqualTo(ids.get(1));

		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico).extracting(row -> row.get("acao"))
				.containsExactly("CANDIDATO_CRIADO", "CANDIDATO_AVANCADO", "CANDIDATO_REPROVADO");
		Map<String, Object> row = historico.get(2);
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("recruiter_id")).isEqualTo(recruiterA);
		assertThat(row.get("candidato_id")).isEqualTo(candidatoId);
		assertThat(row.get("etapa_id")).isEqualTo(ids.get(1));
		assertThat(row.get("etapa_nome")).isEqualTo("Entrevista Liderança");
		assertThat(row.get("etapa_anterior_id")).isNull();
		assertThat(row.get("etapa_anterior_nome")).isNull();
		assertThat(row.get("created_at")).isNotNull();
	}

	// ---------- Operações que falham ----------

	@Test
	void rejectedOperationsDoNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID ana = createCandidato(vagaId, "Ana");
		UUID bruno = createCandidato(vagaId, "Bruno");
		reprovar(vagaId, bruno, ids.get(0), recruiterA).andExpect(status().isOk());
		int before = countHistorico(vagaId);

		// Etapa desatualizada.
		advance(vagaId, ana, ids.get(1), recruiterA).andExpect(status().isConflict());
		reprovar(vagaId, ana, ids.get(1), recruiterA).andExpect(status().isConflict());
		// Reprovado não avança nem é reprovado de novo.
		advance(vagaId, bruno, ids.get(0), recruiterA).andExpect(status().isConflict());
		reprovar(vagaId, bruno, ids.get(0), recruiterA).andExpect(status().isConflict());
		// Body inválido.
		advance(vagaId, ana, null, recruiterA).andExpect(status().isBadRequest());
		postCandidato(vagaId, "{\"name\":\"Carla\"}", recruiterA).andExpect(status().isBadRequest());
		// Outro recruiter.
		advance(vagaId, ana, ids.get(0), recruiterB).andExpect(status().isNotFound());
		reprovar(vagaId, ana, ids.get(0), recruiterB).andExpect(status().isNotFound());
		postCandidato(vagaId, candidatoJson("Carla"), recruiterB).andExpect(status().isNotFound());

		assertThat(countHistorico(vagaId)).isEqualTo(before);
		assertThat(etapaOf(ana)).isEqualTo(ids.get(0));
	}

	@Test
	void rejectedOperationsInPropostaDoNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(2), recruiterA).andExpect(status().isOk());
		int before = countHistorico(vagaId);

		advance(vagaId, candidatoId, ids.get(3), recruiterA).andExpect(status().isConflict());
		reprovar(vagaId, candidatoId, ids.get(3), recruiterA).andExpect(status().isConflict());

		assertThat(countHistorico(vagaId)).isEqualTo(before);
	}

	// ---------- Transação ----------

	@Test
	void failureWhileRegisteringHistoricoRollsBackAdvance() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());
		advance(vagaId, candidatoId, ids.get(1), recruiterA).andExpect(status().isOk());
		int before = countHistorico(vagaId);

		doThrow(new IllegalStateException("falha simulada no histórico"))
				.when(historicoRepository).saveAndFlush(any(Historico.class));

		// O avanço que chegaria à Proposta fecharia a vaga; nada disso pode ficar gravado sem o histórico.
		assertThatThrownBy(() -> advance(vagaId, candidatoId, ids.get(2), recruiterA))
				.hasStackTraceContaining("falha simulada no histórico");

		assertThat(etapaOf(candidatoId)).isEqualTo(ids.get(2));
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
		assertThat(countHistorico(vagaId)).isEqualTo(before);
	}

	@Test
	void failureWhileRegisteringHistoricoRollsBackCreateAndReprovar() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		int before = countHistorico(vagaId);

		doThrow(new IllegalStateException("falha simulada no histórico"))
				.when(historicoRepository).saveAndFlush(any(Historico.class));

		assertThatThrownBy(() -> postCandidato(vagaId, candidatoJson("Bruno"), recruiterA))
				.hasStackTraceContaining("falha simulada no histórico");
		assertThatThrownBy(() -> reprovar(vagaId, candidatoId, firstEtapa, recruiterA))
				.hasStackTraceContaining("falha simulada no histórico");

		assertThat(countCandidatos(vagaId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT reprovado FROM candidato WHERE id = ?", Boolean.class, candidatoId))
				.isFalse();
		assertThat(countHistorico(vagaId)).isEqualTo(before);
	}

	// ---------- Etapas renomeadas ou excluídas ----------

	@Test
	void renamingEtapaKeepsHistoricalNames() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());

		renameEtapa(vagaId, ids.get(0), "Shortlist");
		renameEtapa(vagaId, ids.get(1), "Entrevista com Gestor");

		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico.get(0).get("etapa_nome")).isEqualTo("Envio de Shortlist");
		assertThat(historico.get(1).get("etapa_anterior_nome")).isEqualTo("Envio de Shortlist");
		assertThat(historico.get(1).get("etapa_nome")).isEqualTo("Entrevista Liderança");
	}

	@Test
	void deletingEtapaAlreadyPassedStillWorksAndKeepsHistoricalName() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		List<UUID> ids = etapaIds(vagaId);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, ids.get(0), recruiterA).andExpect(status().isOk());

		// Regra existente: a etapa sem candidatos pode ser excluída, mesmo que o histórico a cite.
		perform(delete("/vagas/{vagaId}/etapas/{etapaId}", vagaId, ids.get(0)), recruiterA)
				.andExpect(status().isNoContent());

		assertThat(etapaIds(vagaId)).containsExactly(ids.get(1), ids.get(2), ids.get(3));
		List<Map<String, Object>> historico = historicoOf(candidatoId);
		assertThat(historico).hasSize(2);
		assertThat(historico.get(0).get("etapa_id")).isNull();
		assertThat(historico.get(0).get("etapa_nome")).isEqualTo("Envio de Shortlist");
		assertThat(historico.get(1).get("etapa_anterior_id")).isNull();
		assertThat(historico.get(1).get("etapa_anterior_nome")).isEqualTo("Envio de Shortlist");
		assertThat(historico.get(1).get("etapa_id")).isEqualTo(ids.get(1));
	}

	// ---------- Garantias do banco ----------

	@Test
	void databaseRejectsHistoricoOfRecruiterThatDoesNotOwnVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");

		assertThatThrownBy(() -> insertHistorico(vagaId, recruiterB, candidatoId, firstEtapa))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsHistoricoWithCandidatoOrEtapaOfAnotherVaga() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID otherVagaId = createVaga(recruiterA, "JAVA-002-" + suffix);
		UUID firstEtapa = etapaIds(vagaId).get(0);
		UUID foreignEtapa = etapaIds(otherVagaId).get(0);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		UUID foreignCandidato = createCandidato(otherVagaId, "Bruno");

		assertThatThrownBy(() -> insertHistorico(vagaId, recruiterA, foreignCandidato, firstEtapa))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertHistorico(vagaId, recruiterA, candidatoId, foreignEtapa))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingVagaRemovesItsHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		UUID candidatoId = createCandidato(vagaId, "Ana");
		advance(vagaId, candidatoId, etapaIds(vagaId).get(0), recruiterA).andExpect(status().isOk());

		jdbcTemplate.update("DELETE FROM vaga WHERE id = ?", vagaId);

		assertThat(countHistorico(vagaId)).isZero();
	}

	// ---------- Status da vaga alterado ----------

	@Test
	void statusAtuandoToPausadaRegistersStatusAlterado() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		changeStatus(vagaId, "PAUSADA", recruiterA).andExpect(status().isOk());

		List<Map<String, Object>> historico = statusHistoricoOf(vagaId);
		assertThat(historico).hasSize(1);
		Map<String, Object> row = historico.get(0);
		assertStatusAlterado(row, vagaId, recruiterA, VagaStatus.ATUANDO, VagaStatus.PAUSADA);
		// Evento da vaga: sem candidato nem etapa.
		assertThat(row.get("candidato_id")).isNull();
		assertThat(row.get("etapa_id")).isNull();
		assertThat(row.get("etapa_nome")).isNull();
		assertThat(row.get("etapa_anterior_id")).isNull();
		assertThat(row.get("etapa_anterior_nome")).isNull();
	}

	@Test
	void statusPausadaToAtuandoRegistersStatusAlterado() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "PAUSADA");

		changeStatus(vagaId, "ATUANDO", recruiterA).andExpect(status().isOk());

		List<Map<String, Object>> historico = statusHistoricoOf(vagaId);
		assertThat(historico).hasSize(1);
		assertStatusAlterado(historico.get(0), vagaId, recruiterA, VagaStatus.PAUSADA, VagaStatus.ATUANDO);
	}

	@Test
	void statusAtuandoToCanceladaRegistersStatusAlterado() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		changeStatus(vagaId, "CANCELADA", recruiterA).andExpect(status().isOk());

		List<Map<String, Object>> historico = statusHistoricoOf(vagaId);
		assertThat(historico).hasSize(1);
		assertStatusAlterado(historico.get(0), vagaId, recruiterA, VagaStatus.ATUANDO, VagaStatus.CANCELADA);
	}

	@Test
	void statusPausadaToCanceladaRegistersStatusAlterado() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "PAUSADA");

		changeStatus(vagaId, "CANCELADA", recruiterA).andExpect(status().isOk());

		List<Map<String, Object>> historico = statusHistoricoOf(vagaId);
		assertThat(historico).hasSize(1);
		assertStatusAlterado(historico.get(0), vagaId, recruiterA, VagaStatus.PAUSADA, VagaStatus.CANCELADA);
	}

	@Test
	void changingCanceladaVagaDoesNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "CANCELADA");

		for (String target : List.of("ATUANDO", "PAUSADA", "CANCELADA")) {
			changeStatus(vagaId, target, recruiterA).andExpect(status().isConflict());
		}

		assertThat(countHistorico(vagaId)).isZero();
		assertThat(vagaStatus(vagaId)).isEqualTo("CANCELADA");
	}

	@Test
	void invalidStatusTransitionsDoNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		changeStatus(vagaId, "FECHADA", recruiterA).andExpect(status().isConflict());
		changeStatus(vagaId, "ATUANDO", recruiterA).andExpect(status().isConflict());
		changeStatus(vagaId, "ARQUIVADA", recruiterA).andExpect(status().isBadRequest());
		setVagaStatus(vagaId, "CANCELADA");
		changeStatus(vagaId, "PAUSADA", recruiterA).andExpect(status().isConflict());

		assertThat(countHistorico(vagaId)).isZero();
		assertThat(vagaStatus(vagaId)).isEqualTo("CANCELADA");
	}

	@Test
	void changingFechadaVagaDoesNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);
		setVagaStatus(vagaId, "FECHADA");

		for (String target : List.of("ATUANDO", "PAUSADA", "CANCELADA")) {
			changeStatus(vagaId, target, recruiterA).andExpect(status().isConflict());
		}

		assertThat(countHistorico(vagaId)).isZero();
		assertThat(vagaStatus(vagaId)).isEqualTo("FECHADA");
	}

	@Test
	void anotherRecruiterChangingStatusDoesNotRegisterHistorico() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		changeStatus(vagaId, "PAUSADA", recruiterB).andExpect(status().isNotFound());

		assertThat(countHistorico(vagaId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historico WHERE recruiter_id = ?",
				Integer.class, recruiterB)).isZero();
		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
	}

	@Test
	void failureWhileRegisteringHistoricoRollsBackStatusChange() throws Exception {
		UUID vagaId = createVaga(recruiterA, "JAVA-001-" + suffix);

		doThrow(new IllegalStateException("falha simulada no histórico"))
				.when(historicoRepository).saveAndFlush(any(Historico.class));

		assertThatThrownBy(() -> changeStatus(vagaId, "PAUSADA", recruiterA))
				.hasStackTraceContaining("falha simulada no histórico");

		assertThat(vagaStatus(vagaId)).isEqualTo("ATUANDO");
		assertThat(countHistorico(vagaId)).isZero();
	}

	private void assertStatusAlterado(Map<String, Object> row, UUID vagaId, UUID recruiterId,
			VagaStatus statusAnterior, VagaStatus statusNovo) {
		assertThat(row.get("acao")).isEqualTo(HistoricoAcao.STATUS_ALTERADO.name());
		assertThat(row.get("vaga_id")).isEqualTo(vagaId);
		assertThat(row.get("recruiter_id")).isEqualTo(recruiterId);
		assertThat(row.get("status_anterior")).isEqualTo(statusAnterior.name());
		assertThat(row.get("status_novo")).isEqualTo(statusNovo.name());
		assertThat(row.get("created_at")).isNotNull();
		assertThat(vagaStatus(vagaId)).isEqualTo(statusNovo.name());
	}

	private ResultActions changeStatus(UUID vagaId, String status, UUID recruiterId) throws Exception {
		return perform(put("/vagas/{vagaId}/status", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"%s\"}".formatted(status)), recruiterId);
	}

	// Prepara o status inicial direto no banco, sem passar pelo endpoint (e sem gerar histórico).
	private void setVagaStatus(UUID vagaId, String status) {
		jdbcTemplate.update("UPDATE vaga SET status = ? WHERE id = ?", status, vagaId);
	}

	private List<Map<String, Object>> statusHistoricoOf(UUID vagaId) {
		return jdbcTemplate.queryForList(
				"SELECT * FROM historico WHERE vaga_id = ? AND acao = 'STATUS_ALTERADO' ORDER BY created_at", vagaId);
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
		postCandidato(vagaId, candidatoJson(name), recruiterA)
				.andExpect(status().isCreated());
		return jdbcTemplate.queryForObject("SELECT id FROM candidato WHERE vaga_id = ? AND name = ?", UUID.class, vagaId, name);
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

	private void renameEtapa(UUID vagaId, UUID etapaId, String name) throws Exception {
		perform(put("/vagas/{vagaId}/etapas/{etapaId}", vagaId, etapaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\"}".formatted(name)), recruiterA)
				.andExpect(status().isOk());
	}

	private void reorder(UUID vagaId, List<UUID> ids) throws Exception {
		String body = "{\"etapaIds\":[" + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()) + "]}";
		perform(put("/vagas/{vagaId}/etapas/ordem", vagaId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), recruiterA)
				.andExpect(status().isOk());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, UUID recruiterId) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(recruiterId)));
	}

	private static String candidatoJson(String name) {
		return """
				{"name":"%s","stack":"Java"}
				""".formatted(name);
	}

	// etapaId nulo gera um body sem o campo obrigatório.
	private static String etapaJson(UUID etapaId) {
		return etapaId == null ? "{}" : """
				{"etapaId":"%s"}
				""".formatted(etapaId);
	}

	private List<UUID> etapaIds(UUID vagaId) {
		return jdbcTemplate.queryForList("SELECT id FROM etapa WHERE vaga_id = ? ORDER BY position", UUID.class, vagaId);
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

	// Em ordem de gravação. created_at pode empatar entre eventos próximos, então o desempate é pela ação,
	// que para um mesmo candidato segue a ordem CRIADO → AVANCADO → REPROVADO, e pela posição da etapa de destino.
	private List<Map<String, Object>> historicoOf(UUID candidatoId) {
		return jdbcTemplate.queryForList("""
				SELECT h.* FROM historico h
				LEFT JOIN etapa e ON e.id = h.etapa_id
				WHERE h.candidato_id = ?
				ORDER BY h.created_at,
				         CASE h.acao WHEN 'CANDIDATO_CRIADO' THEN 1 WHEN 'CANDIDATO_AVANCADO' THEN 2 ELSE 3 END,
				         e.position
				""", candidatoId);
	}

	private int countHistorico(UUID vagaId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historico WHERE vaga_id = ?", Integer.class, vagaId);
	}

	private void insertHistorico(UUID vagaId, UUID recruiterId, UUID candidatoId, UUID etapaId) {
		jdbcTemplate.update("""
				INSERT INTO historico (id, vaga_id, recruiter_id, candidato_id, etapa_id, etapa_nome, acao)
				VALUES (?, ?, ?, ?, ?, 'X', 'CANDIDATO_CRIADO')
				""", UUID.randomUUID(), vagaId, recruiterId, candidatoId, etapaId);
	}

	private UUID insertRecruiter(String label) {
		UUID id = UUID.randomUUID();
		// Hash fictício: estes recruiters nunca fazem login, só recebem token direto do JwtService.
		jdbcTemplate.update("INSERT INTO recruiter (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
				id, "test-" + id + "@example.com", "not-a-real-hash", "Test Recruiter " + label);
		return id;
	}

}
