# Fluxos do Recruiter Visual

Este documento é um guia funcional: mostra o que acontece em cada fluxo do Recruiter Visual, da entrada do recruiter no sistema até o acompanhamento dos candidatos. Ele descreve o comportamento atual da API e, onde indicado, como o frontend a utiliza.

Para a arquitetura e as decisões técnicas, veja [`ARQUITETURA.md`](ARQUITETURA.md).

## 1. Visão geral dos fluxos

```text
Cadastro
  ↓
Verificação de e-mail
  ↓
Login
  ↓
Minhas vagas
  ↓
Criar/abrir vaga  (as etapas podem ser definidas na criação)
  ↓
Cadastrar candidatos
  ↓
Acompanhar no funil/Kanban
  ↓
Avaliar / avançar / reprovar
  ↓
Acompanhar as métricas por etapa
```

As movimentações ficam registradas no histórico, que hoje alimenta a taxa de reprovação por etapa. Não há tela ou endpoint para consultar o histórico diretamente.

## 2. Cadastro e verificação de e-mail

```text
POST /auth/register
        ↓
recruiter criado (e-mail ainda não verificado)
        ↓
código de 6 dígitos enviado por e-mail
        ↓
POST /auth/verify-email
        ↓
e-mail verificado → login liberado
```

1. O recruiter acessa o cadastro e informa **nome, e-mail e senha**.
2. O backend valida os dados: e-mail em formato válido e ainda não cadastrado; senha de 8 a 72 caracteres.
3. O recruiter é criado. A senha é armazenada somente como hash **BCrypt**.
4. Um **código de verificação de 6 dígitos** é enviado para o e-mail informado. O código vale por **15 minutos**.
5. O recruiter informa o e-mail e o código.
6. Com o código correto, o e-mail é marcado como verificado e o código deixa de valer.
7. A partir daí o login é permitido.

Situações de erro:

| Situação | Resposta |
|---|---|
| E-mail já cadastrado | `409` — "Este e-mail já está cadastrado" |
| Dados inválidos no cadastro | `400` com o campo e a mensagem |
| Falha no envio do e-mail | `503`; o cadastro é desfeito e pode ser repetido |
| Código incorreto, expirado, já utilizado ou e-mail inexistente | `400` — "Código inválido ou expirado" |

A resposta para código inválido é sempre a mesma, qualquer que seja o motivo. Depois de **5 tentativas incorretas**, o código é invalidado e é preciso pedir outro.

**Reenvio.** `POST /auth/resend-verification` gera um novo código, que substitui o anterior. A resposta é sempre a mesma mensagem, exista ou não o e-mail e esteja ele verificado ou não.

**Ambiente local.** Os e-mails são entregues ao Mailpit: o código de verificação pode ser lido na interface web dele (`http://localhost:8025`).

## 3. Login

```text
Credenciais
   ↓
Backend valida e-mail e senha
   ↓
Verifica se o e-mail foi confirmado
   ↓
JWT
   ↓
Frontend mantém a sessão
   ↓
Chamadas autenticadas
```

- `POST /auth/login` recebe e-mail e senha.
- E-mail inexistente ou senha incorreta: `401` com a mesma mensagem genérica, "E-mail ou senha inválidos".
- Senha correta com **e-mail ainda não verificado**: `403`. O login exige e-mail verificado.
- Login aceito: a resposta traz o **token JWT** e os dados básicos do recruiter (id, nome e e-mail).

O JWT representa a identidade autenticada: ele identifica o recruiter. O token **expira em 1 hora**. Não existe refresh token; depois da expiração, é preciso fazer login de novo.

O frontend guarda o token e o envia em todas as chamadas autenticadas, no cabeçalho `Authorization: Bearer <token>`.

### Recuperação de senha

```text
POST /auth/forgot-password
        ↓
código de 6 dígitos enviado por e-mail
        ↓
POST /auth/reset-password (e-mail, código e nova senha)
        ↓
senha alterada → login com a nova senha
```

1. O recruiter informa o **e-mail** em `POST /auth/forgot-password`.
2. Se o e-mail estiver cadastrado, um **código de recuperação de 6 dígitos** é enviado para ele. O código vale por **15 minutos** e é guardado apenas como hash.
3. O recruiter informa **e-mail, código e nova senha** em `POST /auth/reset-password`. O código é conferido nessa mesma chamada: não há uma etapa separada só para validá-lo.
4. Com o código correto, a nova senha é armazenada como hash **BCrypt** e o código deixa de valer, na mesma operação.
5. O login passa a aceitar somente a nova senha.

