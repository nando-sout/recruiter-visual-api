CREATE TABLE vaga (
    id           UUID          PRIMARY KEY,
    code         VARCHAR(255)  NOT NULL UNIQUE,
    title        VARCHAR(150)  NOT NULL,
    description  VARCHAR(5000) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    recruiter_id UUID          NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vaga_recruiter FOREIGN KEY (recruiter_id) REFERENCES recruiter (id)
);
