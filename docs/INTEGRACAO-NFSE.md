# Emissão nacional por procuração e certificado A1

O conector Java está implementado, com telas em **A1 e procurações** e **Notas fiscais**. Utiliza o A1 do representante para assinatura XMLDSig e autenticação mTLS; o cliente continua identificado como prestador. Sem certificado ICP-Brasil e procuração real não foi possível executar homologação no Fisco. Os testes locais usam uma CA artificial e respostas controladas, nunca emissão real.

## Configuração do servidor

O sistema inicia normalmente sem configuração fiscal, mas a importação do A1 e a emissão ficam bloqueadas até o administrador configurar o cofre e as âncoras de confiança.

| Variável | Finalidade |
|---|---|
| `NFSE_MASTER_KEY` | Chave AES-256 de 32 bytes em Base64. Deve permanecer estável e protegida; sem ela os A1 existentes não podem ser reabertos. |
| `NFSE_ICP_TRUSTSTORE` | Caminho absoluto para um truststore PKCS#12 com âncoras ICP-Brasil confiáveis, obtidas/verificadas no ITI. Não importar a CA artificial dos testes em ambiente real. |
| `NFSE_ICP_TRUSTSTORE_PASSWORD` | Senha do truststore. Não é a senha do A1. |
| `NFSE_ENVIRONMENT` | `HOMOLOGACAO` por padrão; ou `PRODUCAO`. |
| `NFSE_PRODUCTION_ENABLED` | `true` libera operações de produção. Padrão `false`. |
| `NFSE_CHECK_REVOCATION` | Validação PKIX de revogação, `true` por padrão. Nos testes com CA artificial é `false`. |
| `NFSE_SIGNATURE_ALGORITHM` | `SHA256` por padrão; `SHA1` disponível para compatibilidade com contratos que exijam o algoritmo legado. Confirmar o perfil aceito no ambiente de homologação antes de produção. |
| `NFSE_HOMOLOGATION_URL` | Opcional, ajusta o caminho da API no domínio oficial de homologação. Padrão `https://sefin.producaorestrita.nfse.gov.br/API/SefinNacional/`. |
| `NFSE_PRODUCTION_URL` | Opcional, ajusta o caminho da API no domínio oficial de produção. Padrão `https://sefin.nfse.gov.br/SefinNacional/`. |

Os endpoints só aceitam HTTPS e o domínio oficial do respectivo ambiente; não há opção de ignorar validação TLS ou enviar o A1 a um host arbitrário. A cadeia de confiança do servidor HTTPS usa o truststore padrão da JVM; `NFSE_ICP_TRUSTSTORE` valida o certificado do representante.

