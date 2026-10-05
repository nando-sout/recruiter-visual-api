# Recruiter Visual — API

Backend do **Recruiter Visual**: uma aplicação de apoio visual ao processo seletivo, criada para recruiters individuais acompanharem vagas, etapas e candidatos de forma visual, registrando movimentações, avaliações e reprovações ao longo do processo.

O Recruiter Visual **não pretende ser um ATS completo**. Ele não publica vagas, não recebe candidaturas, não tem portal do candidato nem gestão de equipes: é uma ferramenta para um recruiter organizar e enxergar o próprio processo.

## O que o backend oferece

- **Cadastro e verificação de e-mail** — o recruiter se cadastra e confirma o e-mail com um código de 6 dígitos enviado por e-mail.
- **Login com JWT** — só é liberado depois da verificação do e-mail.
- **Isolamento por recruiter** — cada recruiter enxerga e altera somente as próprias vagas.
- **Vagas** — criar, listar, consultar e editar título e descrição.
- **Status da vaga** — `ATUANDO`, `PAUSADA`, `FECHADA` e `CANCELADA`, com transições controladas.
- **Etapas configuráveis** — cada vaga nasce com etapas padrão ou com as etapas informadas na criação; é possível criar, renomear, reordenar e excluir.
- **Candidatos** — cadastro na primeira etapa, listagem, consulta e edição dos dados.
- **Avanço de candidatos** — uma etapa por vez; chegar à etapa de proposta fecha a vaga.
- **Reprovação** — o candidato é marcado como reprovado na etapa em que está e sai da lista de ativos.
- **Histórico/auditoria** — criação, avanço e reprovação de candidatos e mudanças de status da vaga são registrados.
- **Taxa de reprovação por etapa** — reprovados na etapa sobre os candidatos que chegaram a ela.
- **Avaliação por etapa** — nota de 1 a 5 e observação, uma por candidato em cada etapa.

## Stack

| Tecnologia | Versão | Observação |
|---|---|---|
| Java | 25 | definida no `pom.xml` |
| Spring Boot | 4.1.1 | Web MVC, Data JPA, Validation, Mail |
| Spring Security | 7.1.1 | gerenciada pelo Spring Boot |
| JJWT (`io.jsonwebtoken`) | 0.13.0 | emissão e validação do JWT (HS256) |
| BCrypt | — | hash de senha, via Spring Security |
| PostgreSQL | 16 | banco usado no ambiente local |
| Flyway | 12.4.0 | gerenciada pelo Spring Boot |
| Maven | 3.9.16 | via Maven Wrapper (`mvnw`) |
| Mailpit | — | servidor SMTP de desenvolvimento, só no ambiente local |

Testes: JUnit 6, Spring Boot Test (MockMvc), AssertJ e Mockito.

## Arquitetura resumida

```
Frontend React
      ↓
   REST API
      ↓
  Spring Boot
      ↓
  PostgreSQL
```

O backend é responsável pelas regras de negócio e pela segurança. O frontend apenas consome a API: não decide permissões nem recalcula regras como avanço, reprovação ou taxa de reprovação.

Os detalhes ficam em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

## Segurança e isolamento

- A autenticação usa **JWT** enviado em `Authorization: Bearer <token>`. O token é assinado com HS256 e expira em 1 hora.
- O **subject do JWT é o id do recruiter**. É dele que o backend obtém o recruiter autenticado.
- Os endpoints de vaga **derivam o recruiter do usuário autenticado**. O cliente nunca informa `recruiterId` para decidir de quem é a vaga; se enviar, o valor é ignorado.
- Um recruiter **não acessa vagas de outro recruiter**, nem as etapas, candidatos e avaliações delas.
- A tentativa de acesso indevido recebe a mesma resposta de uma vaga inexistente: `404` com `{"message": "Vaga não encontrada"}`. Assim a API não revela que a vaga existe.
- Requisições sem token, ou com token inválido ou expirado, recebem `401`.
- O **login depende da verificação de e-mail**: com a senha correta e o e-mail ainda não verificado, a resposta é `403`.
- As senhas são armazenadas com **BCrypt**. O código de verificação de e-mail também é guardado apenas como hash.
- A API é stateless: não há sessão no servidor. Os únicos endpoints públicos são os de `/auth`.
- O **CORS** aceita uma única origem, a definida em `FRONTEND_URL`. Não há autenticação por cookie, e `allowCredentials` não está habilitado.

## Ambiente local

| Serviço | Endereço | Observação |
|---|---|---|
| Backend | `http://localhost:8080` | porta padrão quando `PORT` não está definida |
| PostgreSQL | `localhost:5433` | banco `recruiter_visual` |
| Mailpit (interface web) | `http://localhost:8025` | caixa de entrada dos e-mails enviados |
| Mailpit (SMTP) | `localhost:1025` | usado pelo backend para enviar e-mails |
| Frontend | `http://localhost:5173` | origem aceita pelo CORS quando `FRONTEND_URL` não está definida |

O **Mailpit** é usado para visualizar, durante o desenvolvimento, os e-mails de verificação: o código enviado no cadastro aparece na interface web em vez de ir para uma caixa de e-mail real.

