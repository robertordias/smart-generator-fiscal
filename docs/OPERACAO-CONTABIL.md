# Painel operacional e rotina contábil

As melhorias desta etapa foram orientadas pela apresentação pública do [Emitte Contábil](https://emitte.com.br/contabil/): painel unificado, acompanhamento de notas, alertas de certificados e acesso centralizado aos XMLs. O SA Trindade mantém sua própria identidade e o foco atual em NFS-e Nacional.

## Visão geral

O painel apresenta faturamento bruto diário e mensal, quantidade de NFS-e autorizadas, emissões pendentes, rejeitadas e canceladas, junto das contagens de empresas e rascunhos. O usuário escolhe a data de referência e o ambiente; **Produção** é o padrão, e os totais de homologação são identificados como testes.

Os totais financeiros consideram documentos autorizados com situação atual válida. Rascunhos, DPS apenas assinadas, rejeições e cancelamentos confirmados não somam faturamento. Um pedido de cancelamento pendente não remove a nota do total até a aceitação do evento oficial. Os valores são brutos, sem representar recebimentos financeiros ou valores líquidos após retenções.

A nova coluna `authorized_at` registra a primeira confirmação de autorização recebida e validada pelo sistema. Consultar uma nota não altera essa data. Dia/mês usam o horário de São Paulo; a data de referência escolhe o agrupamento, e a situação é a atual. Para XMLs anteriores à atualização sem esse histórico, a migração usa a data original de criação do documento como aproximação. O importador H2 também aplica essa compatibilidade.

Alertas mostram certificados A1 ativos vencidos ou com vencimento nos próximos 30 dias. Ao selecionar uma empresa, apenas certificados vinculados por procuração a ela entram na contagem. Clientes não recebem informações de outras empresas.

## Pesquisa de notas

**Notas fiscais** possui filtros no servidor por situação, ambiente, competência inicial/final e busca por empresa, CNPJ com ou sem pontuação, número de DPS, número oficial ou chave. A paginação e o total respeitam esses filtros e o escopo do usuário. **Limpar filtros** restaura a consulta.

## Contábil · XML

Área exclusiva do gestor para selecionar empresa ou toda a carteira, ambiente e período de competência. A tela informa quantos documentos e cancelamentos estão incluídos antes de baixar o ZIP.

- Entram apenas XMLs oficiais de notas autorizadas e seus eventos de cancelamento disponíveis, inclusive notas já canceladas.
- Rascunhos, DPS sem autorização, A1, senhas e registros de outro escritório ficam fora.
- Os arquivos são organizados por CNPJ/competência, com nomes baseados no UUID do documento.
- `manifesto.csv` relaciona cada arquivo com ambiente, situação atual, valor e checksum SHA-256. Um evento gera sua própria linha; a soma dessa coluna no manifesto não representa faturamento, pois o valor da nota pode aparecer novamente na linha do evento.
- Cada pacote aceita até 500 notas e 50 MB de conteúdo XML; o período deve ter no máximo 367 dias. O servidor recusa pacotes acima dos limites antes de entregar o download. Reduza o período ou selecione uma empresa.
- A geração usa leitura transacional e não faz consultas nem transmissões ao Fisco.

O valor mostrado no resumo inclui notas canceladas e é identificado como valor dos documentos, não faturamento.

## Compartilhamento

Na listagem, **Compartilhar** fica disponível para notas que possuem chave e autorização oficial, inclusive canceladas. O servidor confirma a disponibilidade do XML antes de informar o contato do tomador e a URL oficial.

O modal permite abrir WhatsApp/e-mail com a mensagem e o link da consulta prontos, copiar a mensagem, baixar XML e usar o compartilhamento de arquivos do aparelho quando disponível. O usuário confirma o envio no aplicativo de destino. Mensagens identificam homologação e notas canceladas.

O DANFSe é obtido na consulta oficial. Links de WhatsApp e `mailto:` não anexam arquivos automaticamente: para isso, use o compartilhamento nativo do XML ou anexe manualmente os arquivos baixados. Não há serviço de disparo automático de e-mail/WhatsApp, confirmação de entrega ou PDF fiscal gerado localmente nesta etapa.

## API e atualização

- `GET /api/office/dashboard?referenceDate=AAAA-MM-DD&environment=PRODUCAO&issuerId=...`
- `GET /api/office/xml/summary?from=AAAA-MM-DD&to=AAAA-MM-DD&environment=PRODUCAO&issuerId=...`
- `GET /api/office/xml/export?from=AAAA-MM-DD&to=AAAA-MM-DD&environment=PRODUCAO&issuerId=...`
- `GET /api/nfse/notes`: novos parâmetros `status`, `environment`, `from`, `to`, `search`.
- `GET /api/nfse/notes/{id}/sharing`

`issuerId` é opcional; o servidor aplica os vínculos da identidade autenticada. Os endpoints XML exigem gestor mesmo quando acessados diretamente. A migração Flyway V4 cria `authorized_at` e índices de consulta em PostgreSQL e H2. Reinicie o backend atualizado ou execute `docker compose up --build -d` para carregar a atualização completa.

Os testes usam certificados e XMLs de laboratório e gateway controlado; não representam homologação com um contribuinte real. Emissão de NF-e de produtos, A3, gestão dinâmica de usuários, personalização de logomarca e automações de entrega continuam sendo frentes separadas do projeto.