Exemplo de preparação de um truststore a partir de uma raiz previamente baixada e verificada no [repositório oficial do ITI](https://www.gov.br/iti/pt-br/assuntos/repositorio/repositorio). O comando pede a senha e a confirmação de confiança; repetir para as raízes necessárias com aliases diferentes:

```sh
keytool -importcert -alias icp-raiz-verificada -file /caminho/raiz-verificada.crt \
  -keystore /caminho/icp-truststore.p12 -storetype PKCS12
```

Para gerar uma chave mestra, execute `openssl rand -base64 32` e guarde o resultado em um gerenciador de segredos. Configure-o como `NFSE_MASTER_KEY` no ambiente de execução. Não gerar nova chave a cada inicialização e não compartilhar a saída em logs ou no Git.

Após definir as variáveis, executar o JAR Java 21. Para habilitar busca de LCR via pontos de distribuição na JVM:

```sh
java -Dcom.sun.security.enableCRLDP=true -jar target/emissor-fiscal-0.1.0.jar
```

A importação exige um PKCS#12 com uma única chave privada, RSA de pelo menos 2048 bits, certificado final válido e usos compatíveis. O CPF/CNPJ é extraído dos OIDs ICP-Brasil no SAN (`2.16.76.1.3.1` / `2.16.76.1.3.3`), sem confiar no nome do arquivo ou em um CPF/CNPJ digitado livremente. O cofre usa AES-GCM com contexto por escritório e certificado; a chave privada e a senha não são devolvidas pela API. A senha de acesso ao SA Trindade continua sendo `APP_ADMIN_PASSWORD`, separada das senhas fiscais.

## Fluxo operacional

1. **A1 e procurações → Cadastrar A1:** importar o arquivo do contador/representante e informar CPF/CNPJ titular e senha do arquivo.
2. Selecionar a empresa cliente e salvar seu enquadramento, inscrição municipal quando exigida, série DPS e último número já usado nessa série e ambiente. Para uma série nova sem emissões, informar zero. A numeração é reservada com bloqueio do emitente e nunca retrocede.
3. Vincular a procuração eletrônica existente, seu período e poderes de emissão, consulta e cancelamento ao A1 do outorgado. O registro no sistema é declaratório; o sistema não cria procuração na Receita nem presume que um cadastro local comprova poderes externos. A aceitação é apurada na operação fiscal.
4. Criar um rascunho usando os padrões fiscais habituais da empresa.
5. **Rascunhos → Emitir**, ou **Notas fiscais → Preparar emissão:** informar tomador, endereço quando exigido, município da prestação e classificação complementar. Revisar e assinar a DPS. Esta etapa não transmite.
6. **Transmitir:** conferir ambiente, cliente e valor e confirmar o CNPJ do prestador. A DPS assinada vai por POST `/nfse`, em JSON com XML GZip/Base64, sobre mTLS.
7. Acompanhar o resultado, baixar XML autorizado ou solicitar cancelamento. A identidade do procurador não substitui o prestador no documento.

Somente o perfil gestor/contador importa A1, altera enquadramento/procuração e executa operações fiscais. O cliente pode consultar documentos e arquivos de sua própria empresa. Todas as consultas e referências são verificadas pelo tenant e pelo vínculo de empresa no servidor.

## Dados fiscais e cenários

O formulário gera DPS 1.01 para prestação nacional tributável pelo ISS, com tomador identificado, regime informado e classificação do rascunho. Usa o snapshot do rascunho para código nacional, descrição, valor, alíquota e retenção habitual de ISS. CNAE e LC 116 permanecem referências da configuração; não são confundidos com o código nacional nem inseridos em tags inexistentes.

Os grupos de tributos federais exigem confirmação dos valores e condições; percentuais habituais não são convertidos indiscriminadamente em retenções. Se houver percentuais federais no padrão, a geração bloqueia sua omissão. O formulário permite PIS/COFINS e valores retidos de IRRF, CSLL e contribuição previdenciária, e oferece grupo XML para situações adicionais.

Os totais aproximados são escolhidos explicitamente no formulário: percentual do Simples, percentuais federal/estadual/municipal ou não informação quando permitida. Não se confundem com valores retidos. Para emitente não MEI, a API exige esse grupo na geração assistida; na importação de DPS completa o grupo é validado pelo XSD junto ao restante do documento.

Para IBS/CBS, totais aproximados e cenários especiais (exportação, imunidade, deduções, construção, substituição etc.), há campos de grupos oficiais e importação de DPS completa não assinada. O sistema verifica correspondência com emitente, competência, valor, descrição e código nacional do rascunho, controla numeração/ambiente/assinatura e valida os XSD. Os campos adicionais e regras condicionais precisam ser preenchidos conforme o cenário; passar no XSD não garante autorização. A regra oficial remota permanece determinante. Não há promessa de cobertura já homologada de todos os cenários tributários.

Os XSD oficiais de produção 1.01, pacote `20260209`, estão incluídos em `src/main/resources/nfse/xsd/`. Homologação usa separadamente o pacote `20261020`, em `xsd/homologacao/`. Uma mudança oficial requer atualização coordenada dos schemas e testes. A compatibilidade do regex de série de produção com Xerces e a resolução local de DTD estão documentadas em xsd/README.md. XML com DTD, entidades externas ou referências externas é rejeitado. O resolver de XSD só carrega recursos locais incluídos no projeto.

## Estados, retentativas e auditoria

- `PREPARED`: DPS assinada, sem envio. Revisão conserva número e identidade; não cria uma segunda emissão para o mesmo rascunho/ambiente.
- `SENDING`: intenção de envio persistida antes da chamada externa. Nenhuma transação de banco fica aberta durante o HTTP.
- `AUTHORIZED`: resposta oficial contém XML NFS-e válido, com identidade DPS, emitente, ambiente, competência e valor correspondentes. Chave e número oficiais são armazenados.
- `REJECTED`: resposta fiscal estruturada de rejeição; não gera XML autorizado.
- `UNKNOWN`: timeout, indisponibilidade ou retorno sem autorização validável. **Consultar Fisco** busca a chave por `/dps/{id}` e o XML por `/nfse/{chave}`. Somente um retorno oficial 404 de DPS permite reenviar o XML assinado já reservado, com a mesma identidade e numeração. Não há criação automática de uma nova DPS por falha de rede.
- `CANCELLING` / `CANCEL_UNKNOWN`: pedido e resultado incerto de cancelamento. A consulta busca o evento `101101`; não trata a solicitação como cancelamento consumado.
- Um 404 oficial de evento após cancelamento incerto libera somente o reenvio do mesmo pedido assinado, pela ação **Reenviar pedido**; não altera motivo, identidade ou assinatura.
- `CANCELLED`: evento oficial de cancelamento validado e armazenado. Uma rejeição de cancelamento mantém a autorização anterior.

Os pedidos de cancelamento seguem o schema `pedRegEvento_v1.01.xsd`, são assinados com A1 e enviados a `/nfse/{chave}/eventos`. Não há cancelamento “apenas no banco”. Prazo e admissibilidade dependem do Fisco.

Tentativas, operador, pedidos assinados e respostas fiscais são mantidos em `nfse_audit`, sem segredos do A1. Arquivos fiscais e snapshots são dados privados no banco atual e precisam de backup/acesso protegido. Há trava de envio concorrente no banco. A recuperação de timeout é feita pela ação de consulta; nesta versão não há worker automático de reconciliação ou emissão em fila.

## Endpoints implementados

Todos usam a autenticação existente. Requisições com cookie exigem `X-CSRF-Token` nas alterações; a interface já o envia. Operações do Fisco exigem perfil gestor.

| Método | Caminho sob `/api/nfse` | Função |
|---|---|---|
| GET | `/config` | Ambiente e estado da configuração |
| GET / POST | `/certificates` | Metadados / importação de A1 (JSON Base64) |
| DELETE | `/certificates/{id}` | Revogação local de uso do A1 |
| GET / PUT | `/issuers/{id}/profile` | Enquadramento, série e último número |
| GET / PUT | `/issuers/{id}/proxy` | Vínculo e poderes declarados da procuração |
| POST | `/prepare` | Gerar, validar e assinar DPS a partir de rascunho |
| GET | `/notes?issuerId=UUID&page=0&size=10` | Listagem paginada com escopo de acesso |
| GET | `/notes/{id}` | Situação e identificação do documento |
| POST | `/notes/{id}/transmit` | Transmitir ou reenviar a mesma DPS quando permitido |
| POST | `/notes/{id}/query` | Reconciliar pela DPS/chave ou consultar cancelamento |
| POST | `/notes/{id}/cancel` | Pedir cancelamento com motivo e confirmação do CNPJ |
| POST | `/notes/{id}/retry-cancel` | Reenviar pedido após consulta oficial sem evento |
| GET | `/notes/{id}/history` | Histórico sem segredos nem payload integral |
| GET | `/notes/{id}/files/{kind}` | `dps`, `xml` ou `event`, conforme disponibilidade |

## DANFSe

A antiga API de geração de DANFSe foi descontinuada. A tela disponibiliza acesso à consulta oficial por chave para obter o documento no portal. Não há geração local de PDF sob o nome DANFSe nem promessa de download automático pelo endpoint antigo. [Comunicado oficial sobre a descontinuação](https://www.gov.br/nfse/pt-br/noticias/se-cgnfs-e-prorroga-o-prazo-para-adequacao-ao-novo-leiaute-do-danfse).

## Homologação e limites de evidência

Os testes Java geram, assinam e validam DPS/NFS-e/eventos usando um A1 artificial e uma CA de laboratório. O gateway controlado testa autorização, rejeição, timeout, consulta, retentativa e cancelamento. Isso não comprova aceitação pelo Fisco nem valida uma procuração real.

Antes de operar em produção é necessário executar o fluxo no ambiente oficial com A1 ICP-Brasil, procuração válida e casos fiscais reais de homologação, confirmar o algoritmo de assinatura e caminho do endpoint aceitos e validar eventuais mudanças do leiaute. A documentação interativa da SEFIN encerrou a conexão sem certificado nesta sessão; os contratos implementados se baseiam nos manuais e schemas públicos. Nenhuma nota real foi enviada durante o desenvolvimento.

Fontes: [documentação de produção](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual), [endereços oficiais de APIs](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/apis-prod-restrita-e-producao) e [representação por procuração nas APIs](https://www.gov.br/nfse/pt-br/noticias/nova-funcionalidade-possibilita-representacao-e-alternancia-de-perfis-na-nfs-e).