A resposta de `POST /auth/forgot-password` é **sempre a mesma mensagem**, com `200`: e-mail inexistente, pedido repetido antes do intervalo mínimo e falha no envio do e-mail não mudam a resposta. Assim a API não revela quais e-mails estão cadastrados. Uma falha de envio fica registrada no log do servidor, e o código que não foi entregue é descartado.

- **Intervalo entre pedidos.** O mesmo e-mail gera um novo código no máximo **uma vez a cada 60 segundos**. Um pedido feito antes disso não gera código nem envia e-mail, e o código atual continua valendo.
- **Novo código.** Passado o intervalo, um novo pedido gera outro código, que substitui o anterior.
- **Tentativas.** Depois de **5 tentativas incorretas**, o código é invalidado e é preciso pedir outro, respeitando o intervalo de 60 segundos.
- **Nova senha.** Segue a regra do cadastro: de 8 a 72 caracteres e no máximo 72 bytes. Uma senha recusada não conta como tentativa.
- **Conta não verificada.** Também pode recuperar a senha. A troca **não verifica o e-mail**: o login continua respondendo `403` até a verificação da seção 2. O código de recuperação e o de verificação são independentes, e um não serve no lugar do outro.
- **Tokens já emitidos.** A troca de senha não invalida tokens: os que já existem continuam válidos até expirar.

| Situação | Resposta |
|---|---|
| Pedido de recuperação, qualquer que seja o e-mail | `200` com a mensagem genérica |
| Código incorreto, expirado, já utilizado, invalidado ou e-mail sem pedido de recuperação | `400` — "Código inválido ou expirado" |
| Código fora do formato ou nova senha inválida | `400` com o campo e a mensagem |

**Ambiente local.** O e-mail de recuperação também é entregue ao Mailpit (`http://localhost:8025`).

## 4. Isolamento entre recruiters

```text
Recruiter A
   ↓ JWT
API
   ↓ recruiterId autenticado
Somente vagas de A
```

O backend obtém o recruiter a partir do token e usa essa identidade em toda consulta e alteração. O cliente não informa de quem é a vaga.

```text
Recruiter A tenta acessar vaga de B
              ↓
          Backend
              ↓
   404 — "Vaga não encontrada"
```

A resposta é a mesma de uma vaga que não existe, para qualquer operação: consultar, editar, mudar status, mexer em etapas, candidatos ou avaliações.

Isso é garantido **pelo backend**, e não apenas pela interface: mesmo uma chamada feita diretamente à API, com o id correto da vaga de outro recruiter, é recusada.

## 5. Minhas vagas

- `GET /vagas` devolve **apenas as vagas do recruiter autenticado**.
- Cada vaga traz código, título, descrição, status e data de criação.
- Uma conta nova não tem vagas: a lista vem vazia, e o frontend convida o recruiter a criar a primeira.
- `GET /vagas/{id}` devolve uma vaga; `PUT /vagas/{id}` altera título e descrição. O código e o dono não mudam.
- **Não existe exclusão de vaga.**
- Vagas em estado final (`FECHADA` ou `CANCELADA`) continuam aparecendo e podem ser consultadas normalmente.

## 6. Criação de vaga

`POST /vagas` recebe:

| Campo | Regra |
|---|---|
| `code` | obrigatório; único entre todas as vagas do sistema |
| `title` | obrigatório; até 150 caracteres |
| `description` | obrigatório; até 5000 caracteres |
| `etapas` | opcional |

Toda vaga nasce com o status `ATUANDO` e pertence ao recruiter autenticado. Um código já utilizado, por qualquer recruiter, é recusado com `409`.

### Sem `etapas`

A vaga recebe as etapas padrão, nesta ordem:

1. Envio de Shortlist
2. Entrevista Liderança
3. Entrevista RH
4. Proposta (etapa de proposta)

### Com etapas personalizadas

```text
POST /vagas
      ↓
lista de etapas personalizada
      ↓
backend valida
      ↓
etapas são criadas na ordem enviada
```

Cada item da lista tem `name` e `proposta` (verdadeiro ou falso). Regras:

