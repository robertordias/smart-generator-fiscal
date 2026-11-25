# Serviços, NBS e tomadores

## Variações de serviço por emitente

Em **Padrões fiscais**, cada configuração possui **Nome do serviço / variação**, **Código NBS (9 dígitos)**, códigos LC 116/nacional, CNAE, descrição, percentuais e **ISS retido**. O NBS é opcional no cadastro; quando informado deve conter 9 dígitos.

É permitido reutilizar os mesmos códigos e a mesma data de início com nomes diferentes. Exemplos de nomes:

- `Anexo III — sem retenção`, ISS retido desmarcado.
- `Anexo III — com retenção`, ISS retido marcado.
- Outros anexos ou atividades podem usar o mesmo formato, sem lista fixa de nomes.

**Duplicar serviço com outra retenção** copia os códigos, NBS, descrição e percentuais para o formulário, alterna ISS retido e sugere o novo nome. O contador revisa e salva. Não há gravação automática pela duplicação. Esses nomes são identificadores de cadastro; não selecionam automaticamente enquadramento, opção pelo Simples ou tratamento tributário. A alíquota continua sendo definida pelo contador.

A vigência é única por **escritório + emitente + nome da variação + data inicial**. Para uma mudança com nova vigência, salve outra configuração com nova data inicial. Para corrigir o cadastro vigente (principalmente NBS), use **Editar configuração**, revise os campos e clique em **Salvar alterações**. A edição permite manter a mesma vigência e incrementa o número de versão; substitui os dados dessa configuração para novos rascunhos, sem manter uma cópia separada da configuração corrigida. Notas e rascunhos existentes preservam seus snapshots. Edições concorrentes são rejeitadas para evitar sobrescrita silenciosa. A consulta aplica a maior data inicial até a competência, dentro do nome selecionado. O contador pode consultar também versões futuras alterando a data de consulta. O número de versão permanece sequencial por emitente.

Em **Nova nota**, **Serviço / variação** escolhe os padrões aplicados. O snapshot do rascunho guarda nome, NBS e retenção junto aos demais dados fiscais. Alterações posteriores não mudam rascunhos existentes.

Na preparação da DPS, o NBS preenchido no padrão é aplicado automaticamente e fica somente para leitura. O servidor também rejeita NBS divergente desse snapshot, inclusive ao importar uma DPS completa. Uma DPS completa deve respeitar a retenção de ISS preservada no rascunho. Para rascunhos sem NBS, permanece disponível a informação manual na preparação.

## Cadastro de tomadores

O menu **Tomadores** permite selecionar um emitente, pesquisar, cadastrar e editar CPF/CNPJ, nome/razão social, e-mail e endereço nacional opcional. CPF/CNPJ tem validação de dígitos verificadores. O endereço, quando informado, exige município IBGE, CEP, logradouro, número e bairro.

O cadastro pertence ao escritório e pode ser vinculado a vários prestadores. Em **Tomadores → Vincular tomador existente**, o contador seleciona um cadastro e marca os prestadores desejados; também pode usar **Vincular prestadores** na linha do tomador. As empresas já vinculadas aparecem marcadas. O vínculo é persistido e o mesmo tomador fica disponível na emissão de cada prestador.

A edição feita pelo contador em qualquer prestador vinculado atualiza o cadastro compartilhado. Notas e rascunhos anteriores preservam os dados utilizados. Usuários cliente consultam somente os tomadores de sua empresa e não podem gerenciar vínculos ou editar um tomador compartilhado; solicitam a correção ao contador.

Cadastros antigos separados com o mesmo CPF/CNPJ não são unificados automaticamente, pois podem conter dados diferentes. O sistema rejeita o vínculo se o prestador já tiver outro cadastro com esse documento, sem sobrescrever os dados. Repetir um vínculo existente não duplica o registro.

Em **Preparar emissão nacional**, **Tomador cadastrado** preenche os campos e o endereço. Ainda é possível informar manualmente. **Salvar tomador no cadastro** abre o formulário para cadastrar os dados digitados ou atualizar o tomador selecionado. Editar somente os campos da nota não modifica automaticamente o cadastro. A DPS preparada preserva os dados utilizados no pedido, sem depender de alterações posteriores no tomador.

## Banco e compatibilidade

