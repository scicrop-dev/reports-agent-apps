# AGENTS.md

Instruções para agentes de código (Claude Code, Codex, Cursor etc.) que trabalham neste repositório.
O contrato técnico completo está em [README.md](README.md) e a identidade visual em
[design.md](design.md). Este arquivo resume o que **não pode ser violado** e registra as convenções
que o código já segue, mesmo quando a documentação diverge.

## O que é este repo

Monorepo de **Agent Apps** do InfiniteStack (IS): cada diretório de primeiro nível é uma aplicação
Spring Boot 3.3 / Java 17 independente, empacotada como `.ispz`, instalada pelo IS como subprocesso
e exibida em um iframe em **Agents → Apps**. O host cuida de autenticação, RBAC, porta e credenciais
de banco. O app não cuida de nada disso.

| Diretório | `plugin_id` | Pacote Java |
|---|---|---|
| `reports` | `reports` | `com.infinitestack.reports` |

Este repositório (`scicrop-dev/reports-agent-apps`, em `apps/reports-agent-apps/` no workspace) é o
do **REPORTS**. O produto está descrito em [docs/MASTER.md](docs/MASTER.md), a arquitetura em
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) e a ordem de implementação em
[docs/ROADMAP.md](docs/ROADMAP.md). Leia os três antes de mexer no app. O app fica em `reports/`; o
mapa do código e as decisões de implementação estão na seção 47 do ARCHITECTURE.md.
Hoje `reports` é o único app do repo (o `recomendador-manejo`, que servia só de exemplo do contrato,
saiu em 2026-10-26; o original continua em `infinitestack-agent-apps`). Agent apps novos não entram
aqui: cada um tem repo próprio em `apps/<nome>/`.

Não há build agregado na raiz. Cada app tem seu próprio `pom.xml`, `scripts/` e `.gitignore`.
Trabalhe sempre dentro do diretório do app afetado e não altere outros apps de carona.

## Build e testes

```bash
cd <app>
mvn -q test                  # testes (nem todo app tem; ver src/test)
./scripts/build-all.sh       # step1 jar → step2 staging + checksums → step3 out/<plugin_id>-<versão>.ispz
```

- Os scripts usam `mvn` do sistema. Não há `mvnw` commitado, ao contrário do que o README sugere.
- Rode `build-all.sh` antes de declarar uma mudança pronta. É o critério de "build limpo" do checklist.
- `target/`, `build/`, `out/` e `*.ispz` são artefatos e nunca entram em commit.

### REPORTS (`reports/`)

- `mvn -q test` roda tudo sem banco. O teste de ponta a ponta (`ReportsEndToEndDbTest`) só roda com
  um Postgres descartável:
  `REPORTS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/<db> REPORTS_TEST_DB_USER=... mvn test`.
  Ele apaga e recria o schema `reports_test` e a tabela `gold.reports_test_area` nesse banco.
- **UI local:** `mvn spring-boot:test-run` sobe o app com os Blocks de teste (`LocalDevApplication`,
  só em `src/test`). Passe o banco por `IS_DATASOURCE_URL`/`IS_DATASOURCE_USERNAME`/`IS_DATASOURCE_PASSWORD`
  e `REPORTS_LOCAL_ROLE=ADMIN` para ver a tela de liberação. Sem o token do host, o catálogo vem do
  `information_schema` (schemas bronze/silver/gold do banco).
- **Blocks de produto** ficam em `blocks/<fonte>/` (hoje `blocks/car`, `blocks/brgeo`, `blocks/sathub`, `blocks/skillslab`), um por
  arquivo, com o contrato em [docs/BLOCKS.md](docs/BLOCKS.md) (regras na seção 1: dados só via `BlockQueries`,
  pool somente leitura, e `docs()` com a documentação que o editor mostra).
  Os Blocks de `src/test/.../support/TestBlocks` são só de teste e não entram no `.ispz`.
- Integrações com outros apps (Satellite Hub, scripts Python do Skills Lab): [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).
- **Skills Lab:** o Block `skillslab-python` roda um script Python do Skills Lab por um workflow do
  projeto, pela porta interna `/api/extensions/internal/workflows` do back (infinitestack-back#1280,
  `HostWorkflowClient`). Cada workflow precisa estar liberado para o app em
  `/opt/infinitestack/etc/reports-workflows.json` na instância; fora do IS (sem token) o Block falha.
  O script devolve o resultado imprimindo `IS_REPORTS_RESULT <json>` (INTEGRATIONS.md §2).
