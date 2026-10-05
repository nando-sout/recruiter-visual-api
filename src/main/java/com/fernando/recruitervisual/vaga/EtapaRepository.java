package com.fernando.recruitervisual.vaga;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EtapaRepository extends JpaRepository<Etapa, UUID> {

	List<Etapa> findByVagaIdOrderByPositionAsc(UUID vagaId);

	Optional<Etapa> findByIdAndVagaId(UUID id, UUID vagaId);

	Optional<Etapa> findFirstByVagaIdOrderByPositionAsc(UUID vagaId);

	Optional<Etapa> findFirstByVagaIdAndPositionGreaterThanOrderByPositionAsc(UUID vagaId, int position);

}
