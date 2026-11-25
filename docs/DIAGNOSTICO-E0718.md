# Diagnóstico E0718 — 24/11/2026

Verificação somente de leitura, sem emissão, cancelamento ou alteração de procuração.

## Evidências

- O documento apresentado pelo usuário identifica o CNPJ do emitente, o CPF do certificado cadastrado e autorização abrangendo todos os serviços. A tela apresentada mostra autorização ativa até 23/11/2031.
- A DPS rejeitada foi assinada pelo mesmo certificado vinculado à procuração local (comparação SHA-256 do certificado contido em X509Certificate com o cadastro).
- Validação criptográfica XMLDSig da DPS armazenada: verdadeira, utilizando a chave pública do A1 cadastrado. Essa verificação não substitui a autorização fiscal.
- Consulta autenticada com o mesmo A1: GET https://adn.producaorestrita.nfse.gov.br/contribuintes/DFe/0, com cnpjConsulta do emitente.
- Resposta: HTTP 404; TipoConsulta = Procuracao; StatusProcessamento = NENHUM_DOCUMENTO_LOCALIZADO; código E2220. O 404 informa ausência de documentos, enquanto TipoConsulta identifica reconhecimento da consulta por procuração. Nenhum XML fiscal retornado foi impresso ou salvo pelo diagnóstico.
- OpenAPI de homologação da SEFIN acessível por mTLS: https://sefin.producaorestrita.nfse.gov.br/SefinNacional/swagger/docs/v1. O contrato apresenta POST /nfse com o corpo NFSePostRequest e sem parâmetro adicional de procurador nessa operação.
- A documentação de entrada tanto em /API/SefinNacional/docs/index quanto em /SefinNacional/docs/index aponta para o mesmo contrato canônico com basePath /SefinNacional. Isso não prova diferença de comportamento entre as rotas de emissão.

## Conclusão e limites

O ADN em homologação reconheceu a consulta por procuração com o mesmo certificado e CNPJ. Não há base para atribuir a rejeição à ausência de procuração, ao formato P12 ou a uma assinatura criptograficamente inválida. A SEFIN devolveu E0718 na tentativa registrada; o reconhecimento no ADN não prova autorização de emissão na SEFIN. A causa exata dessa diferença permanece não confirmada. Não foi presumido prazo de propagação nem testada emissão em produção.

O diagnóstico não fez login interativo no Emissor Web. O teste efetuado foi pela API oficial de distribuição, de leitura. Não existe comprovação de nova autorização de emissão sem uma nova resposta da SEFIN; nenhuma transmissão foi realizada automaticamente.

Referências oficiais:

- https://www.gov.br/nfse/pt-br/noticias/nova-funcionalidade-possibilita-representacao-e-alternancia-de-perfis-na-nfs-e
- https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual/manual-municipios-apis-adn-sistema-nacional-nfs-e-v1-1-20261103.pdf
- https://adn.producaorestrita.nfse.gov.br/contribuintes/docs/index.html
- https://sefin.producaorestrita.nfse.gov.br/SefinNacional/docs/index

## Aprofundamento — 24/11/2026, 16h47

- Histórico mais recente: rejeição E0718 em 24/11/2026 às 16:45:39, versão declarada pela resposta `SefinNacional_1.6.0`.
- A consulta GET da DPS pelos caminhos `/API/SefinNacional/dps/{id}` e `/SefinNacional/dps/{id}` retornou HTTP 404 nos dois casos. Isso não demonstra que POST /nfse percorra validações idênticas, mas não apresentou indício de diferença nessas consultas.
- O CNPJ no XML coincide com o emitente do cadastro.
- A assinatura utiliza RSA-SHA256 e passou novamente na validação criptográfica.
- Os certificados locais efetivamente apresentados nas sessões TLS da SEFIN e do ADN foram obtidos por `SSLSession.getLocalCertificates()`. Ambos coincidem, byte a byte, com o certificado da assinatura da DPS rejeitada. Portanto, não houve diferença entre o certificado escolhido para a consulta mTLS e o certificado de assinatura nesse teste.
- A consulta ao ADN foi repetida às 16h47 com o mesmo resultado: `TipoConsulta=Procuracao`, `NENHUM_DOCUMENTO_LOCALIZADO`, E2220.
- Não foi encontrado parâmetro de procurador omitido no contrato POST /nfse consultado. Não se deve inventar esse campo, trocar o CNPJ do prestador pelo CPF do procurador ou mudar tpEmit para tentar contornar a validação.

O ponto isolado é a validação do vínculo do assinante pela operação de emissão da SEFIN, que diverge do reconhecimento da consulta por procuração no ADN. Uma diferença de implementação/configuração entre os serviços em homologação é hipótese compatível com as evidências, não causa comprovada. O número de versão, isoladamente, não comprova que o serviço esteja desatualizado. Não é possível determinar por esses GETs se a SEFIN consulta uma base de poderes diferente, mantém cache, aplica regra específica ao e-CPF/MEI ou usa fluxo distinto para emissão. Nenhuma dessas possibilidades foi apresentada como fato.
