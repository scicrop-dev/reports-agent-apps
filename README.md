# InfiniteStack Agent Apps

Documentação oficial para criação de **Agent Apps** — aplicações Spring Boot que rodam como processos isolados dentro do ecossistema InfiniteStack (IS), acessíveis diretamente pelo frontend do IS sem qualquer configuração extra de rede ou autenticação.

---

## Conceito

Um Agent App é uma aplicação Spring Boot empacotada no formato `.ispz` que o IS instala, inicializa e proxeia automaticamente. Do ponto de vista do usuário final, o app aparece na seção **Agents → Apps** do IS como um painel web com iframe.

```
Usuário → Frontend IS → Nginx → Backend IS (proxy) → Agent App (processo isolado)
```

O host IS é responsável por:

- Validar o pacote `.ispz` e sua integridade (checksums SHA-256)
- Alocar uma porta dinâmica e iniciar o JAR como subprocesso
- Injetar credenciais de banco e tokens via variáveis de ambiente
- Autenticar e autorizar o usuário antes de fazer o proxy da requisição
- Exibir o app no frontend via iframe na rota `/api/plugins/{pluginId}/`

O Agent App **não** gerencia autenticação, não acessa o banco de dados do IS diretamente e não abre porta pública — tudo passa pelo host.

> **Design e UX:** este README cobre o contrato técnico (empacotamento, manifest, datasource, build). Para a identidade visual e o padrão de interface que tornam um app coeso com o IS, veja **[design.md](design.md)**.

---

## Estrutura do projeto

```
meu-agent-app/
├── pom.xml
├── mvnw / mvnw.cmd
├── plugin-manifest.json              ← metadados do plugin (versionado junto do fonte)
├── package-layout/
│   └── meu-agent-plugin/             ← layout exato do pacote .ispz
│       ├── plugin-manifest.json      ← cópia do manifest para o pacote
│       ├── checksums.sha256          ← gerado automaticamente no step2
│       ├── app/
│       │   ├── plugin.jar            ← preenchido no step2 (não commitar)
│       │   └── launch.properties     ← declarações de bootstrap do plugin
│       ├── config/
│       │   └── plugin-permissions.json
│       ├── web/
│       │   └── templates/
│       │       ├── isp-index.html    ← obrigatório
│       │       └── isp-status.html   ← obrigatório
│       └── docs/
│           └── README.txt
├── scripts/
│   ├── build-all.sh
│   ├── step1-build-jar.sh
│   ├── step2-stage-package.sh
│   └── step3-create-ispz.sh
├── out/                              ← arquivo .ispz gerado (gitignore)
└── src/
    └── main/
        ├── java/com/infinitestack/agent/app/
        │   ├── Application.java
        │   ├── ServletInitializer.java
        │   ├── config/
        │   │   ├── PluginDataSourceConfig.java
        │   │   └── PluginWebConfig.java
        │   └── controller/
        │       ├── PluginRuntimeController.java  ← obrigatório
        │       └── AgentPanelController.java      ← obrigatório
        └── resources/
            ├── application.properties
            ├── static/                            ← assets JS/CSS (opcional)
            └── templates/
                ├── isp-index.html
                └── isp-status.html
```

---

## `pom.xml`

