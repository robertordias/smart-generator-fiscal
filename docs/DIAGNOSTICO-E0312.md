# Diagnóstico da rejeição E0312 — São Paulo / serviço 100104

> **Conclusão revisada em 24/11/2026:** o desdobramento `10.01.04.001` foi consultado diretamente nas APIs oficiais e está vigente na competência 17/11/2026 em homologação e produção. O XML analisado não contém `cTribMun=001`. A análise anterior limitada a `.000` não justificava encaminhar o caso à Prefeitura. As consultas anteriores foram mantidas abaixo como evidência histórica, não como conclusão atual.

## Resultado confirmado: desdobramento municipal 001

Consultas GET realizadas em 24/11/2026, com A1 cadastrado e sem transmitir ou alterar documentos:

- Homologação: `GET https://adn.producaorestrita.nfse.gov.br/parametrizacao/3550308/10.01.04.001/2026-11-17/aliquota` → HTTP 200, incidência SIM, alíquota municipal 5%, início 18/03/2025, fim não informado.
- Produção: `GET https://adn.nfse.gov.br/parametrizacao/3550308/10.01.04.001/2026-11-17/aliquota` → HTTP 200, incidência SIM, alíquota municipal 5%, início 22/12/2025, fim não informado.

Para representar esse desdobramento, o XML deve separar `cTribNac=100104` e `cTribMun=001`. No formulário existente, o campo **Código tributário municipal (3 dígitos)** deve receber `001`; não inserir o código completo de nove dígitos no campo nacional. A alíquota municipal retornada não substitui o percentual efetivo de ISS retido do optante do Simples: o XML analisado informa 3,24%, conforme cadastro do contador.

A correção deve ser usada em um novo rascunho/DPS, preservando o XML assinado anteriormente. A autorização final depende de nova validação fiscal oficial; não foi realizada transmissão neste diagnóstico. Não é necessário encaminhar ao suporte municipal com base somente na evidência anterior de `.000`.

---


Consultas realizadas em 19/11/2026. Somente leitura; nenhuma DPS transmitida, nenhum cadastro alterado e nenhuma troca do ambiente de emissão.

## Documento analisado

- Ambiente de emissão: homologação.
- Competência: 17/11/2026.
- Município do emitente e da prestação: 3550308 (São Paulo/SP).
- Código nacional no XML (`cTribNac`) e no snapshot do cadastro: 100104.
- Código municipal (`cTribMun`): ausente.
- NBS: 109102000.
- Retorno da transmissão: E0312.

A consulta de parâmetros exige a forma pontuada de nove dígitos. Para a combinação enviada, sem desdobramento municipal, foi consultado `10.01.04.000`. Esse formato é exclusivo da consulta; o XML permanece com `cTribNac=100104`.

## Evidência obtida diretamente das APIs oficiais

### Homologação — convênio

`GET https://adn.producaorestrita.nfse.gov.br/parametrizacao/3550308/convenio`

HTTP 200

```json
{
  "parametrosConvenio": {
    "aderenteAmbienteNacional": 1,
    "aderenteEmissorNacional": 1,
    "situacaoEmissaoPadraoContribuintesRFB": 1,
    "aderenteMAN": 0,
    "permiteAproveitametoDeCreditos": true
  },
  "mensagem": "Parâmetros do convênio recuperados com sucesso."
}
```

### Homologação — competência

`GET https://adn.producaorestrita.nfse.gov.br/parametrizacao/3550308/10.01.04.000/2026-11-17/aliquota`

HTTP 404

```json
{
  "aliquotas": null,
  "mensagem": "Alíquotas não encontradas."
}
```

### Homologação — histórico

`GET https://adn.producaorestrita.nfse.gov.br/parametrizacao/3550308/10.01.04.000/historicoaliquotas`

HTTP 200

```json
{
  "aliquotas": {
    "10.01.04.000": [
      {
        "Incidencia": "SIM",
        "Aliq": 5.0,
        "DtIni": "2025-03-17T00:00:00",
        "DtFim": "2025-03-17T00:00:00"
      }
    ]
  },
  "mensagem": "Histórico de alíquotas recuperadas com sucesso."
}
```

