# Serviços, NBS e tomadores

## Variações de serviço por emitente

Em **Padrões fiscais**, cada configuração possui **Nome do serviço / variação**, **Código NBS (9 dígitos)**, códigos LC 116/nacional, CNAE, descrição, percentuais e **ISS retido**. O NBS é opcional no cadastro; quando informado deve conter 9 dígitos.

É permitido reutilizar os mesmos códigos e a mesma data de início com nomes diferentes. Exemplos de nomes:

- `Anexo III — sem retenção`, ISS retido desmarcado.
- `Anexo III — com retenção`, ISS retido marcado.
- Outros anexos ou atividades podem usar o mesmo formato, sem lista fixa de nomes.

**Duplicar serviço com outra retenção** copia os códigos, NBS, descrição e percentuais para o formulário, alterna ISS retido e sugere o novo nome. O contador revisa e salva. Não há gravação automática pela duplicação. Esses nomes são identificadores de cadastro; não selecionam automaticamente enquadramento, opção pelo Simples ou tratamento tributário. A alíquota continua sendo definida pelo contador.

A vigência é única por **escritório + emitente + nome da variação + data inicial**. Para mudar uma variação, salve outra versão com nova data inicial. A consulta aplica a maior data inicial até a competência, dentro do nome selecionado. O contador pode consultar também versões futuras alterando a data de consulta. O número de versão permanece sequencial por emitente.

Em **Nova nota**, **Serviço / variação** escolhe os padrões aplicados. O snapshot do rascunho guarda nome, NBS e retenção junto aos demais dados fiscais. Alterações posteriores não mudam rascunhos existentes.

Na preparação da DPS, o NBS preenchido no padrão é aplicado automaticamente e fica somente para leitura. O servidor também rejeita NBS divergente desse snapshot, inclusive ao importar uma DPS completa. Uma DPS completa deve respeitar a retenção de ISS preservada no rascunho. Para rascunhos sem NBS, permanece disponível a informação manual na preparação.

## Cadastro de tomadores

O menu **Tomadores** permite selecionar um emitente, pesquisar, cadastrar e editar CPF/CNPJ, nome/razão social, e-mail e endereço nacional opcional. CPF/CNPJ tem validação de dígitos verificadores. O endereço, quando informado, exige município IBGE, CEP, logradouro, número e bairro.

O cadastro é vinculado ao escritório e ao emitente. O mesmo CPF/CNPJ pode existir para emitentes diferentes, mas não se repete dentro do mesmo emitente. Usuários cliente só acessam tomadores da empresa à qual estão vinculados.

Em **Preparar emissão nacional**, **Tomador cadastrado** preenche os campos e o endereço. Ainda é possível informar manualmente. **Salvar tomador no cadastro** abre o formulário para cadastrar os dados digitados ou atualizar o tomador selecionado. Editar somente os campos da nota não modifica automaticamente o cadastro. A DPS preparada preserva os dados utilizados no pedido, sem depender de alterações posteriores no tomador.

## Banco e compatibilidade

A migração Flyway compartilhada `src/main/java/db/common/V3__Named_services_and_recipients.java` executa em PostgreSQL e H2. Ela acrescenta `variant_name` e `nbs` a `fiscal_defaults`, ajusta a restrição de vigência e cria `recipient`. Configurações anteriores continuam como `Padrão`, sem NBS; snapshots antigos continuam legíveis. Não editar V1/V2 nem executar scripts manuais.

Reinicie o backend atualizado para aplicar a migração. Se usar Docker, execute `docker compose up --build -d` para atualizar também o frontend.

## API

- `POST /api/issuers/{id}/defaults`: aceita `variantName` e `nbs`, além dos campos anteriores. Omiti-los mantém compatibilidade (`Padrão` e NBS vazio).
- `GET /api/issuers/{id}/defaults?competence=2026-11-11&variantName=...`: consulta a variação vigente. Sem `variantName`, consulta `Padrão`.
- `GET /api/issuers/{id}/services?competence=2026-11-11`: retorna uma configuração vigente por nome.
- `POST /api/drafts`: aceita `variantName`; o servidor resolve e preserva a configuração.
- `GET /api/issuers/{id}/recipients`: retorna os tomadores como `{id, values}`.
- `POST /api/issuers/{id}/recipients`: recebe `{taxId, name, email, address}`.
- `PUT /api/issuers/{id}/recipients/{recipientId}`: atualiza um tomador dentro do emitente autorizado.

Todas essas rotas exigem autenticação. A criação de padrões continua exclusiva do gestor. A preparação da nota continua exigindo A1 e procuração válidos, perfil e numeração, validação XSD e confirmação de transmissão.