```xml
<groupId>com.infinitestack</groupId>
<artifactId>meu-agent-app</artifactId>
<version>1.0.0</version>
<packaging>jar</packaging>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.2</version>
</parent>

<properties>
    <java.version>17</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!-- adicione o driver do banco do cliente conforme supported_destination_databases -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    <!-- para Oracle: -->
    <!-- <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc11</artifactId>
        <version>23.7.0.25.01</version>
    </dependency> -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-tomcat</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

---

## `plugin-manifest.json`

Arquivo raiz que o IS lê para identificar e validar o plugin. Deve existir tanto na raiz do projeto (para versionamento) quanto dentro do `package-layout`.

```json
{
  "plugin_id": "meu-agent-plugin",
  "name": "Meu Agent App",
  "version": "1.0.0",
  "description": "Descrição do que este app faz.",
  "entrypoint": "com.infinitestack.agent.app.Application",
  "base_path": "/api/plugins/meu-agent",
  "supported_destination_databases": ["postgresql"],
  "requires_host_authentication": true,
  "requires_host_authorization": true,
  "requires_host_datasource": true,
  "allows_own_user_store": false,
  "allows_own_datasource": false,
  "web": {
    "templates_path": "templates",
    "static_path": "static",
    "thymeleaf": true,
    "local_assets_only": true
  }
}
```

| Campo | Obrigatório | Descrição |
|-------|-------------|-----------|
| `plugin_id` | sim | Identificador único. Usado como chave na instalação. |
| `version` | sim | Semver. Usado no nome do arquivo `.ispz`. |
| `entrypoint` | sim | Classe `main` do Spring Boot. |
| `base_path` | sim | Prefixo de rota no qual o IS proxeia o app. Deve ser `/api/plugins/{slug}`. |
| `supported_destination_databases` | sim | Lista de bancos aceitos: `"postgresql"`, `"oracle"`, `"sqlserver"`. |
| `requires_host_authentication` | sim | **Sempre `true`**. O IS valida o JWT antes de proxear. |
| `requires_host_authorization` | sim | **Sempre `true`**. O IS aplica RBAC. |
| `requires_host_datasource` | sim | **Sempre `true`**. As credenciais do banco vêm do IS. |
| `allows_own_user_store` | sim | **Sempre `false`**. O app não mantém usuários próprios. |
| `allows_own_datasource` | sim | **Sempre `false`**. O app não define conexão própria. |
| `web.thymeleaf` | não | `true` para apps com UI Thymeleaf. |
| `web.local_assets_only` | não | `true` proíbe CDNs externos; todos os assets devem estar em `classpath:/static/`. |

---

## `config/plugin-permissions.json`

Define quais roles do IS podem acessar o app.

```json
{
  "allowed_roles": ["ADMIN", "SCICROP", "USER"],
  "inherits_host_authorization": true,
  "manages_own_roles": false,
  "manages_own_permissions": false
}
```

---

## `app/launch.properties`

Propriedades de bootstrap lidas pelo IS antes de iniciar o processo.

```properties
plugin.entrypoint=com.infinitestack.agent.app.Application
plugin.base-path=/api/plugins/meu-agent
plugin.requires-host-bridge=true
plugin.requires-host-authentication=true
plugin.requires-host-authorization=true
plugin.requires-host-datasource=true
```

---

## `application.properties`

O IS injeta as variáveis de ambiente listadas abaixo. Use `${VAR:default}` para ter fallback útil em desenvolvimento local.

```properties
spring.application.name=meu-agent-plugin
spring.thymeleaf.cache=false

# Injetado pelo IS — prefixo de todas as rotas do app
infinitestack.plugin.base-path=${IS_PLUGIN_BASE_PATH:/api/plugins/meu-agent}
infinitestack.plugin.assets-path=${infinitestack.plugin.base-path}/assets

# Diretório de dados persistentes do plugin (dentro do install path)
infinitestack.plugin.data-root=${IS_PLUGIN_INSTALL_PATH:./data}

# Banco de dados do cliente (conforme supported_destination_databases)
infinitestack.destination.db=${IS_DESTINATION_DB:postgresql}

