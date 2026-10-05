# Arquitetura do Recruiter Visual

Este documento descreve a arquitetura do Recruiter Visual, suas principais regras e as decisões técnicas que orientaram a implementação. Ele retrata o que existe hoje; o que é evolução futura está marcado como roadmap.

Para os fluxos passo a passo, veja [`FLUXOS.md`](FLUXOS.md). Para execução local, veja o [`README.md`](../README.md).

## 1. Visão geral

O Recruiter Visual é uma aplicação de apoio visual ao processo seletivo. Ela foi criada para **recruiters individuais** acompanharem vagas, etapas e candidatos de forma visual, registrando movimentações, avaliações e reprovações ao longo do processo.

Ele **não é um ATS completo**: não publica vagas, não recebe candidaturas, não tem portal do candidato nem gestão de equipes. Cada recruiter usa o sistema para organizar e enxergar o próprio processo.

```text
Usuário
   ↓
Frontend React
   ↓ REST/HTTP
Spring Boot API
   ↓
PostgreSQL
```

O frontend é uma aplicação separada, que conversa com a API apenas por HTTP. No ambiente de desenvolvimento, o frontend alcança o backend por um proxy (`/api` → `http://localhost:8080`); publicado, ele chama a API a partir de outra origem e depende do CORS.

**CORS.** O CORS é configurado no Spring Security (`SecurityConfig`) e vale para todas as rotas.

- A **origem permitida** vem da variável de ambiente `FRONTEND_URL`. Sem ela, o padrão local é `http://localhost:5173`.
- **Apenas a origem configurada é permitida**; não há curinga. O valor precisa ser a origem exata, sem barra no final.
- **Métodos:** `GET`, `POST`, `PUT`, `DELETE` e `OPTIONS`.
- **Headers:** `Authorization` e `Content-Type`.
- `allowCredentials` **não está habilitado**: não há autenticação por cookie. O JWT continua sendo enviado em `Authorization: Bearer <token>`.

O filtro de CORS roda antes da autenticação: o preflight (`OPTIONS`), que o navegador envia sem token, é respondido por ele e não chega à verificação do JWT. Uma requisição vinda de outra origem é rejeitada com `403`. Requisições sem o cabeçalho `Origin` não são afetadas.

## 2. Responsabilidades por camada

### Frontend

- apresentação;
- navegação;
- interação;
- consumo da API;
- estado e cache da interface.

### Backend

- autenticação;
- autorização;
- ownership das vagas;
- validações;
- regras de negócio;
- movimentação de candidatos;
- histórico;
- avaliações;
- persistência.

### PostgreSQL

Persistência dos dados. Além de armazenar, o banco reforça parte das regras com constraints (unicidade, chaves estrangeiras compostas e checks), como última garantia de consistência.

**Regras críticas não dependem do frontend.** Toda validação feita na interface é repetida no backend, e é o backend que decide se uma operação é permitida.

## 3. Organização do backend

O código é organizado **por funcionalidade**, não por camada. Não existem pacotes `controller`, `service` ou `dto`; o papel de cada classe está no sufixo do nome.

Pacotes, em `com.fernando.recruitervisual`:

| Pacote | Conteúdo |
|---|---|
| `auth` | cadastro, verificação de e-mail, login, JWT e configuração de segurança |
| `recruiter` | entidade e repositório do recruiter |
| `vaga` | vagas, etapas, candidatos, avaliações e histórico |

Papéis:

| Papel | Como aparece no código | Responsabilidade |
|---|---|---|
| Controller | `AuthController`, `VagaController` | rotas REST, validação de entrada e conversão de exceções em respostas HTTP |
| Service | `*Service` | regras de negócio e limites de transação |
| Repository | `*Repository` | acesso a dados com Spring Data JPA |
| Entidade | `Recruiter`, `Vaga`, `Etapa`, `Candidato`, `Historico`, `CandidatoEtapaAvaliacao` | modelo persistido |
| DTO | `*Request` e `*Response` (records) | contratos de entrada e saída da API; as entidades não são expostas diretamente |
| Exception | `*Exception` | erros de regra de negócio, cada um mapeado para um status HTTP |

Todas as rotas de vaga, etapa, candidato e avaliação estão em `VagaController`, sob `/vagas`. As regras ficam em `VagaService`, `EtapaService`, `CandidatoService`, `AvaliacaoService` e `HistoricoService`.

