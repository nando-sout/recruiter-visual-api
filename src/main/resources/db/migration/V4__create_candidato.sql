-- Alvo da FK composta de candidato: garante que a etapa do candidato é da mesma vaga dele.
ALTER TABLE etapa ADD CONSTRAINT uk_etapa_id_vaga UNIQUE (id, vaga_id);

CREATE TABLE candidato (
    id                UUID          PRIMARY KEY,
    vaga_id           UUID          NOT NULL,
    etapa_id          UUID          NOT NULL,
    name              VARCHAR(150)  NOT NULL,
    linkedin          VARCHAR(500),
    stack             VARCHAR(500)  NOT NULL,
    rating            INTEGER,
    linkedin_about    VARCHAR(5000),
    recruiter_opinion VARCHAR(5000),
    technical_opinion VARCHAR(5000),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_candidato_rating CHECK (rating BETWEEN 0 AND 5),
    -- Excluir a vaga remove seus candidatos.
    CONSTRAINT fk_candidato_vaga FOREIGN KEY (vaga_id) REFERENCES vaga (id) ON DELETE CASCADE,
    -- Sem ON DELETE: excluir diretamente uma etapa com candidatos falha em vez de apagá-los.
    -- NO ACTION (e não RESTRICT) para que a exclusão da vaga, que cascateia etapas e candidatos, funcione.
    CONSTRAINT fk_candidato_etapa FOREIGN KEY (etapa_id, vaga_id) REFERENCES etapa (id, vaga_id)
);

CREATE INDEX idx_candidato_vaga ON candidato (vaga_id);
CREATE INDEX idx_candidato_etapa ON candidato (etapa_id);
