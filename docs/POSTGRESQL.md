# PostgreSQL e Docker — SA Trindade

O PostgreSQL 16 é o banco padrão. Empresas, configurações fiscais, rascunhos, procurações, numeração DPS, documentos, XMLs, auditoria e os A1 criptografados ficam no banco. O frontend continua separado em `frontend/`.

## Subir o sistema completo

Pré-requisito: Docker Desktop iniciado, com Docker Compose. Na raiz do projeto:

```sh
cp .env.example .env
```

Edite `.env`: defina `DB_PASSWORD` (senha do PostgreSQL) e `APP_ADMIN_PASSWORD` (senha do usuário `contador`, com 12 caracteres ou mais). Não são a mesma credencial. O arquivo `.env` fica fora do Git e do contexto das imagens Docker.

```sh
docker compose up --build -d
docker compose ps
```

Abra **http://127.0.0.1:3000** e entre com `contador` e `APP_ADMIN_PASSWORD`.

| Serviço | Endereço local padrão |
|---|---|
| Frontend React/Nginx | `http://127.0.0.1:3000` |
| Backend Java 21 | `http://127.0.0.1:8080/api` |
| Saúde do backend e conexão com o banco | `http://127.0.0.1:8080/health` |
| PostgreSQL | `127.0.0.1:5432`, banco e usuário `fiscal` |

As portas são publicadas somente na interface local. Se uma estiver ocupada, ajuste `FRONTEND_PORT`, `BACKEND_PORT` ou `DB_PORT` no `.env`. O frontend dentro do Docker usa proxy para `backend:8080`; não é necessário abrir CORS.

```sh
docker compose logs -f backend
docker compose stop
docker compose start
```

O volume nomeado `postgres_data` preserva as gravações ao reiniciar ou recriar os contêineres. `docker compose down` remove contêineres/rede e preserva o volume; **`docker compose down -v` apaga o volume e os dados**. O nome real do volume normalmente é `sa-trindade_postgres_data`; mudar o nome do projeto Compose cria outro volume, sem mover os dados anteriores.

Os usuários do aplicativo ainda são configurados pelo ambiente. `APP_CLIENT_PASSWORD` e `APP_CLIENT_ISSUER_ID` habilitam opcionalmente o usuário `cliente`. As sessões de oito horas continuam em memória do backend e são encerradas ao reiniciá-lo; os registros fiscais permanecem no PostgreSQL.

## Executar somente o banco no Docker

Para continuar usando Java/IDE e Vite na máquina:

```sh
docker compose up -d postgres
```

O backend carrega automaticamente o `.env` da pasta de trabalho ao executar pela IDE, Maven ou JAR. Configure a pasta de trabalho da IDE como a raiz deste projeto. Use `CHAVE=valor`, sem aspas nem `export`; por ser lido também como Java Properties, barras invertidas precisam ser duplicadas. Variáveis do ambiente e argumentos de execução têm prioridade sobre o arquivo.

Também é possível configurar no terminal ou na configuração de execução da IDE:

```text
DB_URL=jdbc:postgresql://127.0.0.1:5432/fiscal
DB_USER=fiscal
DB_PASSWORD=a-mesma-senha-definida-no-env
APP_ADMIN_PASSWORD=sua-senha-de-acesso-com-12-caracteres
```

Sem `DB_URL`, a conexão local usa `DB_PORT` e `DB_NAME` do `.env`. Se definir `DB_URL` explicitamente, ajuste a porta nela. O backend em Docker usa o endereço interno `postgres:5432`, e não `127.0.0.1`.

Se aparecer `The server requested SCRAM-based authentication, but no password was provided`, confira se `DB_PASSWORD` está preenchido no `.env`, se a pasta de trabalho é a raiz do projeto e se a IDE não define `DB_PASSWORD` como vazio. A senha deve ser a mesma do usuário já criado no PostgreSQL; editar `.env` não altera a senha de um volume existente.

```sh
mvn verify
java -jar target/emissor-fiscal-0.1.0.jar
```

No outro terminal, `cd frontend` e `npm run dev`. Nessa modalidade a interface continua em `http://127.0.0.1:5173`.

## Migrações de estrutura

O Flyway executa e valida migrações ao iniciar o backend. Não é necessário criar tabelas manualmente. A versão inicial de PostgreSQL está em:

```text
src/main/resources/db/migration/postgresql/V1__initial_schema.sql
```

XML/snapshots usam `TEXT`; arquivos e senhas criptografados usam `BYTEA`; valores usam `NUMERIC`; identificadores usam `UUID`; instantes usam `TIMESTAMP WITH TIME ZONE`. A tabela `flyway_schema_history` registra as migrações. V2 acrescenta os dados cadastrais importados; V3, compartilhada em `src/main/java/db/common/`, inclui nomes de serviços, NBS e tomadores, preservando os registros anteriores. O Flyway carrega os scripts específicos do banco e as migrações Java compartilhadas. V4 acrescenta a primeira confirmação de autorização e índices do painel/filtros. Novas alterações devem usar novas versões (V5 em diante), sem editar migrações já aplicadas.

Não há `clean` automático nem `baseline-on-migrate` habilitado por padrão. Um banco preexistente com estrutura desconhecida não é adotado silenciosamente. `docs/modelo-postgresql.sql` continua sendo uma referência de arquitetura de produção, não o script executado pela aplicação.