### Padrão de erros

- Erro de regra ou de recurso: `{"message": "..."}`.
- Erro de validação de campos: `{"message": "Dados inválidos", "errors": {"campo": "mensagem"}}`, com status `400`.
- Regra de negócio violada (transição de status inválida, avanço não permitido etc.): status `409`.
- Recurso não encontrado ou fora do alcance do recruiter: status `404`.

### Transações e concorrência

Cada operação de escrita roda em uma transação. As operações que dependem da estrutura ou do estado da vaga começam obtendo um **lock pessimista na linha da vaga**: cadastrar, avançar, reprovar e editar candidato; criar, excluir e reordenar etapas; gravar avaliação; e alterar o status. Isso serializa as operações concorrentes sobre a mesma vaga: dois avanços simultâneos, um avanço e uma reordenação de etapas, ou duas gravações da mesma avaliação não se sobrepõem.

O registro no histórico acontece na mesma transação da operação: ou a operação e o histórico são gravados, ou nada é.

## 4. Autenticação

```text
Cadastro
   ↓
Verificação de e-mail
   ↓
Login
   ↓
JWT
   ↓
API autenticada
```

**Cadastro.** O recruiter informa nome, e-mail e senha. O e-mail é normalizado (sem espaços nas pontas e em minúsculas) e precisa ser único. A senha tem de 8 a 72 caracteres e é armazenada somente como hash **BCrypt**.

**Código de verificação.** No cadastro, o backend gera um código de 6 dígitos e o envia por e-mail. O código é guardado apenas como hash e vale por **15 minutos**. Depois de **5 tentativas incorretas**, o código é invalidado e é preciso pedir um novo. O envio do e-mail faz parte da transação do cadastro: se o envio falhar, o cadastro é desfeito e pode ser repetido.

**Reenvio.** O reenvio do código responde sempre com a mesma mensagem, exista ou não o e-mail, para não revelar quais e-mails estão cadastrados.

**Mailpit.** No ambiente local, o servidor SMTP é o Mailpit: os e-mails de verificação aparecem na interface web dele, em vez de irem para uma caixa real.

**Login.** O recruiter informa e-mail e senha. Credenciais incorretas recebem `401` com uma mensagem genérica. Com a senha correta e o e-mail ainda não verificado, a resposta é `403`; essa verificação só acontece depois da senha, para não revelar o estado da conta a quem não a conhece.

**JWT.** O login devolve um token assinado com HS256.

- O **subject** do token é o id do recruiter.
- O token **expira em 1 hora**.
- A chave de assinatura vem de variável de ambiente e precisa ter pelo menos 32 bytes; sem ela, a aplicação não inicia.
- O cliente envia o token em `Authorization: Bearer <token>`.

A API é stateless: não guarda sessão. Não há refresh token nem endpoint de logout; o token deixa de valer quando expira.

Os únicos endpoints públicos são os quatro de `/auth` (cadastro, verificação, reenvio e login). Todos os demais exigem um token válido; sem ele, a resposta é `401`.

## 5. Autorização e isolamento entre recruiters

> Autenticação responde "quem é o usuário?"
>
> Autorização responde "o que esse usuário pode acessar?"

No Recruiter Visual, a resposta à segunda pergunta é simples: **um recruiter acessa somente as próprias vagas**, e tudo o que está dentro delas.

- Cada vaga pertence a um recruiter.
- O recruiter autenticado é identificado pelo JWT: um filtro valida o token e coloca o id do recruiter como identidade da requisição.
- O backend **deriva o `recruiterId` dessa identidade**. O cliente não escolhe o `recruiterId`: ele não é aceito por URL, query ou body e, se enviado, é ignorado.
- Toda consulta ou alteração localiza a vaga por **id da vaga + recruiter autenticado**. Etapas, candidatos e avaliações são sempre buscados dentro da vaga já validada, nunca apenas pelo próprio id.
- Uma vaga inexistente e uma vaga de outro recruiter recebem a mesma resposta: `404` com `{"message": "Vaga não encontrada"}`. A API não revela que a vaga existe.
- Um candidato ou etapa que não pertence à vaga informada recebe `404` com "Candidato não encontrado" ou "Etapa não encontrada".

O banco reforça o isolamento com chaves estrangeiras compostas: uma etapa, um candidato, um registro de histórico ou uma avaliação não podem apontar para dados de outra vaga.

