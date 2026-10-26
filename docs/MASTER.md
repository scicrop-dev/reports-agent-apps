# REPORTS — MASTER

## 1. Visão Geral

O **REPORTS** é um aplicativo do InfiniteStack responsável pela criação, execução e geração de relatórios dinâmicos a partir dos dados disponíveis na plataforma.

O REPORTS não deve ser tratado apenas como um gerador de PDF.

Seu principal objetivo é permitir que usuários:

1. selecionem dados disponíveis no InfiniteStack;
2. executem blocos de processamento;
3. conectem os resultados desses blocos;
4. utilizem os outputs em templates;
5. criem relatórios HTML;
6. visualizem o resultado;
7. gerem documentos finais, inicialmente PDF.

O conceito central é:

```text
Dados
  ↓
Blocks
  ↓
Flow
  ↓
Outputs
  ↓
Template
  ↓
HTML
  ↓
PDF
```

---

# 2. Objetivos

## 2.1 Objetivo principal

Permitir que usuários construam relatórios personalizados sem que seja necessário desenvolver código específico para cada novo relatório.

## 2.2 Objetivos secundários

O sistema deve:

- reutilizar dados já existentes no InfiniteStack;
- reutilizar funcionalidades geográficas do GEOLIB;
- permitir criação de novos blocos;
- permitir composição de blocos;
- permitir reutilização de flows;
- permitir criação de templates HTML;
- disponibilizar preview;
- gerar PDF;
- permitir versionamento de relatórios;
- manter execução reproduzível;
- permitir auditoria das execuções.

---

# 3. Conceitos Fundamentais

O REPORTS possui cinco conceitos principais.

## 3.1 Block

Um Block é uma unidade funcional que recebe entradas e produz saídas.

Exemplo:

```text
GET_MUNICIPALITY
```

Inputs:

```text
latitude
longitude
```

Outputs:

```text
state
municipality
ibgeCode
```

Um Block deve possuir contrato explícito de entrada e saída.

---

# 3.2 Flow

Flow é uma composição de Blocks.

Exemplo:

```text
INPUT propertyId
        ↓
GET_PROPERTY
        ↓
GET_GEOMETRY
        ↓
GET_CENTROID
        ↓
GET_MUNICIPALITY
```

O Flow representa como os dados são obtidos e transformados.

---

# 3.3 Template

Template é a apresentação final dos dados.

Inicialmente será composto por:

- HTML;
- CSS;
- variáveis;
- helpers controlados.

Exemplo:

```html
<h1>{{property.name}}</h1>

<p>
    Município: {{municipality.name}}
</p>

<p>
    Estado: {{municipality.state}}
</p>
```

---

# 3.4 Report

Report é a definição completa de um relatório.

Um Report associa:

```text
Input
Flow
Template
Configuration
```

Exemplo:

```text
Property Report
│
├── Input
│   └── propertyId
│
├── Flow
│   ├── GET_PROPERTY
│   ├── GET_GEOMETRY
│   ├── GET_CENTROID
│   └── GET_MUNICIPALITY
│
└── Template
    └── property-report.html
```

---

# 3.5 Execution

Execution representa uma execução concreta de um Report.

Exemplo:

```text
Report:
Property Report

Input:
propertyId = 123

Execution:
2026-10-26 10:30

Output:
property-report.pdf
```

Uma Execution deve registrar:

- report;
- version;
- input;
- blocks executados;
- outputs;
- status;
- erros;
- duração;
- resultado final.

---

# 4. Arquitetura Conceitual

```text
                         REPORTS
                            │
              ┌─────────────┴─────────────┐
              │                           │
          REPORTS API                REPORTS UI
              │                           │
              └─────────────┬─────────────┘
                            │
                       Report Engine
                            │
             ┌──────────────┼──────────────┐
             │              │              │
         Flow Engine   Template Engine   Execution
             │              │
             │              │
        Block Registry      HTML
             │              │
             │              ↓
             │         PDF Renderer
             │
             ↓
     InfiniteStack Services
             │
       ┌─────┴─────┐
       │           │
     GEOLIB       DATA
```

---

# 5. Relação com o InfiniteStack

O REPORTS deve ser um aplicativo interno do InfiniteStack.

