CREATE TABLE etapa (
    id         UUID         PRIMARY KEY,
    vaga_id    UUID         NOT NULL,
    name       VARCHAR(100) NOT NULL,
    position   INTEGER      NOT NULL,
    proposta   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_etapa_vaga FOREIGN KEY (vaga_id) REFERENCES vaga (id) ON DELETE CASCADE,
    -- DEFERRABLE: criar, excluir e reordenar trocam posições entre linhas dentro da mesma transação.
    CONSTRAINT uk_etapa_vaga_position UNIQUE (vaga_id, position) DEFERRABLE INITIALLY DEFERRED
);

-- No máximo uma etapa de proposta por vaga.
CREATE UNIQUE INDEX uk_etapa_vaga_proposta ON etapa (vaga_id) WHERE proposta;

-- Vagas criadas antes desta migration recebem as etapas padrão.
INSERT INTO etapa (id, vaga_id, name, position, proposta)
SELECT gen_random_uuid(), v.id, d.name, d.position, d.proposta
FROM vaga v
CROSS JOIN (VALUES
    ('Envio de Shortlist',   1, FALSE),
    ('Entrevista Liderança', 2, FALSE),
    ('Entrevista RH',        3, FALSE),
    ('Proposta',             4, TRUE)
) AS d(name, position, proposta);