- a lista precisa ter **pelo menos uma etapa**: uma lista vazia é recusada;
- **exatamente uma** etapa marcada como proposta;
- a etapa de proposta deve ser a **última**;
- `name` obrigatório, não pode ser só espaços, até 100 caracteres;
- `proposta` obrigatório em cada item;
- a **ordem da lista é preservada**: a posição de cada etapa é a posição na lista;
- os ids das etapas são gerados pelo backend.

Uma configuração inválida é recusada com `400`, e nada é gravado: a vaga e as etapas são criadas na mesma transação.

## 7. Configuração das etapas

Depois de criada a vaga, as etapas podem ser alteradas:

| Operação | Endpoint | Comportamento |
|---|---|---|
| Listar | `GET /vagas/{vagaId}/etapas` | etapas na ordem atual, com as contagens e a taxa de reprovação |
| Adicionar | `POST /vagas/{vagaId}/etapas` | a nova etapa entra imediatamente antes da etapa de proposta |
| Renomear | `PUT /vagas/{vagaId}/etapas/{etapaId}` | altera só o nome |
| Reordenar | `PUT /vagas/{vagaId}/etapas/ordem` | recebe a lista completa de ids na nova ordem |
| Excluir | `DELETE /vagas/{vagaId}/etapas/{etapaId}` | remove a etapa e renumera as demais |

### Etapa de proposta

- Permanece sempre como **última**: a reordenação que tenta movê-la é recusada.
- **Não pode ser excluída.**
- **Pode ser renomeada.** Ela é reconhecida pela marca de proposta, não pelo nome.

### Etapa com candidatos

Uma etapa que tem candidatos, **ativos ou reprovados**, **não pode ser excluída**: a operação é recusada com `409` — "Não é possível excluir etapas que possuem candidatos."

Não existe operação para transferir candidatos de uma etapa para outra na exclusão. A única movimentação de candidato é o avanço para a próxima etapa.

### Etapa pela qual candidatos já passaram

Uma etapa em que não há mais nenhum candidato pode ser excluída, mesmo que candidatos tenham passado por ela.

- O **histórico mantém o contexto**: os eventos antigos conservam o nome que a etapa tinha.
- As **avaliações** feitas nessa etapa são apagadas junto com ela.

### Reordenação

A nova ordem precisa conter exatamente todas as etapas da vaga, sem repetição. O avanço dos candidatos passa a seguir a nova ordem.

## 8. Cadastro de candidato

```text
Vaga ATUANDO
   ↓
Cadastrar candidato
   ↓
Backend valida
   ↓
Candidato entra na primeira etapa
   ↓
Histórico CANDIDATO_CRIADO
```

`POST /vagas/{vagaId}/candidatos` recebe:

| Campo | Regra |
|---|---|
| `name` | obrigatório; até 150 caracteres |
| `stack` | obrigatório; até 500 caracteres |
| `linkedin` | opcional; até 500 caracteres |
| `rating` | opcional; nota geral de 0 a 5 |
| `linkedinAbout` | opcional; até 5000 caracteres |
| `recruiterOpinion` | opcional; até 5000 caracteres |
| `technicalOpinion` | opcional; até 5000 caracteres |

- O candidato entra sempre na **primeira etapa** da vaga, pela ordem atual. O cliente não escolhe a etapa.
- Só é possível cadastrar em vaga **`ATUANDO`**. Em vaga `PAUSADA`, `FECHADA` ou `CANCELADA`, a resposta é `409`.

Consultas:

- `GET /vagas/{vagaId}/candidatos` — candidatos **ativos**;
- `GET /vagas/{vagaId}/candidatos/reprovados` — candidatos **reprovados**;
- `GET /vagas/{vagaId}/candidatos/{candidatoId}` — um candidato, ativo ou reprovado.

## 9. Avanço do candidato

```text
Etapa atual
   ↓
Avançar
   ↓
Próxima etapa
   ↓
Histórico CANDIDATO_AVANCADO
```

`POST /vagas/{vagaId}/candidatos/{candidatoId}/avancar` recebe a etapa em que o candidato está (`etapaId`).

O backend valida a transição:

- o candidato precisa estar na etapa informada. Se já tiver sido movido, a operação é recusada; isso protege contra um avanço duplicado a partir de uma tela desatualizada;
- o candidato não pode estar reprovado;
- o candidato não pode já estar na etapa de proposta;
- a vaga precisa estar `ATUANDO`.