Ele deve reutilizar:

- autenticação;
- autorização;
- organização;
- usuários;
- projetos;
- dados catalogados;
- banco de dados;
- GEOLIB;
- infraestrutura existente.

O REPORTS não deve duplicar dados que já pertencem a outros módulos.

---

# 6. Relação com o GEOLIB

O GEOLIB é responsável por disponibilizar e administrar informações geográficas.

O REPORTS pode consumir essas informações através de Blocks.

Exemplo:

```text
GEOLIB
   │
   ├── Município
   ├── Estado
   ├── Limites
   ├── Camadas
   ├── Raster
   └── Vetores
        │
        ↓
REPORTS BLOCK
GET_MUNICIPALITY
        │
        ↓
REPORT OUTPUT
```

O REPORTS não deve duplicar a responsabilidade do GEOLIB.

---

# 7. Blocks

## 7.1 Primeiro conjunto de Blocks

O MVP deve possuir Blocks simples e determinísticos.

### GET_MUNICIPALITY

Inputs:

```json
{
  "latitude": -22.21,
  "longitude": -49.65
}
```

Output:

```json
{
  "state": "SP",
  "municipality": "Garça",
  "ibgeCode": "3516705"
}
```

---

### GET_PROPERTY

Input:

```text
propertyId
```

Output:

```json
{
  "id": 123,
  "name": "Fazenda São João",
  "area": 1250.43
}
```

---

### GET_GEOMETRY

Input:

```text
propertyId
```

Output:

```json
{
  "geometry": "GeoJSON"
}
```

---

### CALCULATE_AREA

Input:

```text
geometry
```

Output:

```json
{
  "squareMeters": 1250432,
  "hectares": 125.04
}
```

---

# 8. Block Contract

Todo Block deve possuir:

```text
id
name
description
version
inputs
outputs
executor
```

Exemplo conceitual:

```json
{
  "id": "get-municipality",
  "name": "Consultar Município",
  "version": "1.0.0",
  "inputs": {
    "latitude": "number",
    "longitude": "number"
  },
  "outputs": {
    "state": "string",
    "municipality": "string",
    "ibgeCode": "string"
  }
}
```

---

# 9. Inputs

Inputs podem vir de:

1. contexto da execução;
2. outro Block;
3. valor fixo;
4. variável;
5. configuração do Report.

Exemplo:

```text
GET_PROPERTY
propertyId
    ↑
Report Input
```

Ou:

```text
GET_MUNICIPALITY
latitude
    ↑
GET_CENTROID.latitude
```

---

# 10. Outputs

Outputs devem possuir nome e tipo.

Exemplo:

```text
municipality.name
municipality.state
municipality.ibgeCode
```

Os outputs podem ser consumidos por:

- outros Blocks;
- Template;
- logs;
- API;
- futuras integrações.

---

# 11. Context

Toda Execution possui um Context.

Exemplo:

```json
{
  "propertyId": 123
}
```

Durante a execução:

```text
Context
   │
   ├── propertyId
   │
   ├── property
   │
   ├── geometry
   │
   ├── centroid
   │
   └── municipality
```

O Context é a memória da execução.

---

# 12. Templates

Templates serão HTML.

Exemplo:

```html
<!DOCTYPE html>

<html>
<head>
    <style>
        body {
            font-family: Arial, sans-serif;
        }
    </style>
</head>

<body>

<h1>{{property.name}}</h1>

<p>
    Município: {{municipality.name}}
</p>

<p>
    Estado: {{municipality.state}}
</p>

<p>
    Código IBGE: {{municipality.ibgeCode}}
</p>

</body>
</html>
```

---

# 13. Variáveis

Variáveis devem utilizar sintaxe:

```text
{{namespace.property}}
```

Exemplos:

```text
{{property.name}}

{{property.area}}

{{municipality.name}}

{{municipality.ibgeCode}}

{{location.latitude}}
```

A engine deve impedir acesso a propriedades que não estejam disponíveis no Context.

---

# 14. Preview

O usuário deve conseguir visualizar o relatório antes de gerar o PDF.

Fluxo:

```text
Execute Flow
     ↓
Build Context
     ↓
Render Template
     ↓
HTML
     ↓
Preview
```

