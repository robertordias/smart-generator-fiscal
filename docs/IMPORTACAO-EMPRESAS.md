# Importação da relação de empresas

Na tela **Empresas**, o gestor usa **Importar CSV → Selecionar CSV**, revisa a prévia e confirma em **Importar empresas**. A seleção e a prévia não gravam empresas. A confirmação grava os registros válidos no banco configurado, inclusive PostgreSQL; a lista é atualizada após a conclusão. **Dados cadastrais** permite consultar os campos salvos.

## Layout fixo

O arquivo deve ter cabeçalho, separador `;` e as 33 colunas da relação, nesta ordem. O importador valida a posição e o nome das colunas, ignorando acentos, caixa e BOM. Não há mapeamento manual:

```text
Código;Razão Social;Nome Fantasia;CNPJ/CPF;Tipo Tributação;Responsável;IE;IM;CEI;Tipo Empresa;Tipo de Contribuição;Incidência Tributária;Apropriação de Crédito;Número do Livro;Grupo;Tributação Real;Início da Atividade;Número do Órgão;Órgão de Registro;Desmem. CNAEF;Inscrição Suframa;CNAEF;Data Empresarial;Situação Empresa;Logradouro;Número;Complemento;CEP;Bairro;Cidade;UF;Telefone;E-mail
```

Aceita UTF-8 (com ou sem BOM) e Windows-1252, CRLF/LF, campos entre aspas, aspas duplicadas e ponto e vírgula/quebra de linha dentro de campos entre aspas. Limites: **1 MB e 5.000 empresas** por arquivo. Linhas completamente vazias são ignoradas.

| Colunas (posição começa em 1) | Dados persistidos |
|---|---|
| 2, 3, 4 | Razão social, nome fantasia e CNPJ |
| 5 | Tributação original, como referência textual |
| 7, 8 | Inscrições estadual e municipal, preservando zeros iniciais |
| 22 | CNAE (a coluna chamada CNAEF no arquivo) |
| 25 a 31 | Logradouro, número, complemento, CEP, bairro, cidade e UF |
| 32, 33 | Telefone e e-mail |

As demais colunas são ignoradas; não são guardados responsável, CEI, livro, grupo, datas de registro ou o arquivo original. CNPJ, CNAE e CEP são normalizados sem pontuação. A cidade/UF é convertida automaticamente para código IBGE pelo catálogo local em `src/main/resources/registry/municipalities.json`. Nenhum dado da empresa é enviado ao IBGE. Fonte e atualização do catálogo: [registro de municípios](../src/main/resources/registry/README.md).

## Validação e reimportação

- CNPJ deve ter 14 dígitos e verificadores válidos. CPF recebe erro explícito, pois o cadastro atual suporta emitentes pessoa jurídica.
- Razão social e município reconhecido são obrigatórios. CNAE, CEP e e-mail são validados quando preenchidos, assim como os limites dos campos. Inscrição municipal e CNAE ausentes geram avisos para revisão.
- A prévia mostra empresas prontas, ignoradas e inválidas, com mensagens por registro e paginação de 50 registros.
- A confirmação revalida o arquivo, as correções manuais e o cadastro atual. Apenas linhas prontas são gravadas.
- Cada linha inválida tem um botão **Corrigir**, que abre um modal somente com os campos que precisam de ajuste e os respectivos motivos. **Salvar correção** revalida no servidor e atualiza a prévia; se houver erro, o modal mantém os campos ainda inválidos. Em erros de quantidade de colunas, o modal permite reconstruir o conteúdo do registro CSV.
- Correções ficam na prévia até confirmar **Importar empresas**. É possível importar as linhas válidas, corrigir as pendentes no mesmo modal de importação e importar novamente, preservando os registros já gravados. **Cancelar correção** descarta as edições não salvas do formulário. O arquivo original permanece inalterado; fechar a importação ou selecionar outro arquivo descarta as correções ainda não importadas.
- CNPJ existente no mesmo escritório é ignorado; CNPJ repetido no arquivo usa a primeira ocorrência válida. Não há sobrescrita de cadastros, padrões fiscais, procurações ou notas existentes.
- Cada escritório possui seu próprio escopo. Clientes não podem importar. Falhas inesperadas na gravação desfazem toda a transação; uma duplicação concorrente retorna conflito, permitindo rever a prévia e repetir.

## Reaproveitamento fiscal

Ao criar a primeira configuração fiscal, o CNAE importado vem preenchido para revisão. Em **A1 e procurações**, **Usar inscrição municipal importada** copia o dado para o formulário de enquadramento, exigindo salvamento pelo contador. Inscrições com mais de 15 caracteres exigem revisão manual, conforme o limite atual do perfil nacional.

A tributação original pode listar mais de um regime; ela não define automaticamente a opção pelo Simples ou regimes especiais. O arquivo também não informa LC 116, tributação nacional do serviço, descrição padrão ou alíquotas. Esses dados continuam sendo configurados e versionados pelo contador por emitente, sem alterar snapshots de notas já criadas.

## Persistência e API

Flyway V2 cria `issuer_registration` vinculada a `issuer` por escritório/empresa, em PostgreSQL e H2. Não execute o script manualmente. A transferência H2 → PostgreSQL também inclui essa tabela quando ela existir na origem.

- `POST /api/issuers/import/preview`: multipart com campo `file`; valida sem gravar. Aceita `corrections`, um JSON opcional por número de registro (primeiro registro de dados = 2), por exemplo `{"2":{"cnpj":"11222333000181","email":"fiscal@example.com"}}`.
- `POST /api/issuers/import`: multipart com campos `file` e `corrections` opcional; revalida e confirma a gravação das linhas válidas. Somente campos cadastrais conhecidos podem ser corrigidos; status, tenant e identificadores internos não são aceitos.
- Os registros da resposta contêm `fieldErrors` por nome do campo. Erros estruturais usam `layout` e `rawLine`, sem guardar esse conteúdo no banco.
- `GET /api/issuers/{id}/registration`: consulta os dados complementares, respeitando o escopo do usuário.

Testes usam dados sintéticos. O CSV fornecido como referência não foi importado na base.