### Produção — competência

`GET https://adn.nfse.gov.br/parametrizacao/3550308/10.01.04.000/2026-11-17/aliquota`

HTTP 404

```json
{
  "aliquotas": null,
  "mensagem": "Alíquotas não encontradas."
}
```

### Produção — histórico

`GET https://adn.nfse.gov.br/parametrizacao/3550308/10.01.04.000/historicoaliquotas`

HTTP 404

```json
{
  "aliquotas": null,
  "mensagem": "Histórico de alíquotas não encontrado"
}
```

## Conclusão e limite

Na consulta de homologação, o único período retornado para `10.01.04.000` tem início e fim em 17/03/2025, com alíquota de 5%. Não há alíquota vigente retornada para a competência 17/11/2026. Isso é consistente com a rejeição E0312 observada e não é corrigido alterando a alíquota no formulário.

Em produção, a consulta desta mesma combinação sem código municipal também não localizou alíquota nem histórico. Portanto, não há evidência para afirmar que simplesmente mudar o ambiente resolveria.

Não foi comprovado que o município não tenha outros desdobramentos municipais para o serviço nacional. O contador ou o suporte municipal deve confirmar o código completo vigente, inclusive eventual `cTribMun` de três dígitos, e a parametrização da competência. Se o código completo correto for o próprio `10.01.04.000`, o município/suporte nacional precisa revisar sua vigência no ambiente correspondente.

## Referências

- [Lista nacional de serviços](https://www.gov.br/nfse/pt-br/mei-e-demais-empresas/codigos-de-tributacao-nacional-nbs)
- [APIs oficiais dos ambientes](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/apis-prod-restrita-e-producao)
- [Contrato consultado da API de parâmetros de homologação](https://adn.producaorestrita.nfse.gov.br/parametrizacao/swagger/v1/swagger.json)
- [Comunicado de migração do Simples Nacional em São Paulo](https://notadomilhao.sf.prefeitura.sp.gov.br/noticias/migracao-de-contribuintes-do-simples-nacional-para-o-ambiente-nacional/)


## Conferência do Simples Nacional e da alíquota enviada

Conferência adicional em 19/11/2026, diretamente no XML assinado da DPS rejeitada:

| Campo | Valor enviado | Leitura |
|---|---|---|
| `opSimpNac` | `3` | ME/EPP optante do Simples Nacional |
| `regApTribSN` | `1` | Tributos federais e ISS apurados pelo Simples |
| `regEspTrib` | `0` | Sem regime especial |
| `tpRetISSQN` | `2` | Retenção pelo tomador |
| `pAliq` | `3.24` | ISS informado de 3,24% |
| Percentual no snapshot | `3.24` | Mesmo percentual utilizado no XML |

O sistema não substituiu a alíquota de 3,24% pela alíquota municipal de 5% encontrada na consulta de parâmetros. A correção do percentual efetivo de 3,24% depende da apuração do contribuinte pelo contador e não foi recalculada neste diagnóstico.

A ausência de `cTribMun` não comprova a causa da rejeição. As regras nacionais distinguem E0312 (administração do código nacional na competência) de E0314 (existência/administração de código municipal informado). Não foi identificado, nas evidências consultadas, um código municipal específico que deva substituir ou complementar o cadastro. Não acrescentar `000` à DPS: esse sufixo foi usado somente no identificador da consulta de parâmetros.

A evidência confirmada continua sendo a ausência de vigência retornada para `10.01.04.000` em São Paulo, na competência rejeitada, nos ambientes consultados. Para esclarecer eventual desdobramento ou ausência de parametrização, encaminhar os retornos deste relatório ao suporte oficial/municipal, mantendo os dados fiscais definidos pelo contador.

Referência das regras: [Tabela de validação disponibilizada em portal municipal](https://certidoes.ji-parana.ro.gov.br/issweb/temp/ANEXO_~1.pdf). Referência de retenção no Simples: [Resolução CGSN nº 140/2018](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?idAto=92278).
