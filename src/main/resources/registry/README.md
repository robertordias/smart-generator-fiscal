# Catálogo de municípios

`municipalities.json` é uma cópia local dos identificadores, nomes e UFs de 5.571 municípios retornados pela API oficial do IBGE, obtida em 11/11/2026:

https://servicodados.ibge.gov.br/api/v1/localidades/municipios?view=nivelado

Documentação: https://servicodados.ibge.gov.br/api/docs/localidades

A importação consulta esta cópia por cidade/UF, sem enviar registros do cliente a serviços externos. A normalização ignora acentos e caixa, mas não tenta adivinhar grafias diferentes. Municípios ausentes ou grafias divergentes são reportados por linha. Para atualizar, converter os campos `municipio-id`, `municipio-nome`, `UF-sigla` para `id`, `name`, `uf` (id como string) e revisar a alteração do catálogo.