- **Rede de saída:** o Block `sathub-latest-image` lê Sentinel-2 na hora e precisa de HTTPS do host
  para `earth-search.aws.element84.com` (STAC; `reports.sentinel.stac-url` / `REPORTS_SENTINEL_STAC_URL`)
  e `sentinel-cogs.s3.us-west-2.amazonaws.com` (COGs). O resto do app não sai da rede. O teste real
  (`SatelliteHubLatestImageNetworkTest`) roda com `SENTINEL_NETWORK_TEST=1` + `CAR_TEST_DB_*`.
- **Testes contra o banco de dev** (somente leitura, consultas por imóvel): `CarBlocksDevDbTest`,
  `CarPropertyMapDevDbTest`, `BrGeoBlocksDevDbTest` e `SatelliteHubDevDbTest` rodam com
  `CAR_TEST_DB_URL`/`CAR_TEST_DB_USER`/`CAR_TEST_DB_PASSWORD`.
  Nunca grave credenciais em arquivo. Sem as variáveis, são pulados.
- **App local com dados reais:** `reports.block-datasource.url/username/password` apontam o pool dos Blocks
  para outro banco (ex.: dev, só leitura), enquanto as tabelas do app ficam no Postgres local de
  `IS_DATASOURCE_*`. Passe a senha por `SPRING_APPLICATION_JSON`, não pela linha de comando.
- A UI segue a regra de idioma (seção "Idioma", abaixo): textos de interface em inglês no código-fonte.
- **Relatório pronto novo:** `reports/src/main/resources/ready-made/<id>.json` (+ `<id>.html`/`<id>.css`),
  formato e regras em [docs/READY-MADE-REPORTS.md](docs/READY-MADE-REPORTS.md). `mvn -q test` valida
  todos contra os contratos dos Blocks e gera HTML/PDF em `reports/target/ready-made/` para conferir.
  O conteúdo do relatório (template, nome, descrição) fica no idioma do relatório, não em inglês.
- Template novo além de `isp-index`/`isp-status` não existe: a UI é uma página só, com JS em
  `static/app/*.js` e CodeMirror 5.65.16 vendorado em `static/vendor/codemirror/`. O editor visual de Flow
  é `static/app/flow-graph.js` (DOM + SVG, sem biblioteca; ARCHITECTURE §47.7). As alturas das linhas
  dos nós são constantes no JS e no CSS `.fg-*`: mude as duas juntas.

## Regras invioláveis (contrato com o host)

1. **Manifest:** `requires_host_authentication`, `requires_host_authorization` e
   `requires_host_datasource` são `true`. `allows_own_user_store` e `allows_own_datasource` são `false`.
2. **`base_path` = `/api/plugins/{plugin_id}`**, e todo controller mapeia sob
   `${infinitestack.plugin.base-path}`.
3. **Endpoints obrigatórios:** `GET {base_path}/api/runtime-health` → `"ok"` (`PluginRuntimeController`)
   e `GET {base_path}/` → template `isp-index` (`AgentPanelController`).
4. **Templates começam com `isp-`.** `isp-index.html` e `isp-status.html` são obrigatórios.
5. **Sem rede externa no front.** `web.local_assets_only: true` proíbe CDN, Google Fonts e qualquer
   origem de terceiros. CSS/JS do app ficam inline no template, e libs de terceiros ficam vendoradas
   em `src/main/resources/static/` e são referenciadas via `th:src="@{${pluginAssetsPath} + '/...'}"`.
6. **Chamadas do front** usam `const BASE = /*[[${pluginBasePath}]]*/ '...'` e `fetch(BASE + '/api/...')`.
   Não use URL absoluta com host nem `window.top`.
7. **Credenciais vêm do host.** Nunca hardcode URL, usuário ou senha de banco, nem chaves de LLM.
   Use `${VAR:default}` em `application.properties`. Em produção o host injeta
   `SPRING_DATASOURCE_*` (não `IS_DATASOURCE_*`, que é só fallback de dev local).

## Programação Java
**KISS.** Cada implementação resolve um único problema. Mas sempre que possível para entendimento e reaproveiramento use Design Patterns GoF
**Fluxo obrigatório back→front:** persistência → DTO → service → controller → curl de teste → frontend. Nunca ao contrário.
**Nomenclatura:** camelCase inteligível em métodos e funções. Comentários explicam *o quê* e *por quê*.
**Não fazer:** múltiplas features simultâneas, overengineering, abstrações prematuras, frameworks desnecessários.

### Idioma — inglês em todo código

Vale para os quatro repositórios, sem exceção.