Qualquer uma dessas recusas responde `409`.

Aceito o avanço, o candidato vai para a **próxima etapa** pela ordem atual, uma etapa por vez, e o evento `CANDIDATO_AVANCADO` registra a etapa de origem e a de destino.

### Fechamento automático

```text
Candidato avança para a etapa de proposta
              ↓
      Vaga passa a FECHADA
```

Chegar à etapa de proposta **fecha a vaga**, na mesma operação. Esse fechamento **não gera um evento separado de `STATUS_ALTERADO`**: o registro é o próprio `CANDIDATO_AVANCADO` que levou o candidato à proposta.

Com a vaga fechada, os demais candidatos não podem mais ser avançados nem reprovados.

## 10. Avaliação por etapa

```text
Candidato
   ↓
Etapa atual ou já alcançada
   ↓
Avaliação
   ↓
1 a 5 estrelas + observação opcional
```

`PUT /vagas/{vagaId}/candidatos/{candidatoId}/etapas/{etapaId}/avaliacao` recebe `rating` e `observacao`.

- A avaliação pertence à combinação **candidato + etapa**. Cada etapa pode ter a sua própria avaliação, e uma não altera a outra.
- `rating` é obrigatório, de **1 a 5**.
- `observacao` é opcional, com até 5000 caracteres.
- Se ainda não existe avaliação daquele candidato naquela etapa, ela é criada; se já existe, é **atualizada**.
- Só é possível avaliar uma etapa que o candidato **já alcançou**: a atual ou uma anterior. Para uma etapa a que ele ainda não chegou, a resposta é `409`.
- A avaliação **não movimenta** o candidato e **não gera histórico**.
- Pode ser feita em vaga **pausada, fechada ou cancelada**.
- Pode ser feita para **candidato reprovado**.

Consultas:

- `GET /vagas/{vagaId}/candidatos/{candidatoId}/avaliacoes` — avaliações de um candidato, na ordem das etapas;
- `GET /vagas/{vagaId}/candidatos/avaliacoes` — avaliações de todos os candidatos da vaga.

O frontend busca as avaliações da vaga **em lote**, pelo segundo endpoint, e distribui cada uma no card correspondente. Assim não é feita uma requisição por card.

A nota geral do candidato (`rating`, no cadastro e na edição) é independente da avaliação por etapa.

## 11. Reprovação

```text
Candidato
   ↓
Reprovar na etapa atual
   ↓
Histórico CANDIDATO_REPROVADO
   ↓
Sai do fluxo ativo
   ↓
Continua disponível em Reprovados
```

`POST /vagas/{vagaId}/candidatos/{candidatoId}/reprovar` recebe a etapa em que o candidato está (`etapaId`).

- A **etapa da reprovação é registrada** no candidato.
- O **nome da etapa é preservado**: fica guardado como estava no momento da reprovação, mesmo que a etapa seja renomeada depois.
- O candidato sai da listagem de ativos e não pode mais avançar. Ele continua na listagem de reprovados e pode ser consultado, editado e avaliado.
- **Não há motivo de reprovação** no modelo atual.
- **Não há e-mail de reprovação**: o sistema não envia comunicação a candidatos.
- Não existe operação para desfazer a reprovação.

Recusas, todas com `409`:

- o candidato não está mais na etapa informada;
- o candidato já foi reprovado;
- o candidato está na **etapa de proposta**, que não permite reprovação;
- a vaga **não está `ATUANDO`**.

## 12. Funil e Kanban

O funil e o Kanban usam **a mesma configuração de etapas** e a mesma lista de candidatos da vaga. São duas visualizações dos mesmos dados.

- O **funil** mostra a evolução dos candidatos pelas etapas.
- O **Kanban** mostra os candidatos em cada etapa.
- Os candidatos **reprovados ficam fora do fluxo ativo**: não aparecem entre os candidatos das etapas.
- Existe uma **visualização separada de reprovados**, com os reprovados de cada etapa.
- A **taxa de reprovação** usa o histórico, e não apenas o estado atual dos candidatos.

Os dados vêm da listagem de etapas, que devolve para cada etapa:

| Campo | Significado |
|---|---|
| `candidatesCount` | candidatos ativos na etapa agora |
| `reprovadosCount` | candidatos reprovados nessa etapa |
| `chegaramCount` | candidatos distintos que já chegaram à etapa |
| `taxaReprovacao` | percentual de reprovação da etapa |

