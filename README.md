# Emissor fiscal white-label — Java 21

Arquitetura de um SaaS fiscal para escritórios contábeis e base executável de API Java 21/Spring Boot. O projeto completo está especificado em [Arquitetura funcional, telas e escopo](docs/ARQUITETURA.md) e no [modelo de banco PostgreSQL](docs/modelo-postgresql.sql).

**Situação:** backend Java e frontend React/Material UI, com login de 8 horas, empresas, padrões fiscais versionados, rascunhos e conector NFS-e Nacional por A1/procuração. Implementados cofre criptografado, enquadramento, geração/assinatura/validação da DPS, transmissão mTLS, consulta, XML e cancelamento por evento. **A integração ainda não foi homologada com certificado e procuração reais.** O ambiente padrão é homologação e a produção exige habilitação explícita. Painel de autorizações, filtros, exportação contábil em ZIP e compartilhamento assistido estão disponíveis. A3, NF-e, marca personalizada e disparos automáticos continuam pendentes.

Configuração e fluxo completo: [guia da integração NFS-e](docs/INTEGRACAO-NFSE.md).

## Executar com Docker e PostgreSQL

A tela **Empresas** permite importar a relação de empresas em CSV, com prévia, validação por linha e gravação no PostgreSQL. Consulte [layout e regras de importação](docs/IMPORTACAO-EMPRESAS.md).

O sistema também possui [cadastro de tomadores, NBS e variações do mesmo serviço com e sem retenção de ISS](docs/SERVICOS-TOMADORES.md), reutilizados na preparação da nota.

Com Docker Desktop iniciado, na raiz do projeto:

```sh
cp .env.example .env
```

Edite `.env` e defina `DB_PASSWORD` e `APP_ADMIN_PASSWORD` (12+ caracteres). Depois:

```sh
docker compose up --build -d
```

Abra **http://127.0.0.1:3000**, usuário **contador**, senha definida em `APP_ADMIN_PASSWORD`. O Compose executa PostgreSQL 16, Java 21 e frontend React/Nginx. O banco é persistido em volume; reiniciar os contêineres mantém as gravações. O Flyway cria e versiona a estrutura automaticamente.

Para executar só o banco no Docker, usar a IDE, transferir os dados H2 antigos ou fazer backup: [guia PostgreSQL/Docker](docs/POSTGRESQL.md). A troca de banco não importa nem apaga automaticamente os dados H2 anteriores. Os A1 migrados precisam da mesma `NFSE_MASTER_KEY` anterior.

## Abrir a interface em desenvolvimento

Primeiro, inicie o PostgreSQL (`docker compose up -d postgres`) e configure `DB_URL`, `DB_USER` e `DB_PASSWORD` conforme o guia. Use dois terminais. No primeiro, na raiz do projeto, inicie o backend:

```sh
mvn clean verify
export APP_ADMIN_PASSWORD='MinhaSenhaForte123'
java -jar target/emissor-fiscal-0.1.0.jar
```

No segundo, também partindo da raiz:

```sh
cd frontend
npm install
npm run dev
```

Abra **http://127.0.0.1:5173**. Entre com o usuário **contador** e a senha definida em `APP_ADMIN_PASSWORD` (no exemplo, `MinhaSenhaForte123`). O frontend requer Node.js 20.19+ e npm. O backend continua na raiz, com Java 21+ e Maven; o frontend tem configuração e dependências independentes.

Fluxo de emissão: **Nova nota → Documento e destinatário → Serviços → Pagamento e emissão → Revisar emissão → Confirmar CNPJ**. A tela reúne tomador, quantidades, valores, pagamento, complementos, descontos e retenções; também permite salvar um rascunho sem transmitir. Configure A1, procuração e enquadramento em **A1 e procurações** antes de gerar a nota. O ambiente padrão continua em homologação. Consulte o [formulário de emissão](docs/FORMULARIO-EMISSAO.md) e o [painel operacional, XMLs em lote e compartilhamento](docs/OPERACAO-CONTABIL.md).

