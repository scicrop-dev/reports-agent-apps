# CLAUDE.md

As instruções do projeto ficam em [AGENTS.md](AGENTS.md), compartilhadas com outros agentes. Elas são importadas abaixo.

@AGENTS.md

## Específico do Claude Code

- Antes de criar tabela, controller ou template, leia a seção correspondente do
  [README.md](README.md). Antes de mexer em UI, leia o [design.md](design.md).
- Para validar uma mudança de UI no app rodando, suba o app localmente com
  `mvn spring-boot:run` dentro do diretório do app. Ele usa os defaults `${VAR:default}` do
  `application.properties`, que exigem um Postgres local. Abra `http://localhost:8080{base_path}/`.
- Não faça commit nem push sem pedido explícito. Ao preparar um PR, faça o bump de versão e gere o
  `.ispz` da versão nova antes de abri-lo; todo build de `.ispz` para entregar também leva bump
  (ver "Versionamento e release" no AGENTS.md).
