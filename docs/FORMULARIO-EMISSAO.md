# Formulário de emissão em três etapas

O fluxo em **Nova nota** segue a organização visual do vídeo de referência, com a identidade SA Trindade e foco em NFS-e Nacional. O vídeo não foi importado como dado do sistema; os testes e a prévia utilizam empresas e pessoas sintéticas.

## 1. Documento e destinatário

- Empresa emitente e competência.
- Tipo NFS-e e natureza prestação de serviços.
- Tomador cadastrado ou CPF/CNPJ, nome e e-mail informados manualmente.
- Cadastro rápido de cliente sem sair da emissão, endereço opcional e município da prestação.

## 2. Serviços

- Seleção do serviço/variação vigente, com LC 116, código nacional e NBS do emitente.
- Quantidade com até quatro casas decimais, valor unitário com duas casas e inclusão/remoção de itens.
- Tabela com descrição, quantidade, unitário e subtotal.
- Ajuste de descrição com justificativa e código tributário municipal opcional.

Todos os itens da nota compartilham o serviço/variação selecionado. Para trocar a variação, remova os itens. Operações com códigos ou tratamentos distintos devem ser separadas em notas; o formulário não mistura classificações fiscais em uma única DPS.

O servidor soma os subtotais, arredondando cada quantidade × unitário para duas casas com `HALF_UP`. Não confia no total enviado pelo navegador. Até 20 itens são permitidos. Quantidades e unitários aparecem na descrição fiscal, respeitando o limite de 2.000 caracteres. Exemplo validado: **4 × R$ 250,00 = R$ 1.000,00**.

## 3. Pagamento e emissão

- Forma de pagamento: PIX, dinheiro, transferência, cartão de crédito/débito, outros ou não informada.
- Data de vencimento, desconto condicionado ou incondicionado e informações complementares.
- Seleção de retenções de ISS, PIS, COFINS, CSLL, IRRF e INSS, com alíquota, base e valor calculado.
- Informação dos tributos aproximados por percentual do Simples, percentuais por esfera ou opção de não informar, quando permitida.
- Resumo lateral com serviços, descontos, valor da nota, retenções e líquido estimado.

As retenções são preenchidas pelo padrão do emitente. Alterar incidência/alíquota ou descrição exige justificativa; a mudança vale somente para a nota. A configuração original é preservada para auditoria e não é sobrescrita.

A base de ISS deste formulário é o valor dos serviços menos desconto incondicionado; deduções especiais continuam exigindo tratamento fiscal específico. As bases federais são sugestões iniciais e devem ser conferidas pelo contador. PIS e COFINS compartilham a mesma base quando ambos são selecionados. O sistema calcula o valor de cada retenção com as escolhas explícitas e rejeita valores inconsistentes, descontos excessivos e líquido estimado negativo.

O resumo é **estimativa comercial**, não substitui os valores calculados e devolvidos pelo Fisco. PIX é uma condição de pagamento cadastrada; não gera cobrança, QR Code nem comprovante automaticamente.

## Rascunho e confirmação

**Salvar rascunho** grava o formulário válido no banco, incluindo tomador, itens, pagamento, descontos, bases e retenções. Não transmite ao Fisco. Rascunhos podem ser salvos antes de configurar A1/procuração ou preencher os tributos aproximados; a preparação posterior pede os dados pendentes.

**Revisar emissão** salva o rascunho e abre a confirmação com empresa, tomador, totais e ambiente. Após digitar o CNPJ, **Gerar e transmitir NFS-e** prepara/assina a DPS e solicita a autorização. A1, procuração, enquadramento e produção habilitada, quando aplicável, continuam sendo verificados pelo backend.

Uma autorização confirmada oferece acesso ao DANFSe oficial e à nota. Se houver resultado pendente ou falha de comunicação após preparar a DPS, a tela orienta acompanhar a emissão; não repete a transmissão automaticamente. A rota de acompanhamento usa `?note=UUID`. As ações de consulta e eventual reenvio da mesma DPS permanecem na tela de notas fiscais.

## Persistência e XML

`POST /api/drafts` aceita um objeto `commercial` opcional, validado no servidor. Pedidos antigos sem esse objeto continuam compatíveis.

O snapshot armazena:

- `commercial`: formulário, tomador, itens, condições e seleções de tributos;
- `totals`: cálculos em `BigDecimal`, refeitos no servidor;
- `defaults`: parâmetros fiscais efetivamente usados;
- `sourceDefaults`: configuração anterior quando houve ajuste tributário justificado;
- descrição, justificativa e usuário responsável.

Os dados usam a coluna de snapshot já existente; não há mudança de tabela nesta etapa. Backups e transferência entre H2/PostgreSQL preservam o JSON. Alterar o cadastro do tomador ou criar nova versão dos padrões não muda os documentos salvos.

A DPS estruturada aplica o tomador preservado, descontos nos grupos oficiais, informações complementares/pagamento em `infoCompl/xInfComp`, retenções federais e totais aproximados. O NBS e a retenção de ISS acompanham os parâmetros efetivos do snapshot. Todos passam pelo XSD local correspondente ao ambiente. Quebras de linha são normalizadas nos campos fiscais que não as aceitam; o texto comercial original fica no snapshot.

Rascunhos estruturados usam os próprios dados na preparação e não aceitam uma DPS completa diferente sobreposta. O fluxo de DPS avançada continua disponível para rascunhos anteriores/técnicos, sujeito às validações existentes. O pacote oficial e os leiautes de referência estão na [documentação técnica da NFS-e Nacional](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual).

O ambiente padrão permanece **HOMOLOGACAO**. Testes utilizam gateway controlado, certificados artificiais e confirmação de interface simulada, sem emissão de notas reais. A homologação com contribuinte e certificado reais permanece pendente.


### Município da prestação

**Usar município do prestador** vem marcado por padrão. A busca exibe o município cadastrado do prestador e fica desabilitada. Ao desmarcar, é obrigatório selecionar a cidade da prestação na busca por nome/UF. O rascunho grava explicitamente o código IBGE da opção escolhida. Trocar o prestador ou iniciar outra nota restaura a opção padrão; alternar o checkbox durante o preenchimento não envia a cidade manual quando a opção do prestador está marcada.


### Recuperação automática do preenchimento

A tela salva automaticamente no `localStorage` os campos e seleções, inclusive preenchimento incompleto e a etapa atual, por escritório, usuário e prestador. Ao visitar configurações, voltar à emissão ou recarregar a página, os dados são recuperados. A última empresa é restaurada somente se continuar autorizada ao usuário.

**Cancelar preenchimento** apaga o formulário local após confirmação. Salvar rascunho ou fechar a revisão não apaga o preenchimento. A resposta definitiva da transmissão (autorizada/rejeitada) limpa o formulário local; resultados incertos preservam uma referência à DPS preparada e retomam o acompanhamento sem transmitir automaticamente. A chave de idempotência do rascunho também é preservada. Nenhuma senha de login, A1 ou chave de assinatura é salva nesse mecanismo.

O armazenamento pertence ao navegador e à origem de acesso; limpar seus dados ou trocar de navegador remove essa recuperação. Se o navegador bloquear o armazenamento ou não houver espaço, a tela avisa e continua permitindo salvar um rascunho no servidor. O cancelamento do preenchimento local não cancela notas fiscais ou apaga rascunhos já gravados no banco.