O servidor Vite encaminha `/api` ao Java em `127.0.0.1:8080`, sem precisar liberar CORS. Se o backend usar outra porta, copie `frontend/.env.example` para `frontend/.env` e ajuste `BACKEND_URL`. Para parar cada servidor, use Ctrl+C no respectivo terminal. A sessão permanece ativa por **8 horas contadas a partir do login**, inclusive ao atualizar a página ou reabrir o navegador. Usar o sistema não renova esse prazo. “Sair da conta” revoga a sessão no servidor e encerra o acesso nas outras abas. As sessões são mantidas na memória do backend; reiniciá-lo exige novo login, sem perder os registros fiscais salvos.

Detalhes de build, preview e testes: [README do frontend](frontend/README.md).

## Executar

Pré-requisitos para execução fora do Docker: JDK 21+, Maven 3.9+ e PostgreSQL configurado. Na primeira compilação, Maven precisa das dependências disponíveis em seu repositório local ou de acesso à internet.

```sh
mvn clean verify
export APP_ADMIN_PASSWORD='defina-uma-senha-forte-aqui'
java -jar target/emissor-fiscal-0.1.0.jar
```

A API estará em `http://127.0.0.1:8080/api`. A interface visual é servida separadamente pelo frontend na porta 5173; a raiz da API não possui uma página inicial. Usuário: `contador`; senha: a variável definida acima. A aplicação recusa inicialização sem senha de pelo menos 12 caracteres. O banco padrão é PostgreSQL, com conexão definida por `DB_URL`, `DB_USER` e `DB_PASSWORD`. Os dados do Docker ficam em volume persistente. O H2 anterior continua preservado em `./data/fiscal`, sem uso automático. Parar: Ctrl+C.

Configurações: `PORT`, `SERVER_ADDRESS`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_TENANT_ID`. Para habilitar o usuário `cliente`, informar `APP_CLIENT_PASSWORD` (12+ caracteres) e `APP_CLIENT_ISSUER_ID` com ID de empresa já cadastrada, e reiniciar. O contador acessa o tenant configurado; o cliente acessa somente essa empresa. Gestão dinâmica de contas/tenants ainda não existe.

O login visual usa cookie HttpOnly/SameSite com prazo absoluto de 8 horas e proteção CSRF nas alterações. Defina `APP_COOKIE_SECURE=true` quando servir por HTTPS. HTTP Basic continua disponível para clientes locais da API. A autenticação atual continua voltada à base local. PostgreSQL e migrações Flyway estão implementados. Para produção permanecem os requisitos de OIDC/TLS, usuários/vínculos persistentes e os demais controles da arquitetura. O H2 fica disponível explicitamente pelo perfil `h2`, para desenvolvimento e testes.

## Exercitar o fluxo

Com o servidor iniciado, em outro terminal, defina `APP_ADMIN_PASSWORD` com a mesma senha. Os dados abaixo são apenas exemplos cadastrais, sem validade ou recomendação tributária.

```sh
curl -u "contador:$APP_ADMIN_PASSWORD" http://127.0.0.1:8080/api/me

curl -u "contador:$APP_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"cnpj":"12345678000195","name":"Empresa de exemplo","municipality":"3550308"}' \
  http://127.0.0.1:8080/api/issuers
```

Copie o `id` retornado para `EMITENTE_ID`:

```sh
export EMITENTE_ID='id-retornado-pela-api'
curl -u "contador:$APP_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"effectiveFrom":"2026-01-01","lc116":"01.01","nationalService":"010101","cnae":"6201501","description":"Descrição padrão do serviço","issRate":5,"irrfRate":0,"pisRate":0,"cofinsRate":0,"csllRate":0,"inssRate":0,"issWithheld":false}' \
  "http://127.0.0.1:8080/api/issuers/$EMITENTE_ID/defaults"

