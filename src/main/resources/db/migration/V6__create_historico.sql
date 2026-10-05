-- Alvos das FKs compostas do histórico, no mesmo padrão de uk_etapa_id_vaga (V4).
-- Garante que o candidato do histórico é da mesma vaga dele.
ALTER TABLE candidato ADD CONSTRAINT uk_candidato_id_vaga UNIQUE (id, vaga_id);
-- Garante que o recruiter do histórico é o dono da vaga.
ALTER TABLE vaga ADD CONSTRAINT uk_vaga_id_recruiter UNIQUE (id, recruiter_id);

CREATE TABLE historico (
    id                  UUID         PRIMARY KEY,
    vaga_id             UUID         NOT NULL,
    recruiter_id        UUID         NOT NULL,
    candidato_id        UUID         NOT NULL,
    -- Etapas: a aplicação sempre grava etapa_id; ele só fica NULL se a etapa for excluída depois.
    etapa_anterior_id   UUID,
    etapa_id            UUID,
    -- Fotografia do nome da etapa no momento do evento; não acompanha renomeações nem exclusões.
    etapa_anterior_nome VARCHAR(100),
    etapa_nome          VARCHAR(100) NOT NULL,
    acao                VARCHAR(30)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Etapa anterior identificada sempre tem o nome gravado.
    CONSTRAINT ck_historico_etapa_anterior CHECK (etapa_anterior_id IS NULL OR etapa_anterior_nome IS NOT NULL),
    -- Excluir a vaga remove seu histórico.
    CONSTRAINT fk_historico_vaga FOREIGN KEY (vaga_id) REFERENCES vaga (id) ON DELETE CASCADE,
    CONSTRAINT fk_historico_recruiter FOREIGN KEY (recruiter_id) REFERENCES recruiter (id),
    CONSTRAINT fk_historico_vaga_recruiter FOREIGN KEY (vaga_id, recruiter_id)
        REFERENCES vaga (id, recruiter_id) ON DELETE CASCADE,
    -- NO ACTION, como fk_candidato_etapa: a exclusão da vaga cascateia candidatos e histórico juntos.
    CONSTRAINT fk_historico_candidato FOREIGN KEY (candidato_id, vaga_id) REFERENCES candidato (id, vaga_id),
    -- Excluir uma etapa não é bloqueado pelo histórico: só a coluna da etapa vira NULL e o nome permanece.
    CONSTRAINT fk_historico_etapa_anterior FOREIGN KEY (etapa_anterior_id, vaga_id)
        REFERENCES etapa (id, vaga_id) ON DELETE SET NULL (etapa_anterior_id),
    CONSTRAINT fk_historico_etapa FOREIGN KEY (etapa_id, vaga_id)
        REFERENCES etapa (id, vaga_id) ON DELETE SET NULL (etapa_id)
);

CREATE INDEX idx_historico_vaga ON historico (vaga_id);
CREATE INDEX idx_historico_candidato ON historico (candidato_id);
CREATE INDEX idx_historico_etapa_anterior ON historico (etapa_anterior_id);
CREATE INDEX idx_historico_etapa ON historico (etapa_id);