## 6. Modelo de domínio

```text
Recruiter
   │
   └── Vagas
         │
         ├── Etapas
         │
         ├── Candidatos
         │      │
         │      └── Avaliações (uma por candidato + etapa)
         │
         └── Histórico
                ├── eventos de candidato (criado, avançado, reprovado)
                └── eventos da vaga (status alterado)
```

| Conceito | Descrição | Relações |
|---|---|---|
| Recruiter | usuário do sistema | dono de várias vagas |
| Vaga | processo seletivo acompanhado | pertence a um recruiter; tem etapas, candidatos e histórico |
| Etapa | fase do processo | pertence a uma vaga; tem uma posição |
| Candidato | pessoa acompanhada no processo | pertence a uma vaga; está em uma etapa |
| Histórico | registro de eventos | pertence a uma vaga; eventos de candidato referenciam o candidato e as etapas envolvidas |
| Avaliação por etapa | nota e observação | pertence a um candidato e a uma etapa da mesma vaga |

## 7. Vagas

Uma vaga tem:

- **código** — informado pelo recruiter, único entre todas as vagas do sistema e imutável;
- **título** e **descrição** — editáveis;
- **recruiter proprietário** — definido na criação a partir do token e imutável;
- **status**.

Não existe exclusão de vaga pela API.

### Status

| Status | Significado |
|---|---|
| `ATUANDO` | vaga em andamento; estado inicial de toda vaga |
| `PAUSADA` | vaga suspensa temporariamente |
| `FECHADA` | um candidato chegou à etapa de proposta |
| `CANCELADA` | vaga encerrada sem fechamento |

Transições:

```text
ATUANDO  ⇄  PAUSADA
ATUANDO  →  CANCELADA
PAUSADA  →  CANCELADA
ATUANDO  →  FECHADA      (somente pelo avanço de um candidato à proposta)
```

- Pausar, retomar e cancelar são transições manuais, feitas pelo endpoint de status.
- `FECHADA` **não é definida manualmente**: ela ocorre quando um candidato avança para a etapa de proposta.
- `CANCELADA` e `FECHADA` são **finais**: não há transição de saída.
- Qualquer transição fora dessas regras é rejeitada com `409`.

### Bloqueios por status

| Operação | `ATUANDO` | `PAUSADA` | `FECHADA` | `CANCELADA` |
|---|---|---|---|---|
| Consultar vaga, etapas, candidatos, reprovados e avaliações | sim | sim | sim | sim |
| Cadastrar candidato | sim | não | não | não |
| Avançar candidato | sim | não | não | não |
| Reprovar candidato | sim | não | não | não |
| Editar dados do candidato | sim | sim | sim | sim |
| Avaliar candidato em uma etapa | sim | sim | sim | sim |

Editar um candidato e avaliá-lo são tratados como edição de informação, não como movimentação do processo; por isso continuam disponíveis em qualquer status.

## 8. Etapas

- As etapas são **configuráveis por vaga**.
- A **ordem** é importante: ela define o caminho do candidato. Cada etapa tem uma posição, única dentro da vaga.
- A mesma lista de etapas é usada pelo funil e pelo Kanban.
- Os candidatos avançam de uma etapa para a seguinte.

### Etapa de proposta

Toda vaga tem exatamente uma **etapa de proposta**, que é a etapa de fechamento.

- Ela é identificada por uma **propriedade de domínio** (o campo `proposta`), e **não pelo nome**. Uma etapa chamada "Proposta" sem essa marca é uma etapa comum; a etapa de proposta pode se chamar "Oferta".
- Ela é sempre a **última** etapa e não pode mudar de posição.
- Pode ser **renomeada**.
- **Não pode ser removida**.

### Criação da vaga

- Se nenhuma lista de etapas for informada, a vaga recebe as etapas padrão: "Envio de Shortlist", "Entrevista Liderança", "Entrevista RH" e "Proposta".
- Se uma lista for informada, a vaga é criada com exatamente essas etapas, na ordem recebida. A lista precisa ter exatamente uma etapa de proposta, e ela deve ser a última.
- Vaga e etapas são gravadas na mesma transação.

### Alterações depois da criação