PostgreSQL e Mailpit precisam estar disponíveis nos endereços acima; no ambiente de desenvolvimento do projeto eles rodam em containers (imagens `postgres:16` e `axllent/mailpit`). O repositório não traz arquivos para subi-los: o único arquivo Docker é o `Dockerfile` da própria aplicação (veja [Docker](#docker)).

## Configuração

Toda a configuração que muda entre ambientes vem de **variáveis de ambiente**, lidas em `src/main/resources/application.properties`. Não existem profiles `dev` ou `prod`: o mesmo arquivo serve aos dois ambientes, e o que não for definido usa o padrão local.

| Variável de ambiente | Obrigatória | Padrão local | Finalidade |
|---|---|---|---|
| `DB_URL` | não | `jdbc:postgresql://localhost:5433/recruiter_visual` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | não | usuário do banco local | usuário do banco |
| `DB_PASSWORD` | **sim** | — | senha do banco. Não tem padrão: deve ser sempre fornecida pelo ambiente |
| `JWT_SECRET` | **sim** | — | chave de assinatura do JWT; mínimo de 32 bytes. Sem ela a aplicação não inicia |
| `FRONTEND_URL` | não | `http://localhost:5173` | única origem permitida pelo CORS: a URL do frontend, sem barra no final |
| `PORT` | não | `8080` | porta HTTP da aplicação |
| `MAIL_HOST` | não | `localhost` | servidor SMTP |
| `MAIL_PORT` | não | `1025` | porta SMTP |
| `MAIL_USERNAME` | não | vazio | usuário SMTP |
| `MAIL_PASSWORD` | não | vazio | senha SMTP; vem somente do ambiente |
| `MAIL_FROM` | não | `no-reply@recruitervisual.local` | remetente dos e-mails |
| `JPA_SHOW_SQL` | não | `false` | `true` exibe no log o SQL executado |

**Segredos.** A senha do banco, a chave do JWT e a senha SMTP vêm somente de variáveis de ambiente; não ficam no código nem no `application.properties`. Nenhum segredo real deve ser colocado no Git, e arquivos `.env` são ignorados pelo `.gitignore`. Os exemplos desta documentação usam placeholders (`<...>`).

Sem `DB_PASSWORD`, a aplicação não consegue conectar ao banco, e a falha aparece como erro de autenticação do PostgreSQL, não como variável ausente.

### Produção

Em produção, o banco e o servidor de e-mail são serviços externos, apontados pelas mesmas variáveis:

- **Banco:** `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`. `DB_URL` precisa estar no formato JDBC (`jdbc:postgresql://<host>:<porta>/<banco>`); uma URL no formato `postgresql://usuario:senha@host/banco` não é aceita como está.
- **E-mail:** `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` e `MAIL_FROM`, no lugar do Mailpit. O projeto ainda não define propriedades de TLS para o SMTP (STARTTLS ou SSL).
- **Frontend:** `FRONTEND_URL` com a origem exata do frontend publicado.
- **Porta:** `PORT`, normalmente definida pela própria plataforma de hospedagem.
- **JWT:** `JWT_SECRET` com um valor próprio do ambiente, diferente do usado em desenvolvimento.

## Como executar

Pré-requisitos: Java 25, PostgreSQL e Mailpit disponíveis e as variáveis `DB_PASSWORD` e `JWT_SECRET` definidas. O Maven não precisa estar instalado: o projeto usa o Maven Wrapper.

No Windows use `.\mvnw.cmd`; em Linux/macOS, `./mvnw`.

```powershell
# Compilar
.\mvnw.cmd compile

# Executar os testes (JWT_SECRET não é necessária: os testes usam uma chave fictícia própria)
$env:DB_PASSWORD = "<senha do banco local>"
.\mvnw.cmd test

# Iniciar a aplicação
$env:DB_PASSWORD = "<senha do banco local>"
$env:JWT_SECRET = "<chave com pelo menos 32 bytes>"
.\mvnw.cmd spring-boot:run
```

Ao iniciar, o Flyway aplica as migrations pendentes e a aplicação valida o schema antes de subir.

## Docker

O `Dockerfile` na raiz gera a imagem da aplicação em duas etapas, ambas com **Java 25** (Eclipse Temurin):

1. **Build** — compila o projeto com o Maven Wrapper e gera o JAR executável. Os testes não rodam nessa etapa, porque dependem de um PostgreSQL acessível.
2. **Execução** — imagem final só com o JRE e o JAR, executada por um usuário sem privilégios de root.

A imagem não contém nenhuma credencial: toda a configuração chega pelas variáveis de ambiente da seção [Configuração](#configuração). A porta é a definida em `PORT` (padrão `8080`).

```powershell
# Construir a imagem
docker build -t recruiter-visual-api .

# Executar, apontando para um banco e um SMTP acessíveis a partir do container
docker run --rm -p 8080:8080 `
  -e DB_URL="jdbc:postgresql://<host>:<porta>/<banco>" `
  -e DB_USERNAME="<usuário>" `
  -e DB_PASSWORD="<senha>" `
  -e JWT_SECRET="<chave com pelo menos 32 bytes>" `
  -e FRONTEND_URL="<origem do frontend>" `
  -e MAIL_HOST="<host SMTP>" `
  -e MAIL_PORT="<porta SMTP>" `
  recruiter-visual-api
```

Dentro do container, `localhost` é o próprio container: os padrões locais de banco e de e-mail não alcançam serviços que rodam na máquina hospedeira.

## Migrations

O banco é versionado pelo **Flyway**. O schema nunca é gerado pelo Hibernate, que apenas o valida.

Existem atualmente **9 migrations** (`V1` a `V9`), todas aplicadas, em `src/main/resources/db/migration`:

| Versão | Conteúdo |
|---|---|
| V1 | recruiter |
| V2 | vaga |
| V3 | etapas da vaga |
| V4 | candidato |
| V5 | reprovação do candidato |
| V6 | histórico |
| V7 | mudança de status da vaga no histórico |
| V8 | verificação de e-mail do recruiter |
| V9 | avaliação do candidato por etapa |

## Testes

Estado validado em **05/10/2026**:

| Total | Passando | Falhando |
|---|---|---|
| 265 | 265 | 0 |

São testes de integração: sobem a aplicação e exercitam os endpoints contra um PostgreSQL real, o mesmo banco apontado por `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`. Por isso `DB_PASSWORD` precisa estar definida para rodar a suíte. Cada teste cria os próprios dados e os remove ao final. O envio de e-mail é substituído por um mock, então o Mailpit não é necessário para rodar a suíte.

## Estrutura

O código é organizado **por funcionalidade**, não por camada. Não há pacotes `controller`, `service` ou `dto`: o papel de cada classe está no sufixo do nome.

Pacotes, em `src/main/java/com/fernando/recruitervisual`:

| Pacote | Conteúdo |
|---|---|
| `auth` | cadastro, verificação de e-mail, login, JWT e configuração de segurança |
| `recruiter` | entidade e repositório do recruiter |
| `vaga` | vagas, etapas, candidatos, avaliações e histórico |

Papéis das classes:

| Sufixo | Papel |
|---|---|
| `*Controller` | rotas REST e conversão de erros em respostas HTTP |
| `*Service` | regras de negócio e transações |
| `*Repository` | acesso a dados (Spring Data JPA) |
| `*Request` / `*Response` | contratos de entrada e saída da API |
| `*Exception` | erros de regra de negócio |
| sem sufixo (`Vaga`, `Etapa`, `Candidato`, `Historico`, `CandidatoEtapaAvaliacao`, `Recruiter`) | entidades |

A segurança fica no pacote `auth`: `SecurityConfig`, `JwtAuthenticationFilter` e `JwtService`.

Outros diretórios:

- `src/main/resources/db/migration` — migrations do Flyway.
- `src/test/java` — testes, espelhando os pacotes.

## API

Os endpoints REST são organizados por domínio. As regras detalhadas de cada um ficam em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) e [`docs/FLUXOS.md`](docs/FLUXOS.md).

| Grupo | Rotas | Autenticação |
|---|---|---|
| Autenticação | `/auth/register`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/login` | públicas |
| Vagas | `/vagas`, `/vagas/{id}` | JWT |
| Status da vaga | `/vagas/{vagaId}/status` | JWT |
| Etapas | `/vagas/{vagaId}/etapas` | JWT |
| Candidatos | `/vagas/{vagaId}/candidatos` | JWT |
| Avanço e reprovação | `/vagas/{vagaId}/candidatos/{candidatoId}/avancar` e `/reprovar` | JWT |
| Avaliações | `/vagas/{vagaId}/candidatos/avaliacoes`, `/vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes` e `/vagas/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao` | JWT |

Os erros seguem um formato único: `{"message": "..."}` e, em erros de validação de campos, `{"message": "Dados inválidos", "errors": {"campo": "mensagem"}}`.

O projeto não tem Swagger/OpenAPI.

## SSO corporativo — roadmap

**SSO não está implementado.** Hoje a autenticação é própria: e-mail, senha e JWT emitido pelo backend. Esta seção registra apenas o caminho arquitetural já decidido para uma integração futura.

- **Padrão:** OpenID Connect sobre OAuth 2.0, com o fluxo Authorization Code + PKCE.
- **Provedores de identidade possíveis:** qualquer um compatível com OIDC, como Microsoft Entra ID, Okta ou Keycloak.
- **Identidade:** a identidade externa seria associada a um recruiter interno. O `recruiterId` interno seria preservado.
- **Autorização:** continua sendo responsabilidade do backend.

A divisão de responsabilidades é:

- o SSO responde **"quem é o usuário?"**;
- o Recruiter Visual continua respondendo **"o que esse usuário pode acessar?"**.

Como toda a regra de acesso depende apenas do `recruiterId` interno obtido na autenticação, o isolamento de vagas por recruiter seria mantido sem alteração.

## Documentação complementar

- [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md)
- [`docs/FLUXOS.md`](docs/FLUXOS.md)