| Onde | Idioma |
|------|--------|
| Nomes de variáveis, classes, métodos, arquivos | **inglês** |
| Comentários e javadoc/JSDoc dentro do código | **inglês** |
| Textos de UI no código-fonte | **inglês** |

Fora do código-fonte, issues, PRs, documentação `.md` do repositório e conversa com o time — não há restrição de idioma.


## Banco de dados

- Toda tabela vive no schema **`apps`**, criado pelo próprio app com `CREATE SCHEMA IF NOT EXISTS`.
- **Todo SQL é qualificado** (`apps.x`). Não dependa de `search_path`.
- **Toda tabela e índice levam o `plugin_id` em snake_case como prefixo:**
  `apps.chatbot_workflow_manager_conversation`, `idx_chatbot_workflow_manager_conversation_<cols>`.
- O nome do schema vem de `infinitestack.app.schema` (default `apps`) e passa pelo helper
  `repository/AppSchema.java`, que valida o identificador antes de entrar no DDL. Em app novo, copie
  esse helper de um app existente (ex.: `barry-gestor-produtor`) em vez de reinventar.
- `supported_destination_databases` usa **`"postgres"`**, que é o valor que o host aceita (ver commit
  `9056a7f`). O README ainda mostra `"postgresql"` nos exemplos. Siga o código.

## Templates: fonte da verdade e sincronização

- **Fonte da verdade:** `src/main/resources/templates/`. O `step2-stage-package.sh` copia
  `isp-index.html` e `isp-status.html` para `package-layout/<plugin_id>/web/templates/`.
- Edite em `src/main/resources/templates/` e **commite as duas cópias**, mantendo-as idênticas. Se
  editar só uma, rode o step2 (ou copie) antes do commit.
- Template novo além dos dois obrigatórios: adicione-o ao `cp` e ao `sha256sum` do step2.
- `checksums.sha256` e `app/plugin.jar` dentro de `package-layout/` são gerados e não devem ser editados à mão.

## UI

Siga [design.md](design.md), com Thymeleaf: tema escuro com os tokens de `:root`, system font, header +
sidebar 320px + conteúdo, `body` travado em `100vh` com scroll só nos painéis, botão primário verde
(`--accent`), spinner CSS e bolinha de status para estados longos, texto em pt-BR. Para começar uma
tela nova, parta do próprio `reports/` ou, em `infinitestack-agent-apps`, de `recomendador-manejo`
(formulário → resultado) ou `satellite-hub` (mapa/visualização). O design.md chama este de `satellite-hub-viewer`.

**Link de volta ao IS:** o README exige um link no `<header>` de `isp-index.html` para
`/agents/apps` com `target="_parent"` (ou `window.parent.location`). Essa regra é mais nova que a §1
do design.md ("sem links voltar") e prevalece sobre ela. O `reports` cumpre a regra (`.btn-back` no header).

## Versionamento e release

Uma mudança que vai para o IS gera nova versão (semver) **nos três lugares, juntos**:

1. `<app>/pom.xml` (`<version>` do projeto, não do parent)
2. `<app>/plugin-manifest.json`
3. `<app>/package-layout/<plugin_id>/plugin-manifest.json`

O nome do `.ispz` sai do manifest do package-layout. Algumas versões estão dessincronizadas hoje
(`barry-gestor-produtor`: pom 1.0.0 × manifest 1.11.2; `chatbot-whatsapp-manager`: root 1.2.0 ×
layout 1.1.0). Não corrija isso de carona. Só alinhe se for pedido.

O histórico do repo separa a feature do bump de versão:
`feat(<app>): ...` em um PR e `chore(<app>): versão X.Y.Z` em outro.

## Git

- Mensagens no estilo Conventional Commits em **pt-BR**, com escopo = app:
  `feat(chatbot-workflow): ...`, `fix(barry): ...`, `perf(satellite-hub): ...`, `chore(...): versão ...`.
- Trabalhe em branch (`feat/<app>-<assunto>`, `fix/...`, `chore/<app>-<versão>`) e abra PR contra `main`.
- Um PR toca um app só, salvo mudança deliberada de documentação compartilhada.

## Checklist antes de dar a tarefa por concluída

- [ ] Mudança restrita ao app em questão
- [ ] Tabelas/índices novos em `apps.` com prefixo do `plugin_id`
- [ ] Nenhum CDN/URL externa adicionada ao front
- [ ] Templates em `src/main/resources/templates/` e `package-layout/` idênticos
- [ ] `mvn -q test` e `./scripts/build-all.sh` passam no app
- [ ] Versão bumpada nos três lugares, se for release
