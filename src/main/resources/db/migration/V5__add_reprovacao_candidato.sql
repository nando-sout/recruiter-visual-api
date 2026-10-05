-- Reprovado é um estado do candidato, não uma etapa: etapa_id continua apontando para a etapa em que ele estava.
ALTER TABLE candidato
    ADD COLUMN reprovado             BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN etapa_reprovacao_id   UUID,
    -- Fotografia do nome da etapa no momento da reprovação; não acompanha renomeações.
    ADD COLUMN etapa_reprovacao_nome VARCHAR(100),
    -- Reprovado sempre tem etapa e nome da reprovação; não reprovado não tem nenhum dos dois.
    ADD CONSTRAINT ck_candidato_reprovacao CHECK (
        (reprovado AND etapa_reprovacao_id IS NOT NULL AND etapa_reprovacao_nome IS NOT NULL)
        OR (NOT reprovado AND etapa_reprovacao_id IS NULL AND etapa_reprovacao_nome IS NULL)),
    -- Mesmo padrão de fk_candidato_etapa: a etapa da reprovação é da mesma vaga do candidato.
    ADD CONSTRAINT fk_candidato_etapa_reprovacao
        FOREIGN KEY (etapa_reprovacao_id, vaga_id) REFERENCES etapa (id, vaga_id);

CREATE INDEX idx_candidato_etapa_reprovacao ON candidato (etapa_reprovacao_id);