As gravações que substituíam `MERGE ... KEY` usam SQL portável de atualização/inserção, dentro de transação e com bloqueio da empresa. O mesmo código funciona no PostgreSQL e nos testes H2, preservando a proteção da numeração DPS contra concorrência.

## Certificados A1 no Docker

O A1 importado pela interface fica criptografado no PostgreSQL; não fica solto dentro da imagem. Mantenha `NFSE_MASTER_KEY` estável e protegida. **Copiar/restaurar o banco não substitui a cópia protegida dessa chave.** Para reutilizar A1 migrados do H2, configure exatamente a chave mestra usada antes.

Coloque apenas o truststore de âncoras públicas ICP-Brasil em `./secrets` e configure, por exemplo:

```text
NFSE_ICP_TRUSTSTORE=/run/fiscal-secrets/icp-truststore.p12
NFSE_ICP_TRUSTSTORE_PASSWORD=senha-do-truststore
```

O diretório é montado como somente leitura. O backend roda com UID 10001; esse usuário precisa de acesso de leitura ao truststore. Não use permissões públicas para arquivos contendo chaves privadas. Importação do A1, procurações e habilitação de produção seguem [o guia fiscal](INTEGRACAO-NFSE.md). Sem configurar o cofre, o sistema cadastral funciona, mas importação e emissão ficam bloqueadas.

## Transferir os dados do H2 antigo

A mudança para PostgreSQL não importa dados automaticamente e não altera o arquivo H2 antigo. Há um comando específico, que aceita somente destino PostgreSQL com as tabelas de negócio vazias.

1. Pare o backend antigo e qualquer outro processo que esteja usando o H2. Preserve uma cópia de `data/fiscal.mv.db` e a chave mestra fiscal.
2. Inicie somente o PostgreSQL: `docker compose up -d postgres`. Se já subiu a aplicação completa, pare `backend` e `frontend` durante a transferência.
3. Gere o JAR com `mvn verify` e configure `DB_URL`, `DB_USER` e `DB_PASSWORD` no terminal, apontando para o PostgreSQL de destino.
4. Execute, na raiz do projeto:

```sh
java -jar target/emissor-fiscal-0.1.0.jar --migrate-h2
```

Origem padrão: `./data/fiscal` (sem a extensão `.mv.db`), usuário H2 `sa`, senha vazia. Para outra origem, use `H2_SOURCE_FILE`, `H2_SOURCE_USER` e `H2_SOURCE_PASSWORD`. O modo de transferência não inicia o servidor nem exige `APP_ADMIN_PASSWORD`.

A origem é aberta com `ACCESS_MODE_DATA=r` e `IFEXISTS=TRUE`. O comando aplica o esquema Flyway do PostgreSQL, recusa destino com qualquer registro de negócio e copia as tabelas na ordem de dependência, em uma única transação. Preserva IDs, vínculos, valores, UTF-8, XMLs, instantes e bytes criptografados, sem descriptografar certificados. Um erro desfaz todas as inserções. A tabela Flyway pode permanecer criada, mas nenhum dado parcial de negócio é confirmado.

O comando imprime somente contagens por tabela. Ele não mescla bancos, não sobrescreve registros e não remove a origem. Bancos H2 antigos que ainda não tinham todas as tabelas fiscais são aceitos. Após conferir as contagens, iniciar a aplicação com PostgreSQL e manter o H2 preservado até validar os dados importados.

Para abrir explicitamente o H2 em desenvolvimento offline, use `--spring.profiles.active=h2`. Para um H2 legado ainda sem histórico Flyway, a adoção explícita é:

```sh
java -jar target/emissor-fiscal-0.1.0.jar --spring.profiles.active=h2 \
  --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=0
```

Não use essa adoção como substituto da transferência para PostgreSQL. Ao usar H2, deixe `DB_URL`, `DB_USER` e `DB_PASSWORD` adequados à origem H2, sem os valores do PostgreSQL.

## Backup e restauração

Crie um dump sem precisar informar a senha na linha de comando:

```sh
mkdir -p backups
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > backups/fiscal.dump
```

Guarde o dump e uma cópia protegida da chave `NFSE_MASTER_KEY`. Para restaurar e verificar em outro banco, com o usuário padrão:

```sh
docker compose exec -T postgres createdb -U fiscal fiscal_restaurado
docker compose exec -T postgres pg_restore -U fiscal -d fiscal_restaurado --no-owner --no-privileges < backups/fiscal.dump
```

Mudar `DB_PASSWORD` no `.env` não muda automaticamente a senha de um usuário PostgreSQL de um volume já inicializado. Para alterá-la, use `psql` no contêiner e o comando interativo `\password fiscal`, depois atualize `.env` e recrie o backend. Não apague o volume para trocar uma senha.

## Verificação

`mvn verify` executa testes rápidos com H2 e Flyway. Para a mesma suíte no PostgreSQL real, use exclusivamente um banco de teste e configure:

```text
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:15432/fiscal_test
TEST_POSTGRES_USER=fiscal
TEST_POSTGRES_PASSWORD=senha-do-banco-de-teste
TEST_POSTGRES_ALLOW_WRITES=true
```

Os testes criam um schema exclusivo por execução, sem apagar dados existentes. O teste de transferência é adicional e exige `TEST_POSTGRES_MIGRATION_URL` apontando para **outro banco de teste vazio**. Ele comprova rollback, recusa de sobrescrita e preservação de texto, XML, valores, horários e certificados binários. Não configure essas variáveis para um banco de operação.