# Credenciais PostgreSQL injetadas pelo IS
spring.datasource.url=${IS_DATASOURCE_URL:jdbc:postgresql://localhost:5432/postgres}
spring.datasource.username=${IS_DATASOURCE_USERNAME:postgres}
spring.datasource.password=${IS_DATASOURCE_PASSWORD:}

# Credenciais Oracle (quando supported_destination_databases inclui oracle)
oracle.datasource.url=${IS_ORACLE_URL:}
oracle.datasource.username=${IS_ORACLE_USERNAME:}
oracle.datasource.password=${IS_ORACLE_PASSWORD:}

# Comunicação de volta ao host IS (para chamar APIs internas)
infinitestack.host.base-url=${IS_HOST_BASE_URL:http://127.0.0.1:8081}
infinitestack.plugin.internal-token=${IS_PLUGIN_INTERNAL_TOKEN:}
infinitestack.plugin.runtime-id=${IS_PLUGIN_ID:meu-agent-plugin}

# LLM ativo configurado no IS
infinitestack.active-llm.provider=${IS_ACTIVE_LLM_PROVIDER:}
infinitestack.active-llm.model=${IS_ACTIVE_LLM_MODEL:o4-mini}
infinitestack.active-llm.credential-key=${IS_ACTIVE_LLM_CREDENTIAL_KEY:}
```

### Variáveis de ambiente injetadas pelo IS

| Variável | Descrição |
|----------|-----------|
| `IS_PLUGIN_BASE_PATH` | Prefixo de rota, ex.: `/api/plugins/meu-agent` |
| `IS_PLUGIN_INSTALL_PATH` | Diretório de instalação do plugin no servidor |
| `IS_PLUGIN_ID` | `plugin_id` do manifest |
| `IS_PLUGIN_INTERNAL_TOKEN` | Token para o app chamar APIs internas do IS |
| `IS_DESTINATION_DB` | Tipo de banco: `postgresql`, `oracle`, `sqlserver` |
| `IS_DATASOURCE_URL` | JDBC URL do banco de destino |
| `IS_DATASOURCE_USERNAME` | Usuário do banco |
| `IS_DATASOURCE_PASSWORD` | Senha do banco |
| `IS_HOST_BASE_URL` | URL interna do backend IS |
| `IS_ACTIVE_LLM_PROVIDER` | Provedor LLM ativo (ex.: `openai`) |
| `IS_ACTIVE_LLM_MODEL` | Modelo LLM ativo (ex.: `o4-mini`) |
| `IS_ACTIVE_LLM_CREDENTIAL_KEY` | Chave de API do LLM |

---

## Classes Java obrigatórias

### `Application.java`

```java
package com.infinitestack.agent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### `ServletInitializer.java`

```java
package com.infinitestack.agent.app;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

public class ServletInitializer extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(Application.class);
    }
}
```

### `PluginRuntimeController.java` — obrigatório

O IS chama `GET {base_path}/api/runtime-health` para verificar se o processo está vivo.

```java
package com.infinitestack.agent.app.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/meu-agent}/api")
public class PluginRuntimeController {

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }
}
```

### `AgentPanelController.java` — obrigatório

Expõe a UI do app. O IS abre `GET {base_path}/` no iframe.

```java
package com.infinitestack.agent.app.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/meu-agent}")
public class AgentPanelController {

    @Value("${infinitestack.plugin.base-path:/api/plugins/meu-agent}")
    private String pluginBasePath;

    @Value("${infinitestack.plugin.assets-path:${infinitestack.plugin.base-path}/assets}")
    private String pluginAssetsPath;

    @GetMapping
    public String index(Model model) {
        populateModel(model);
        return "isp-index";
    }

    @GetMapping("/status")
    public String status(Model model, HttpServletRequest request) {
        populateModel(model);
        model.addAttribute("pluginStatus", "healthy");
        model.addAttribute("pluginPath", request.getRequestURI());
        return "isp-status";
    }

    private void populateModel(Model model) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
    }
}
```

### `PluginDataSourceConfig.java`

Constrói o `DataSource` a partir das variáveis de ambiente injetadas. Adapte os casos conforme os bancos que o app suporta.

```java
package com.infinitestack.agent.app.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class PluginDataSourceConfig {

    @Bean
    @Primary
    public DataSource pluginDataSource(
        @Value("${infinitestack.destination.db:postgresql}") String destinationDb,
        @Value("${spring.datasource.url:}") String pgUrl,
        @Value("${spring.datasource.username:}") String pgUser,
        @Value("${spring.datasource.password:}") String pgPass,
        @Value("${oracle.datasource.url:}") String oraUrl,
        @Value("${oracle.datasource.username:}") String oraUser,
        @Value("${oracle.datasource.password:}") String oraPass
    ) {
        DriverManagerDataSource ds = new DriverManagerDataSource();

        switch (destinationDb.toLowerCase()) {
            case "postgresql" -> {
                ds.setDriverClassName("org.postgresql.Driver");
                ds.setUrl(pgUrl);
                ds.setUsername(pgUser);
                ds.setPassword(pgPass);
            }
            case "oracle" -> {
                ds.setDriverClassName("oracle.jdbc.OracleDriver");
                ds.setUrl(oraUrl);
                ds.setUsername(oraUser);
                ds.setPassword(oraPass);
            }
            default -> throw new IllegalStateException("Unsupported destination db: " + destinationDb);
        }

        return ds;
    }
}
```

### `PluginWebConfig.java`

Mapeia os assets estáticos no caminho correto do plugin.

```java
package com.infinitestack.agent.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PluginWebConfig implements WebMvcConfigurer {

    @Value("${infinitestack.plugin.base-path:/api/plugins/meu-agent}")
    private String pluginBasePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(pluginBasePath + "/assets/**")
                .addResourceLocations("classpath:/static/");
    }
}
```

---

## Banco de dados — nomenclatura obrigatória

Duas regras, e as duas são obrigatórias.

### 1. Tudo vive no schema `apps`

**Todo Agent App cria suas tabelas no schema `apps`**, nunca em `public`. O schema é criado pelo
próprio app, na primeira instrução do seu DDL:

```sql
CREATE SCHEMA IF NOT EXISTS apps;
```

E toda instrução seguinte é **qualificada explicitamente** — `apps.minha_tabela`, não
`minha_tabela` confiando no `search_path`.

> **Por que qualificar em vez de configurar o `search_path` da conexão.** Ficaria mais limpo, mas
> não é determinístico: o Postgres aceita um `search_path` apontando para um schema que ainda não
> existe e silenciosamente cai para o próximo. Entre o pool abrir a primeira conexão e o schema ser
> criado, um `CREATE TABLE` cairia em `public` — e nada reportaria isso.

O nome do schema é configurável (`infinitestack.app.schema`, default `apps`) para o caso de um DBA
já ter provisionado outro. O app valida que o valor é um identificador simples antes de colocá-lo
no DDL: é configuração, mas ainda é SQL.

### 2. Toda tabela começa com o `plugin_id`

Dentro de `apps`, **cada tabela é prefixada com o `plugin_id`** convertido para snake_case:

```
apps.<plugin_id em snake_case>_<nome da tabela>
```

| `plugin_id` | Tabela | Nome obrigatório |
|---|---|---|
| `chatbot-workflow-manager` | conversas | `apps.chatbot_workflow_manager_conversation` |
| `chatbot-whatsapp-manager` | mensagens | `apps.chatbot_whatsapp_manager_message` |
| `reports` | relatórios | `apps.reports_report` |

O mesmo vale para **índices**, com o prefixo depois do `idx_`:

```sql
CREATE INDEX IF NOT EXISTS idx_chatbot_whatsapp_manager_message_jid
    ON apps.chatbot_whatsapp_manager_message (jid, happened_at);
```

### Por que as regras existem

Um Agent App **não tem banco próprio**: ele grava no banco de destino do cliente, o mesmo onde
estão as tabelas de negócio dele e as de todos os outros apps instalados.

O **schema separado** faz de "tudo que os apps criaram" uma coisa só, endereçável: dá para conceder
ou revogar de uma vez, aparece separado num dump, e ninguém confunde com tabela de negócio. Sem ele,
as tabelas dos apps ficam espalhadas no meio do que o cliente possui.

O **prefixo por app** resolve o que o schema sozinho não resolve — de qual app é cada tabela. Sem
ele, três coisas ruins acontecem, e nenhuma dá erro na hora:

1. **Colisão silenciosa.** Dois apps que criem `conversation` com `CREATE TABLE IF NOT EXISTS`
   passam a compartilhar a mesma tabela sem nenhum aviso — o segundo simplesmente encontra a tabela
   já existente, com o schema errado, e só falha quando insere.
2. **Colisão com o cliente.** `message`, `event`, `document` são nomes que uma empresa
   provavelmente já usa. O app não pode reivindicá-los.
3. **Ninguém sabe de quem é.** Ao desinstalar um app, ou ao investigar o que ocupa espaço no banco,
   o prefixo é a única coisa que diz qual app é dono de qual tabela.

O nome fica longo, e isso é aceitável: essas tabelas são lidas por código e por quem opera o banco,
não digitadas o tempo todo.

> **Atenção ao nome das variáveis.** O host injeta `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`
> e `SPRING_DATASOURCE_PASSWORD` — não `IS_DATASOURCE_*`. Elas chegam como variáveis de ambiente e,
> por terem precedência sobre o `application.properties`, sobrescrevem `spring.datasource.*`
> diretamente. O `${IS_DATASOURCE_URL:...}` que aparece nos exemplos serve apenas de default para
> desenvolvimento local.

---

## Templates Thymeleaf

**Regra:** todos os templates devem ter nome começando com `isp-`. Qualquer template sem esse prefixo não é carregado pelo IS.

Templates ficam em `src/main/resources/templates/`.

### `isp-index.html` — obrigatório

Ponto de entrada do app. O IS abre esta página no iframe.

```html
<!DOCTYPE html>
<html lang="pt-BR" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Meu Agent App</title>
    <link rel="stylesheet" th:href="@{${pluginAssetsPath} + '/css/app.css'}">
</head>
<body>
    <h1>Meu Agent App</h1>
    <script th:src="@{${pluginAssetsPath} + '/js/app.js'}"></script>
</body>
</html>
```

### `isp-status.html` — obrigatório

```html
<!DOCTYPE html>
<html lang="pt-BR" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>Status</title>
</head>
<body>
    <p>Status: <span th:text="${pluginStatus}">unknown</span></p>
    <p>Path: <span th:text="${pluginPath}">-</span></p>
</body>
</html>
```

**Variáveis disponíveis nos templates** (injetadas por `AgentPanelController`):

| Variável | Valor |
|----------|-------|
| `pluginBasePath` | `/api/plugins/meu-agent` |
| `pluginAssetsPath` | `/api/plugins/meu-agent/assets` |

Para referenciar assets, use sempre `th:href` / `th:src` com `${pluginAssetsPath}` — nunca caminhos absolutos.

---

## Scripts de build

### Pré-requisito

Java 17+ e Maven wrapper (`mvnw`) no projeto. Os scripts devem estar em `scripts/`.

### `scripts/step1-build-jar.sh`

Compila o projeto e copia o JAR para `build/plugin.jar`.

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PLUGIN_ROOT/build"
mkdir -p "$BUILD_DIR"
"$PLUGIN_ROOT/mvnw" -q -DskipTests clean package
JAR_PATH="$(ls "$PLUGIN_ROOT/target"/*.jar | grep -v 'original-' | head -n 1)"
cp "$JAR_PATH" "$BUILD_DIR/plugin.jar"
echo "[step1] Jar pronto em $BUILD_DIR/plugin.jar"
```

### `scripts/step2-stage-package.sh`

Monta o layout do pacote em `build/staging/` e gera `checksums.sha256`.

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ID="meu-agent-plugin"          # ← ajuste para o plugin_id do manifest
BUILD_DIR="$PLUGIN_ROOT/build"
STAGING_DIR="$BUILD_DIR/staging/$PLUGIN_ID"
LAYOUT_ROOT="$PLUGIN_ROOT/package-layout/$PLUGIN_ID"

rm -rf "$BUILD_DIR/staging"
mkdir -p "$BUILD_DIR/staging"
cp -R "$LAYOUT_ROOT" "$STAGING_DIR"
cp "$BUILD_DIR/plugin.jar" "$STAGING_DIR/app/plugin.jar"

# checksums cobrindo todos os arquivos que o IS valida
(
  cd "$STAGING_DIR"
  sha256sum \
    app/plugin.jar \
    plugin-manifest.json \
    app/launch.properties \
    config/plugin-permissions.json \
    web/templates/isp-index.html \
    web/templates/isp-status.html \
    > checksums.sha256
)
echo "[step2] Staging pronto em $STAGING_DIR"
```

> Se o app tiver templates adicionais, inclua-os no `sha256sum`.

### `scripts/step3-create-ispz.sh`

Compacta o staging em `.zip` e renomeia para `.ispz`.

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ID="meu-agent-plugin"          # ← ajuste
BUILD_DIR="$PLUGIN_ROOT/build"
STAGING_ROOT="$BUILD_DIR/staging"
OUTPUT_DIR="$PLUGIN_ROOT/out"
MANIFEST_PATH="$STAGING_ROOT/$PLUGIN_ID/plugin-manifest.json"

VERSION="$(grep '"version"' "$MANIFEST_PATH" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
mkdir -p "$OUTPUT_DIR"
ISPZ_PATH="$OUTPUT_DIR/$PLUGIN_ID-$VERSION.ispz"
rm -f "$ISPZ_PATH"

(cd "$STAGING_ROOT" && zip -r "$OUTPUT_DIR/$PLUGIN_ID-$VERSION.zip" "$PLUGIN_ID" >/dev/null)
mv "$OUTPUT_DIR/$PLUGIN_ID-$VERSION.zip" "$ISPZ_PATH"
echo "[step3] Pacote criado em $ISPZ_PATH"
```

### `scripts/build-all.sh`

Executa os três passos em sequência.

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/step1-build-jar.sh"
"$SCRIPT_DIR/step2-stage-package.sh"
"$SCRIPT_DIR/step3-create-ispz.sh"
echo "[build-all] Concluído."
```

Executar:

```bash
chmod +x scripts/*.sh
./scripts/build-all.sh
# gera: out/meu-agent-plugin-1.0.0.ispz
```

---

## Formato `.ispz`

Um arquivo `.ispz` é um ZIP renomeado. Internamente:

```
meu-agent-plugin-1.0.0.ispz
└── meu-agent-plugin/
    ├── plugin-manifest.json
    ├── checksums.sha256
    ├── app/
    │   ├── plugin.jar
    │   └── launch.properties
    ├── config/
    │   └── plugin-permissions.json
    ├── web/
    │   └── templates/
    │       ├── isp-index.html
    │       └── isp-status.html
    └── docs/
        └── README.txt
```

O IS valida `checksums.sha256` na instalação — qualquer alteração manual nos arquivos empacotados causará rejeição.

---

## Instalação e gerenciamento via CLI do IS

O IS expõe comandos no terminal CLI (seção **Leverage → Chatbot** ou terminal admin).

### Instalar

```
plugininstall -path /caminho/absoluto/para/meu-agent-plugin-1.0.0.ispz
```

O IS irá:
1. Extrair e validar checksums
2. Copiar para o diretório de plugins
3. Alocar porta dinâmica
4. Iniciar o JAR com `java -jar plugin.jar --server.port=XXXX` + variáveis de ambiente
5. Aguardar `/api/runtime-health` responder `"ok"`
6. Registrar o app no frontend

### Listar instalados

```
lsplugin
```

Exibe `plugin_id`, versão, status do processo e porta.

### Desinstalar

```
uninstallplugin -id meu-agent-plugin@1.0.0
```

Para o processo e remove os arquivos.

---

## Roteamento no IS

Depois de instalado, todas as requisições para `/api/plugins/meu-agent/**` são proxeadas pelo `PluginRuntimeProxyController` do IS para `http://localhost:{porta_dinamica}/api/plugins/meu-agent/**`. O frontend acessa o app via esse proxy — sem CORS, sem configuração adicional.

```
GET /api/plugins/meu-agent/          → isp-index.html
GET /api/plugins/meu-agent/status    → isp-status.html
GET /api/plugins/meu-agent/api/runtime-health → "ok"
GET /api/plugins/meu-agent/assets/** → classpath:/static/**
```

---

## Exemplo de novo endpoint

```java
@RestController
@RequestMapping("${infinitestack.plugin.base-path}/api/relatorio")
public class RelatorioController {

    private final JdbcTemplate jdbc;

    public RelatorioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return jdbc.queryForList("SELECT * FROM minha_tabela LIMIT 100");
    }
}
```

O `JdbcTemplate` é autoconfigurado a partir do `DataSource` criado em `PluginDataSourceConfig` com as credenciais injetadas pelo IS.

---

## `.gitignore` recomendado

```
target/
build/staging/
build/plugin.jar
out/
*.ispz
```

Commite o `package-layout/` completo (exceto `plugin.jar` e `checksums.sha256`, que são gerados automaticamente) e o `plugin-manifest.json` raiz.

---

## Link de volta ao IS

Todo Agent App **deve** exibir um botão ou link que leve o usuário de volta à seção **Agents → Apps** do IS. O app roda dentro de um `<iframe>`, portanto a navegação deve usar `window.parent`:

```html
<a href="#" onclick="window.parent.location.href='/agents/apps'; return false;">
  ← Voltar ao IS
</a>
```

Ou como botão com `target="_parent"`:

```html
<a href="/agents/apps" target="_parent">← Voltar ao IS</a>
```

**Posicionamento obrigatório:** no `<header>` principal do app, visível em todas as telas.

---

## Checklist antes de empacotar

- [ ] `plugin_id` em `plugin-manifest.json` é único e sem espaços
- [ ] **O DDL cria `apps` com `CREATE SCHEMA IF NOT EXISTS` e qualifica todas as instruções**
- [ ] **Toda tabela e índice criados pelo app começam com o `plugin_id` em snake_case**
- [ ] `base_path` segue o padrão `/api/plugins/{slug}`
- [ ] `PluginRuntimeController` expõe `GET {base_path}/api/runtime-health` → `"ok"`
- [ ] `AgentPanelController` expõe `GET {base_path}/` retornando `"isp-index"`
- [ ] Todos os templates começam com `isp-`
- [ ] `checksums.sha256` lista todos os templates e arquivos críticos
- [ ] `allows_own_user_store: false` e `allows_own_datasource: false` no manifest
- [ ] `requires_host_authentication: true` e `requires_host_authorization: true`
- [ ] Assets JS/CSS em `classpath:/static/` (sem CDN externo se `local_assets_only: true`)
- [ ] UI segue a identidade visual do IS — ver checklist de design em **[design.md](design.md)**
- [ ] Header do `isp-index.html` contém link de volta a `/agents/apps`
- [ ] Build limpo: `./scripts/build-all.sh` sem erros
