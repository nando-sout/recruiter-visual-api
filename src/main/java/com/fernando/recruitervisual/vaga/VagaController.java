package com.fernando.recruitervisual.vaga;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/vagas")
public class VagaController {

	private final VagaService vagaService;
	private final EtapaService etapaService;
	private final CandidatoService candidatoService;
	private final AvaliacaoService avaliacaoService;

	public VagaController(VagaService vagaService, EtapaService etapaService, CandidatoService candidatoService,
			AvaliacaoService avaliacaoService) {
		this.vagaService = vagaService;
		this.etapaService = etapaService;
		this.candidatoService = candidatoService;
		this.avaliacaoService = avaliacaoService;
	}

	// Em todos os endpoints, o recruiterId vem só da autenticação JWT, nunca da URL, query ou body.
	@GetMapping
	public List<VagaResponse> list(@AuthenticationPrincipal UUID recruiterId) {
		return vagaService.listByRecruiter(recruiterId);
	}

	@GetMapping("/{id}")
	public VagaResponse get(@AuthenticationPrincipal UUID recruiterId, @PathVariable UUID id) {
		return vagaService.findByIdForRecruiter(id, recruiterId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public VagaResponse create(
			@AuthenticationPrincipal UUID recruiterId,
			@Valid @RequestBody CreateVagaRequest request) {
		return vagaService.create(request, recruiterId);
	}

	@PutMapping("/{id}")
	public VagaResponse update(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID id,
			@Valid @RequestBody UpdateVagaRequest request) {
		return vagaService.update(id, request, recruiterId);
	}

	@PutMapping("/{vagaId}/status")
	public VagaResponse updateStatus(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@Valid @RequestBody UpdateVagaStatusRequest request) {
		return vagaService.updateStatus(vagaId, request.status(), recruiterId);
	}

	@GetMapping("/{vagaId}/etapas")
	public List<EtapaResponse> listEtapas(@AuthenticationPrincipal UUID recruiterId, @PathVariable UUID vagaId) {
		return etapaService.list(vagaId, recruiterId);
	}

	@PostMapping("/{vagaId}/etapas")
	@ResponseStatus(HttpStatus.CREATED)
	public EtapaResponse createEtapa(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@Valid @RequestBody EtapaRequest request) {
		return etapaService.create(vagaId, request, recruiterId);
	}

	// Rota literal: tem precedência sobre /{vagaId}/etapas/{etapaId}.
	@PutMapping("/{vagaId}/etapas/ordem")
	public List<EtapaResponse> reorderEtapas(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@Valid @RequestBody ReorderEtapasRequest request) {
		return etapaService.reorder(vagaId, request, recruiterId);
	}

	@PutMapping("/{vagaId}/etapas/{etapaId}")
	public EtapaResponse renameEtapa(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID etapaId,
			@Valid @RequestBody EtapaRequest request) {
		return etapaService.rename(vagaId, etapaId, request, recruiterId);
	}

	@DeleteMapping("/{vagaId}/etapas/{etapaId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEtapa(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID etapaId) {
		etapaService.delete(vagaId, etapaId, recruiterId);
	}

	@PostMapping("/{vagaId}/candidatos")
	@ResponseStatus(HttpStatus.CREATED)
	public CandidatoResponse createCandidato(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@Valid @RequestBody CreateCandidatoRequest request) {
		return candidatoService.create(vagaId, request, recruiterId);
	}

	@GetMapping("/{vagaId}/candidatos")
	public List<CandidatoResponse> listCandidatos(@AuthenticationPrincipal UUID recruiterId, @PathVariable UUID vagaId) {
		return candidatoService.list(vagaId, recruiterId);
	}

	// Rota literal: tem precedência sobre /{vagaId}/candidatos/{candidatoId}.
	@GetMapping("/{vagaId}/candidatos/reprovados")
	public List<CandidatoResponse> listCandidatosReprovados(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId) {
		return candidatoService.listReprovados(vagaId, recruiterId);
	}

	@GetMapping("/{vagaId}/candidatos/{candidatoId}")
	public CandidatoResponse getCandidato(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId) {
		return candidatoService.findById(vagaId, candidatoId, recruiterId);
	}

	@PutMapping("/{vagaId}/candidatos/{candidatoId}")
	public CandidatoResponse updateCandidato(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId,
			@Valid @RequestBody UpdateCandidatoRequest request) {
		return candidatoService.update(vagaId, candidatoId, request, recruiterId);
	}

	@PostMapping("/{vagaId}/candidatos/{candidatoId}/avancar")
	public CandidatoResponse advanceCandidato(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId,
			@Valid @RequestBody AdvanceCandidatoRequest request) {
		return candidatoService.advance(vagaId, candidatoId, request.etapaId(), recruiterId);
	}

	@PostMapping("/{vagaId}/candidatos/{candidatoId}/reprovar")
	public CandidatoResponse reprovarCandidato(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId,
			@Valid @RequestBody ReprovarCandidatoRequest request) {
		return candidatoService.reprovar(vagaId, candidatoId, request.etapaId(), recruiterId);
	}

	// Rota literal: tem precedência sobre /{vagaId}/candidatos/{candidatoId}.
	@GetMapping("/{vagaId}/candidatos/avaliacoes")
	public List<VagaAvaliacaoResponse> listAvaliacoesOfVaga(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId) {
		return avaliacaoService.listByVaga(vagaId, recruiterId);
	}

	@GetMapping("/{vagaId}/candidatos/{candidatoId}/avaliacoes")
	public List<AvaliacaoResponse> listAvaliacoes(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId) {
		return avaliacaoService.list(vagaId, candidatoId, recruiterId);
	}

	// Cria ou atualiza: existe no máximo uma avaliação por candidato em cada etapa.
	@PutMapping("/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao")
	public AvaliacaoResponse saveAvaliacao(
			@AuthenticationPrincipal UUID recruiterId,
			@PathVariable UUID vagaId,
			@PathVariable UUID candidatoId,
			@PathVariable UUID etapaId,
			@Valid @RequestBody AvaliacaoRequest request) {
		return avaliacaoService.save(vagaId, candidatoId, etapaId, request, recruiterId);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return ResponseEntity.badRequest().body(Map.of("message", "Dados inválidos", "errors", errors));
	}

	@ExceptionHandler(DuplicateVagaCodeException.class)
	public ResponseEntity<Map<String, String>> handleDuplicateCode(DuplicateVagaCodeException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(VagaNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleVagaNotFound(VagaNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(VagaStatusTransitionException.class)
	public ResponseEntity<Map<String, String>> handleVagaStatusTransition(VagaStatusTransitionException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(EtapaNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleEtapaNotFound(EtapaNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(PropostaEtapaException.class)
	public ResponseEntity<Map<String, String>> handlePropostaEtapa(PropostaEtapaException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvalidEtapaOrderException.class)
	public ResponseEntity<Map<String, String>> handleInvalidEtapaOrder(InvalidEtapaOrderException ex) {
		return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvalidVagaEtapasException.class)
	public ResponseEntity<Map<String, String>> handleInvalidVagaEtapas(InvalidVagaEtapasException ex) {
		return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(EtapaHasCandidatosException.class)
	public ResponseEntity<Map<String, String>> handleEtapaHasCandidatos(EtapaHasCandidatosException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(CandidatoNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleCandidatoNotFound(CandidatoNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(CandidatoCadastroException.class)
	public ResponseEntity<Map<String, String>> handleCandidatoCadastro(CandidatoCadastroException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(CandidatoAdvanceException.class)
	public ResponseEntity<Map<String, String>> handleCandidatoAdvance(CandidatoAdvanceException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(CandidatoReprovacaoException.class)
	public ResponseEntity<Map<String, String>> handleCandidatoReprovacao(CandidatoReprovacaoException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(CandidatoAvaliacaoException.class)
	public ResponseEntity<Map<String, String>> handleCandidatoAvaliacao(CandidatoAvaliacaoException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(RecruiterNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleRecruiterNotFound(RecruiterNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", ex.getMessage()));
	}

}