O Preview deve permitir validar:

- dados;
- layout;
- imagens;
- mapas;
- tabelas;
- paginação.

---

# 15. PDF

A primeira saída oficial será PDF.

Fluxo:

```text
Template
   ↓
Rendered HTML
   ↓
HTML Renderer
   ↓
PDF
```

A tecnologia utilizada para renderização deve suportar HTML/CSS moderno e paginação.

A implementação concreta fica definida no ARCHITECTURE.md.

---

# 16. Tipos de dados futuros

O sistema deve ser preparado para:

```text
string
number
boolean
date
datetime
object
array
geometry
image
table
chart
map
file
```

---

# 17. Blocks futuros

Depois do MVP:

```text
IF
FILTER
MAP
LOOP
SORT
GROUP
JOIN
TRANSFORM
FORMAT
SQL_QUERY
HTTP_REQUEST
GET_RASTER
GET_VECTOR
INTERSECT_LAYER
BUFFER
CLIP
CALCULATE_STATISTICS
GENERATE_MAP
GENERATE_CHART
AI_ANALYSIS
```

Esses Blocks não fazem parte do MVP inicial.

---

# 18. Relatórios Geográficos

O REPORTS deve ser especialmente preparado para relatórios geográficos.

Exemplo:

```text
Property
   ↓
Geometry
   ↓
Centroid
   ↓
Municipality
   ↓
Intersect Layers
   ↓
Statistics
   ↓
Map
   ↓
Report
```

Um relatório poderá conter:

- mapas;
- coordenadas;
- área;
- municípios;
- estados;
- camadas;
- imagens;
- estatísticas;
- tabelas.

---

# 19. Segurança

Templates não devem possuir acesso irrestrito ao ambiente.

Não permitir inicialmente:

```html
<script>
    fetch(...)
</script>
```

Não permitir acesso arbitrário a:

- filesystem;
- banco de dados;
- rede interna;
- variáveis de ambiente;
- serviços internos.

O acesso a dados deve acontecer exclusivamente através dos Blocks autorizados.

---

# 20. Versionamento

Reports, Flows, Blocks e Templates devem ser versionáveis.

Exemplo:

```text
Property Report
├── v1
├── v2
└── v3
```

Uma Execution deve sempre registrar a versão utilizada.

Isso garante que um relatório antigo possa ser reproduzido.

---

# 21. MVP

O MVP deve conter:

## Blocks

- GET_PROPERTY
- GET_GEOMETRY
- GET_CENTROID
- GET_MUNICIPALITY
- CALCULATE_AREA

## Flow

- criação de Flow;
- adição de Blocks;
- conexão entre inputs e outputs;
- execução.

## Template

- editor HTML;
- editor CSS;
- variáveis;
- preview.

## Reports

- criação;
- edição;
- execução;
- versionamento básico.

## Output

- HTML;
- PDF.

---

# 22. Fora do MVP

Não implementar inicialmente:

- editor visual completo de documentos;
- AI Blocks;
- loops;
- condições complexas;
- marketplace de Blocks;
- colaboração em tempo real;
- assinatura digital;
- workflow approval;
- agendamento;
- distribuição automática;
- múltiplos formatos de exportação.

Esses recursos podem ser adicionados posteriormente.

---

# 23. Princípio fundamental

O REPORTS deve seguir o princípio:

> **Dados primeiro. Apresentação depois.**

O Flow determina **o que deve ser obtido**.

O Template determina **como deve ser apresentado**.

Isso permite utilizar o mesmo Flow em diferentes Templates.

Exemplo:

```text
                ┌──► PDF
                │
Flow ───────────┼──► HTML
                │
                ├──► API
                │
                └──► Dashboard
```

---

# 24. Resultado esperado

Ao final do MVP, um usuário deve conseguir:

```text
1. Criar um Report

2. Definir seus Inputs

3. Criar um Flow

4. Adicionar Blocks

5. Conectar os Blocks

6. Executar o Flow

7. Visualizar os Outputs

8. Criar um HTML

9. Utilizar {{variables}}

10. Visualizar Preview

11. Gerar PDF
```

Esse fluxo representa o núcleo do REPORTS.