## 13. Taxa de reprovação

```text
Reprovados na etapa
        ÷
Candidatos que chegaram na etapa
        × 100
```

Exemplo:

```text
10 candidatos chegaram
2 foram reprovados
Taxa = 20%
```

No exemplo, os outros 8 podem ter avançado para a etapa seguinte: eles continuam contando como candidatos que chegaram.

- A **chegada vem do histórico**, não da etapa em que o candidato está agora.
- **Criação e avanço contam como chegada**: o candidato chega à primeira etapa ao ser cadastrado e às demais ao avançar.
- **Reprovação não conta como chegada.**
- **Duplicidade não infla a métrica**: se uma reordenação de etapas levar o mesmo candidato de volta a uma etapa, ele conta uma única vez.
- **Sem candidatos chegando**, a taxa fica sem valor (`null`). Isso é diferente de 0%.
- **Com chegada e nenhuma reprovação**, a taxa é `0`.

O valor tem uma casa decimal e é calculado pelo backend.

## 14. Edição de candidato

`PUT /vagas/{vagaId}/candidatos/{candidatoId}` altera os dados do candidato:

| Campo | Regra |
|---|---|
| `name` | obrigatório; até 150 caracteres |
| `stack` | obrigatório; até 500 caracteres |
| `linkedin` | opcional; até 500 caracteres |
| `rating` | opcional; nota geral de 1 a 5 |
| `linkedinAbout`, `recruiterOpinion`, `technicalOpinion` | opcionais; até 5000 caracteres |

A edição **substitui** todos os campos editáveis: um campo opcional que não for enviado fica vazio no candidato.

A edição **não altera**:

- o id;
- a vaga;
- a etapa atual;
- o estado de reprovação (se foi reprovado, em qual etapa e o nome dela);
- a data de criação.

Se esses dados forem enviados, são ignorados.

A edição **não gera evento de histórico**. Ela funciona em qualquer status da vaga e também para candidato reprovado.

Na edição, a nota geral vai de 1 a 5 (ou sem nota); o valor 0, aceito no cadastro, é recusado.

## 15. Status da vaga

| Status | Significado |
|---|---|
| `ATUANDO` | em andamento; estado inicial |
| `PAUSADA` | suspensa temporariamente |
| `FECHADA` | um candidato chegou à etapa de proposta |
| `CANCELADA` | encerrada sem fechamento |

`PUT /vagas/{vagaId}/status` recebe o novo status e faz as transições manuais:

```text
ATUANDO  →  PAUSADA      (pausar)
PAUSADA  →  ATUANDO      (retomar)
ATUANDO  →  CANCELADA    (cancelar)
PAUSADA  →  CANCELADA    (cancelar)
```

- `CANCELADA` é **final**: não volta a `ATUANDO` nem a `PAUSADA`.
- `FECHADA` é **final** e não pode ser definida manualmente: só ocorre pelo avanço de um candidato à etapa de proposta.
- Qualquer outra transição, inclusive para o mesmo status, é recusada com `409`.
- Cada transição manual gera um evento `STATUS_ALTERADO`.

### O que cada status permite

| Operação | `ATUANDO` | `PAUSADA` | `FECHADA` | `CANCELADA` |
|---|---|---|---|---|
| Consultar vaga, etapas, candidatos, reprovados e avaliações | sim | sim | sim | sim |
| Cadastrar candidato | sim | não | não | não |
| Avançar candidato | sim | não | não | não |
| Reprovar candidato | sim | não | não | não |
| Editar dados do candidato | sim | sim | sim | sim |
| Avaliar candidato por etapa | sim | sim | sim | sim |

Depois de retomada (`PAUSADA → ATUANDO`), a vaga volta a aceitar cadastro, avanço e reprovação.

O gerenciamento de etapas (adicionar, renomear, reordenar, excluir) segue as próprias regras, descritas na seção 7, e **não é bloqueado pelo status da vaga**.

## 16. Histórico / auditoria

| Evento | Quando ocorre |
|---|---|
| `CANDIDATO_CRIADO` | um candidato é cadastrado; registra a etapa em que entrou |
| `CANDIDATO_AVANCADO` | um candidato avança; registra a etapa de origem e a de destino |
| `CANDIDATO_REPROVADO` | um candidato é reprovado; registra a etapa da reprovação |
| `STATUS_ALTERADO` | a vaga é pausada, retomada ou cancelada; registra o status anterior e o novo |