- Uma etapa nova entra imediatamente antes da etapa de proposta.
- Etapas comuns podem ser renomeadas, reordenadas e excluídas.
- Uma etapa que tem candidatos, ativos ou reprovados, não pode ser excluída.
- Uma etapa pela qual candidatos já passaram, mas na qual não há mais ninguém, pode ser excluída.

As operações de etapa não são bloqueadas pelo status da vaga.

## 9. Candidatos

- Um candidato **pertence a uma vaga** e tem uma **etapa atual**.
- Ao ser cadastrado, entra sempre na **primeira etapa** da vaga; o cliente não escolhe a etapa.
- Pode **avançar**, ser **reprovado** e ter os dados **editados**.
- Não existe exclusão de candidato pela API.

Campos: nome, LinkedIn, stack, nota geral (`rating`), resumo do LinkedIn (`linkedinAbout`), opinião do recruiter (`recruiterOpinion`) e opinião técnica (`technicalOpinion`). Nome e stack são obrigatórios. No cadastro, a nota geral aceita valores de 0 a 5 ou ausência de nota.

### Avanço

- O candidato avança **uma etapa por vez**, para a próxima etapa pela ordem atual.
- O cliente informa a etapa em que acredita que o candidato está. Se o candidato já não estiver nela, a operação é rejeitada; isso evita avanços duplicados a partir de uma tela desatualizada.
- Chegar à etapa de proposta **fecha a vaga** na mesma transação.
- Um candidato reprovado não avança, e um candidato na etapa de proposta não avança mais.

### Edição

A edição substitui os dados do candidato (os campos listados acima). Ela não altera a etapa atual, os dados de reprovação nem a data de criação, e não gera histórico. Na edição, a nota geral aceita valores de 1 a 5 ou ausência de nota.

### Listagens

- A listagem de candidatos devolve somente os **ativos**.
- Os **reprovados** têm uma listagem própria.
- A consulta de um candidato pelo id funciona para ativos e reprovados.

## 10. Reprovação

- Existe um **endpoint específico** de reprovação.
- A reprovação acontece na **etapa atual** do candidato e registra a etapa em que ocorreu.
- O **nome da etapa é copiado** no momento da reprovação, para que o contexto não mude se a etapa for renomeada depois.
- O candidato reprovado **deixa o fluxo ativo**: sai da listagem de ativos e não pode mais avançar. Ele continua vinculado à vaga e à etapa em que estava.
- Ele permanece disponível no **funil de reprovados**: a listagem de reprovados e a contagem de reprovados por etapa.
- A **reprovação na etapa de proposta é bloqueada**: chegar à proposta fecha a vaga, e reprovar ali exigiria reabri-la.
- Um candidato já reprovado não pode ser reprovado de novo, e não existe operação para desfazer a reprovação.
- **Não existe motivo de reprovação** no modelo atual.
- **Não há envio de e-mail de reprovação.** O sistema não envia nenhuma comunicação a candidatos.

## 11. Histórico / auditoria

O histórico registra os acontecimentos do processo. Os eventos existentes são:

| Evento | Quando ocorre | O que registra |
|---|---|---|
| `CANDIDATO_CRIADO` | cadastro do candidato | candidato e a etapa em que entrou |
| `CANDIDATO_AVANCADO` | avanço de etapa | candidato, etapa de origem e etapa de destino |
| `CANDIDATO_REPROVADO` | reprovação | candidato e a etapa em que foi reprovado |
| `STATUS_ALTERADO` | pausar, retomar ou cancelar a vaga | status anterior e status novo |

Todo evento registra também a vaga, o recruiter que realizou a operação e a data.

O fechamento automático da vaga não gera `STATUS_ALTERADO`: ele é consequência do `CANDIDATO_AVANCADO` que levou o candidato à etapa de proposta. Edição de candidato e avaliação por etapa também não geram eventos.

O histórico existe para:

- **rastreabilidade** — saber o que aconteceu, quando e por quem;
- **métricas** — é a base da taxa de reprovação por etapa;
- **compreensão do caminho do candidato** — por quais etapas ele passou, mesmo depois de avançar.

O histórico é somente gravado e usado internamente: não há endpoint para consultá-lo.

### Exclusão de etapas

Uma etapa pela qual candidatos já passaram pode ser excluída. Para não perder o contexto:

- a referência à etapa no histórico **fica sem a chave estrangeira** (passa a ser nula);
- o **nome da etapa é preservado**, pois foi copiado no momento do evento.

