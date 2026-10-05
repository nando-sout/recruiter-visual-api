-- Alteração manual de status da vaga: evento da vaga, sem candidato nem etapa.
ALTER TABLE historico
    ALTER COLUMN candidato_id DROP NOT NULL,
    ALTER COLUMN etapa_nome DROP NOT NULL,
    ADD COLUMN status_anterior VARCHAR(20),
    ADD COLUMN status_novo     VARCHAR(20),
    -- Eventos de candidato continuam exigindo candidato e etapa; STATUS_ALTERADO exige só os dois status.
    -- Os registros já existentes são todos de candidato e atendem à primeira condição.
    ADD CONSTRAINT ck_historico_tipo_evento CHECK (
        (acao = 'STATUS_ALTERADO'
            AND candidato_id IS NULL AND etapa_id IS NULL AND etapa_nome IS NULL
            AND etapa_anterior_id IS NULL AND etapa_anterior_nome IS NULL
            AND status_anterior IS NOT NULL AND status_novo IS NOT NULL)
        OR (acao <> 'STATUS_ALTERADO'
            AND candidato_id IS NOT NULL AND etapa_nome IS NOT NULL
            AND status_anterior IS NULL AND status_novo IS NULL));