Todo evento guarda a vaga, o recruiter que fez a operação e a data. O evento é gravado junto com a operação: se uma falhar, a outra também não é registrada. Operações recusadas não geram evento.

Não geram histórico:

- a avaliação por etapa;
- a edição de candidato;
- a edição da vaga e as alterações de etapas;
- o fechamento automático da vaga, que fica representado pelo `CANDIDATO_AVANCADO` correspondente.

Atualmente **não existe endpoint de leitura do histórico**. Ele é usado internamente, para o cálculo da taxa de reprovação e para saber por quais etapas um candidato já passou.

## 17. Logout e sessão

- O JWT é **stateless**: o backend não guarda sessão.
- O backend **não tem endpoint de logout** e não invalida tokens.
- O **logout ocorre no frontend**: o token e os dados do usuário são removidos do `localStorage`, e as telas protegidas voltam a exigir login.
- O token **expira em 1 hora**. Uma chamada com token ausente, inválido ou expirado recebe `401`; nesse caso o frontend descarta o token e o recruiter precisa fazer login novamente.

Um token já emitido continua válido até expirar, mesmo depois do logout no frontend.

## 18. Fluxo completo de exemplo

```text
Recruiter cadastra a conta
        ↓
Recebe o código por e-mail e verifica o e-mail
        ↓
Faz login e recebe o token
        ↓
Cria a vaga "Desenvolvedor(a) Backend" com as etapas:
Triagem → Entrevista Técnica → Oferta (proposta)
        ↓
Cadastra a candidata Ana
        ↓
Ana entra em Triagem                       → histórico: CANDIDATO_CRIADO
        ↓
Recruiter avalia Ana em Triagem: 4 estrelas
        ↓
Avança Ana
        ↓
Ana está em Entrevista Técnica             → histórico: CANDIDATO_AVANCADO
        ↓
Recruiter avalia Ana em Entrevista Técnica: 5 estrelas
(a avaliação da Triagem continua 4)
        ↓
        ├── Avança Ana novamente
        │        ↓
        │   Ana chega a Oferta             → histórico: CANDIDATO_AVANCADO
        │        ↓
        │   Vaga passa a FECHADA
        │
        └── Ou reprova Ana em Entrevista Técnica
                 ↓
            Ana sai do fluxo ativo         → histórico: CANDIDATO_REPROVADO
                 ↓
            Ana aparece em Reprovados, com a etapa "Entrevista Técnica"
```

Suponha que, na mesma vaga, o recruiter também cadastrou Bruno e o reprovou na Triagem, antes do fechamento. As métricas por etapa ficariam assim, no caminho em que Ana chega à Oferta:

| Etapa | Chegaram | Reprovados | Taxa de reprovação |
|---|---|---|---|
| Triagem | 2 (Ana e Bruno) | 1 (Bruno) | 50% |
| Entrevista Técnica | 1 (Ana) | 0 | 0% |
| Oferta | 1 (Ana) | 0 | 0% |

Com a vaga fechada, não é mais possível cadastrar, avançar ou reprovar candidatos nela. A vaga, os candidatos, os reprovados e as avaliações continuam disponíveis para consulta, e ainda é possível editar os dados dos candidatos e registrar avaliações.

## 19. SSO corporativo — roadmap

> **Isto não está implementado.** Hoje a entrada no sistema é a descrita nas seções 2 e 3: cadastro próprio, verificação de e-mail e login com e-mail e senha.

Fluxo previsto para uma integração futura:

```text
Usuário
   ↓
Recruiter Visual
   ↓
Identity Provider
   ↓
OIDC/OAuth2
   ↓
Identidade corporativa
   ↓
Vínculo com recruiter interno
   ↓
Autorização do Recruiter Visual
```

Provedores possíveis, por serem compatíveis com OpenID Connect: Microsoft Entra ID, Okta ou Keycloak.

O provedor passaria a responder quem é o usuário. O Recruiter Visual continuaria decidindo o que esse usuário pode acessar, com a regra atual: cada recruiter acessa somente as próprias vagas. Os detalhes estão em [`ARQUITETURA.md`](ARQUITETURA.md).