A migração Flyway compartilhada `src/main/java/db/common/V3__Named_services_and_recipients.java` executa em PostgreSQL e H2. Ela acrescenta `variant_name` e `nbs` a `fiscal_defaults`, ajusta a restrição de vigência e cria `recipient`. Configurações anteriores continuam como `Padrão`, sem NBS; snapshots antigos continuam legíveis. Não editar V1/V2 nem executar scripts manuais.

A V5 cria `recipient_issuer`, com vínculo entre o tomador e os prestadores, e preserva os vínculos anteriores automaticamente. A transferência H2/PostgreSQL também preserva os vínculos, inclusive de bases anteriores à V5.

Reinicie o backend atualizado para aplicar as migrações. Se usar Docker, execute `docker compose up --build -d` para atualizar também o frontend.

## API

- `POST /api/issuers/{id}/defaults`: aceita `variantName` e `nbs`, além dos campos anteriores. Omiti-los mantém compatibilidade (`Padrão` e NBS vazio).
- `PUT /api/issuers/{id}/defaults/{defaultsId}?version=N`: corrige os campos da configuração selecionada, com controle de concorrência e acesso exclusivo do gestor.
- `GET /api/issuers/{id}/defaults?competence=2026-11-11&variantName=...`: consulta a variação vigente. Sem `variantName`, consulta `Padrão`.
- `GET /api/issuers/{id}/services?competence=2026-11-11`: retorna uma configuração vigente por nome.
- `POST /api/drafts`: aceita `variantName`; o servidor resolve e preserva a configuração.
- `GET /api/issuers/{id}/recipients`: retorna os tomadores como `{id, values}`.
- `GET /api/recipients`: catálogo do escritório, incluindo `issuerIds`; exclusivo do gestor.
- `POST /api/recipients/{recipientId}/issuers`: recebe `{issuerIds: [UUID, ...]}`, adiciona vínculos de forma transacional; exclusivo do gestor.
- `POST /api/issuers/{id}/recipients`: recebe `{taxId, name, email, address}`.
- `PUT /api/issuers/{id}/recipients/{recipientId}`: atualiza um tomador dentro do emitente autorizado.

Todas essas rotas exigem autenticação. A criação e a edição de padrões são exclusivas do gestor. A preparação da nota continua exigindo A1 e procuração válidos, perfil e numeração, validação XSD e confirmação de transmissão.


## Endereço automático na emissão

Ao selecionar o tomador, o formulário copia o endereço cadastrado. Se não houver endereço próprio, a API aproveita o cadastro completo de uma empresa importada pelo mesmo CNPJ, dentro do mesmo escritório. Endereços próprios têm prioridade; registros incompletos não são inventados nem completados com dados de outros escritórios.

Se o tomador não tiver endereço completo, os campos abrem para preenchimento. **Editar cadastro do tomador** permite salvar esses dados para próximas emissões. O rascunho mantém sua cópia do endereço; alterações no cadastro não reescrevem documentos anteriores.

O fluxo estruturado pede endereço quando houver ISS retido e a preparação da DPS valida essa exigência antes da assinatura. Para uma DPS já rejeitada por falta de endereço, corrija o cadastro e gere um novo rascunho; o XML anteriormente assinado não é alterado.


## Buscar endereço pelo CNPJ

No cadastro ou edição de tomador, informe um CNPJ válido e clique em **Buscar pelo CNPJ**. O backend consulta somente esse CNPJ na [BrasilAPI](https://brasilapi.com.br/docs#tag/CNPJ), preenche razão social e endereço e preserva o e-mail já digitado. A consulta usa HTTPS com destino fixo, sem enviar certificado A1, senha ou procuração.

O município usa `codigo_municipio_ibge`, não o código cadastral da Receita. Campos ausentes devem ser completados pelo usuário. Revise os dados e clique em **Salvar tomador**; consultar não grava automaticamente. CPF permanece manual. Falhas ou limites do serviço preservam os dados digitados e permitem preenchimento manual. Respostas são reutilizadas por até seis horas em memória, com cache limitado a 500 CNPJs.

API autenticada: `GET /api/issuers/{id}/recipients/cnpj/{cnpj}`, restrita ao emitente autorizado. O retorno contém somente dados utilizados no cadastro, sem quadro societário ou demais dados do provedor.