Assim o histórico continua legível mesmo quando a etapa deixa de existir, e a exclusão de etapas não é bloqueada pelo histórico.

## 12. Taxa de reprovação por etapa

```text
Taxa de reprovação =
candidatos reprovados na etapa
÷
candidatos que chegaram à etapa
× 100
```

- A **chegada** é determinada pelo histórico, não pela etapa atual dos candidatos. Um candidato que chegou à etapa e depois avançou continua contando.
- `CANDIDATO_CRIADO` e `CANDIDATO_AVANCADO` contam como chegada à etapa de destino.
- `CANDIDATO_REPROVADO` **não** conta como chegada.
- Os candidatos são contados de forma **distinta**: se uma reordenação de etapas levar o mesmo candidato de volta a uma etapa, ele conta uma vez só.
- **Zero chegadas** resulta em ausência de taxa (`null`): não há base para o cálculo.
- **Chegadas maiores que zero e nenhuma reprovação** resultam em `0`.
- O valor tem uma casa decimal e é **calculado pelo backend**. Ele é devolvido na listagem de etapas, junto com a quantidade de candidatos ativos, de reprovados e de candidatos que chegaram à etapa.

## 13. Avaliação por etapa

A avaliação é feita por combinação:

```text
Candidato + Etapa
```

e não é uma nota única para o candidato. O mesmo candidato pode ter uma nota na primeira etapa, outra na segunda e nenhuma na terceira; alterar uma não afeta as outras.

- A nota (`rating`) vai de **1 a 5** e é obrigatória.
- A **observação** é opcional, com limite de **5000 caracteres**.
- Existe no máximo uma avaliação por candidato em cada etapa. O mesmo endpoint **cria ou atualiza**.
- A avaliação só é permitida para uma etapa que o candidato **já alcançou** (a atual ou uma anterior), verificado pelo histórico.
- A avaliação **não movimenta** o candidato, **não altera o status** da vaga e **não gera histórico**.
- Funciona em vaga **pausada, fechada ou cancelada**.
- Funciona para **candidato reprovado**.
- Nenhuma avaliação é criada automaticamente: ela só existe quando o recruiter a registra.
- Excluir uma etapa apaga as avaliações feitas nela.

Há dois endpoints de consulta: um **individual**, com as avaliações de um candidato, e um **bulk**, com as avaliações de todos os candidatos da vaga.

O campo `rating` do candidato permanece no modelo como nota geral e é **independente** da avaliação por etapa: um não altera o outro.

## 14. Performance da avaliação

O Kanban mostra a avaliação em cada card. A decisão foi não fazer:

```text
1 GET de avaliação para cada candidato
```

e sim:

```text
1 GET das avaliações da vaga
        ↓
frontend distribui por candidato + etapa
```

O backend tem uma consulta bulk, filtrada pela vaga, que busca todas as avaliações em uma única consulta ao banco, já com os dados do candidato e da etapa. Cada item da resposta traz o id do candidato e o id da etapa, e o frontend usa esse endpoint para montar os cards. Isso evita N+1 requisições HTTP.

## 15. Banco de dados e migrations

- O banco é **PostgreSQL**.
- O schema é versionado pelo **Flyway**, com migrations em `src/main/resources/db/migration`. O Hibernate apenas valida o schema; nunca o gera.
- O estado atual é a versão **V9**.

| Grupo de evolução | Migrations |
|---|---|
| Schema inicial: recruiter, vaga, etapas e candidato | V1 a V4 |
| Reprovação do candidato | V5 |
| Histórico | V6 |
| Status da vaga no histórico | V7 |
| Verificação de e-mail | V8 |
| Avaliação por etapa | V9 |

Garantias mantidas pelo banco, além das validações da aplicação:

- e-mail do recruiter e código da vaga únicos;
- posição única por vaga e no máximo uma etapa de proposta por vaga;
- candidato, histórico e avaliação sempre ligados a etapas e candidatos da mesma vaga;
- consistência dos dados de reprovação (reprovado sempre tem etapa e nome; não reprovado não tem);
- no máximo uma avaliação por candidato e etapa, com nota entre 1 e 5.

## 16. Segurança de dados