curl -u "contador:$APP_ADMIN_PASSWORD" \
  "http://127.0.0.1:8080/api/issuers/$EMITENTE_ID/defaults?competence=2026-11-10"
```

Criar rascunho preenchendo automaticamente os dados da empresa:

```sh
curl -u "contador:$APP_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: exemplo-001' \
  -d "{\"issuerId\":\"$EMITENTE_ID\",\"competence\":\"2026-11-10\",\"amount\":1000.00}" \
  http://127.0.0.1:8080/api/drafts
```

Resposta contém `status: DRAFT`, `snapshot.defaults.version` e `descriptionOrigin: ISSUER_DEFAULT`. Uma descrição manual diferente exige `overrideReason`. Nova tentativa com a mesma chave e mesmo corpo retorna o mesmo rascunho; alteração do corpo retorna 409. A comparação considera a representação do valor decimal; repita o corpo original em retentativas.

Para modificar padrões, publique por POST uma nova configuração com outra data inicial. A maior data inicial até a competência determina a versão dentro de cada nome de variação. O mesmo código pode ser cadastrado em nomes diferentes, com ou sem retenção. Versões antigas e snapshots não são alterados. Duplicação de vigência para o mesmo nome retorna 409; ausência de política vigente retorna 422. Alíquotas não determinam por si só a aplicabilidade tributária; o fluxo de emissão exige revisão dos dados e validação oficial.

## API implementada

| Método | Caminho | Acesso |
|---|---|---|
| POST | `/api/auth/login` | Credenciais JSON; exige X-Requested-With: XMLHttpRequest; cria cookie de 8 horas |
| GET | `/api/auth/session` | Restaura usuário, prazo e proteção CSRF da sessão |
| POST | `/api/auth/logout` | Cookie e X-CSRF-Token; revoga a sessão |
| GET | `/api/me` | Autenticado |
| GET | `/api/dashboard?referenceDate=AAAA-MM-DD&issuerId=UUID` | Resumo de rascunhos; emitente opcional |
| GET | `/api/issuers` | Empresas autorizadas |
| POST | `/api/issuers` | Gestor |
| POST | `/api/issuers/{id}/defaults` | Gestor; cria versão |
| GET | `/api/issuers/{id}/defaults?competence=AAAA-MM-DD` | Empresa autorizada |
| POST | `/api/drafts` | Empresa autorizada; exige Idempotency-Key |
| GET | `/api/drafts?page=0&size=10` | Listagem paginada; filtros opcionais issuerId, from e to |
| GET | `/api/drafts/{id}` | Empresa autorizada |

Os endpoints de negócio exigem autenticação. Apenas `/api/auth/login` aceita acesso sem sessão existente. O tenant é obtido do usuário, sem confiar em tenant informado pelo cliente. Campos JSON desconhecidos são rejeitados para evitar falsa impressão de processamento de dados ainda não suportados. Os percentuais aceitam 0 a 100 com até 4 casas; isso é validação estrutural, não validação fiscal. CNPJ apenas numérico com 14 posições neste protótipo, sem validação de dígitos verificadores; suporte completo é requisito de produção.

## Verificação

```sh
mvn test
```

Testes cobrem autenticação HTTP, validação estrutural, isolamento entre tenants/empresas, restrição do cliente, vigência por competência, preservação do snapshot, justificativa de sobrescrita, idempotência, conflitos de cadastro, consultas do painel e paginação/filtros de rascunhos. Não são testes de homologação fiscal. A suíte usa H2 por padrão e também pode executar no PostgreSQL com as variáveis descritas no guia. Há teste adicional da transferência entre os dois bancos.

Estrutura: `src/main/java` contém API e autenticação; `src/main/resources/db/migration/` contém os esquemas Flyway por banco; `docs` contém o escopo de produção. Nenhuma credencial ou certificado real deve ser adicionado ao repositório. Os certificados artificiais em src/test/resources/nfse são apenas de laboratório. As APIs fiscais ficam sob /api/nfse; consulte o guia de integração para configuração e limites.
