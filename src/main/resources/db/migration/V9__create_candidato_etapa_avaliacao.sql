-- Avaliação do candidato em uma etapa. Independente de candidato.rating, que continua sendo a nota geral.
-- Só existe quando o recruiter registra uma nota; nada é criado ao cadastrar ou avançar o candidato.
CREATE TABLE candidato_etapa_avaliacao (
    id           UUID          PRIMARY KEY,
    vaga_id      UUID          NOT NULL,
    candidato_id UUID          NOT NULL,
    etapa_id     UUID          NOT NULL,
    rating       INTEGER       NOT NULL,
    observacao   VARCHAR(5000),
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_avaliacao_rating CHECK (rating BETWEEN 1 AND 5),
    -- No máximo uma avaliação por candidato em cada etapa.
    CONSTRAINT uk_avaliacao_candidato_etapa UNIQUE (candidato_id, etapa_id),
    -- Excluir a vaga remove suas avaliações.
    CONSTRAINT fk_avaliacao_vaga FOREIGN KEY (vaga_id) REFERENCES vaga (id) ON DELETE CASCADE,
    -- FKs compostas, como no histórico: candidato e etapa são da mesma vaga da avaliação.
    CONSTRAINT fk_avaliacao_candidato FOREIGN KEY (candidato_id, vaga_id)
        REFERENCES candidato (id, vaga_id) ON DELETE CASCADE,
    -- A avaliação pertence à etapa: excluir a etapa apaga as avaliações feitas nela.
    CONSTRAINT fk_avaliacao_etapa FOREIGN KEY (etapa_id, vaga_id)
        REFERENCES etapa (id, vaga_id) ON DELETE CASCADE
);

CREATE INDEX idx_avaliacao_vaga ON candidato_etapa_avaliacao (vaga_id);
CREATE INDEX idx_avaliacao_etapa ON candidato_etapa_avaliacao (etapa_id);