- As senhas são armazenadas com **BCrypt**; a senha em texto nunca é gravada. O código de verificação de e-mail também é guardado só como hash.
- A chave de assinatura do JWT **não fica no código nem na documentação**: é fornecida por variável de ambiente.
- Credenciais não devem ser versionadas. A senha do banco e as credenciais SMTP também vêm de variáveis de ambiente.
- O **ownership é verificado no backend**, em toda operação.
- O **frontend não é fonte de verdade para autorização**: esconder um botão na interface não é o que impede uma operação.
- As respostas da API nunca incluem o hash da senha nem dados de outro recruiter.

## 17. Decisões arquiteturais importantes

### Backend como fonte de verdade

As regras críticas ficam no backend: transições de status, avanço, reprovação, regras da etapa de proposta, cálculo da taxa de reprovação. O frontend exibe e solicita; quem decide é o backend.

### Ownership pelo JWT

O backend nunca confia em um `recruiterId` enviado pelo cliente. O recruiter é sempre o do token, e toda vaga é buscada junto com ele.

### Funil e Kanban compartilham as mesmas etapas

Existe uma única estrutura de etapas por vaga. O funil e o Kanban são duas visualizações dos mesmos dados, o que evita divergência entre elas.

### Histórico para métricas

A taxa de reprovação depende do histórico, e não apenas do estado atual dos candidatos. Só o histórico sabe quantos candidatos chegaram a uma etapa e depois saíram dela.

### Avaliação por etapa

A nota representa a avaliação daquele candidato naquela etapa específica, e não uma nota geral.

### Bulk GET para avaliações

Uma requisição devolve as avaliações da vaga inteira, evitando N+1 requisições do frontend.

### Status bloqueiam mutações

Uma vaga que não está `ATUANDO` continua consultável, mas não permite cadastrar, avançar ou reprovar candidatos.

### SSO separado da autorização

No futuro, um provedor corporativo poderá autenticar o usuário, mas o Recruiter Visual continuará responsável pelo vínculo com o recruiter interno e pelas permissões.

## 18. SSO corporativo — futuro

> **Roadmap.** SSO não está implementado. Hoje a autenticação é própria: e-mail, senha e JWT emitido pelo backend. Esta seção registra apenas o caminho arquitetural previsto.

```text
Usuário
   ↓
Recruiter Visual
   ↓
Identity Provider
   ↓
OIDC / OAuth2
   ↓
Identidade autenticada
   ↓
Recruiter interno
   ↓
Autorização do Recruiter Visual
```

**Protocolo.** OpenID Connect, com o fluxo Authorization Code + PKCE.

**Provedores possíveis.** Qualquer provedor de identidade compatível com OIDC, como Microsoft Entra ID, Okta ou Keycloak.

**Conceitos envolvidos.**

| Conceito | Papel |
|---|---|
| Issuer | endereço do provedor de identidade; identifica quem emitiu o token e de onde vêm as chaves para validá-lo |
| Client ID | identificador do Recruiter Visual registrado no provedor |
| Redirect URI | endereço do Recruiter Visual para onde o provedor devolve o usuário depois da autenticação |
| Scopes `openid`, `profile`, `email` | o que a aplicação pede ao provedor: a identidade, os dados básicos e o e-mail |
| Claims `sub`, `email`, `name` | o que o provedor informa: o identificador estável do usuário, o e-mail e o nome |

**Associação entre identidade externa e recruiter interno.** A identidade externa (issuer + `sub`) seria associada a um recruiter interno, criado ou vinculado no primeiro acesso. O `recruiterId` interno seria preservado, e é dele que todas as regras de acesso dependem.

**SSO autentica. Recruiter Visual autoriza.**

O provedor responde "quem é o usuário?". O Recruiter Visual continua respondendo "o que esse usuário pode acessar?", com a mesma regra de hoje: o recruiter acessa somente as próprias vagas.

## 19. Estado atual

| Item | Estado |
|---|---|
| Backend | Spring Boot em funcionamento |
| Banco | PostgreSQL |
| Migrations | Flyway, versão V9 |
| Autenticação | cadastro, verificação de e-mail, login e JWT |
| Isolamento | por recruiter |
| Domínio | vagas, etapas e candidatos |
| Histórico | eventos de candidato e de status da vaga |
| Reprovação | implementada, com funil de reprovados |
| Taxa de reprovação | calculada por etapa |
| Avaliação | por etapa, com consulta individual e bulk |
| Testes | 265 passando em 02/10/2026 |
| SSO | não implementado (roadmap) |
