-- Verificação de e-mail no cadastro. email_verified_at NULL = e-mail ainda não confirmado.
-- O código de confirmação só é guardado como hash BCrypt; hash e expiração ficam NULL quando não há código ativo.
ALTER TABLE recruiter
    ADD COLUMN email_verified_at            TIMESTAMP,
    ADD COLUMN verification_code_hash       VARCHAR(255),
    ADD COLUMN verification_code_expires_at TIMESTAMP,
    ADD COLUMN verification_attempts        INTEGER NOT NULL DEFAULT 0;

-- Recruiters anteriores a este fluxo são usuários existentes e não podem ficar bloqueados no login.
UPDATE recruiter SET email_verified_at = NOW();
