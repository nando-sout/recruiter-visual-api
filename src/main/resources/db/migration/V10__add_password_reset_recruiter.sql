-- Recuperação de senha. O código de reset só é guardado como hash BCrypt, em colunas próprias:
-- não se mistura com o código de verificação de e-mail da V8.
ALTER TABLE recruiter
    ADD COLUMN password_reset_code_hash       VARCHAR(255),
    ADD COLUMN password_reset_code_expires_at TIMESTAMP,
    ADD COLUMN password_reset_attempts        INTEGER NOT NULL DEFAULT 0;
