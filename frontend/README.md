# Frontend — SA Trindade

Interface React 19, Material UI 7 e Vite, com branco, cinza e dourado. Projeto independente do backend Java, que permanece na raiz do repositório.

Prévias verificadas com dados de teste: [painel desktop](../docs/previews/dashboard-desktop.png), [painel mobile](../docs/previews/dashboard-mobile.png) e [login mobile](../docs/previews/login-mobile.png).

Prévia do novo fluxo: [formulário de emissão em três etapas](../docs/previews/invoice-video-payment.png).

## Docker

O frontend também pode rodar junto com Java e PostgreSQL pelo Compose na raiz do repositório. Configure o `.env` da raiz e execute `docker compose up --build -d`. Acesse **http://127.0.0.1:3000**. Nessa modalidade o Nginx encaminha `/api` ao backend. Consulte [PostgreSQL/Docker](../docs/POSTGRESQL.md).

## Iniciar com Vite

Pré-requisitos: Node.js 20.19+ e backend Java em execução na porta 8080.

```sh
cd frontend
npm install
npm run dev
```

Abra **http://127.0.0.1:5173**. Usuário `contador`; senha definida em `APP_ADMIN_PASSWORD` no backend (no `.env` da raiz ao usar Docker). O usuário `cliente` também é compatível quando configurado no backend: ele consulta sua empresa e seus padrões e prepara rascunhos, mas não cadastra empresas nem publica configurações.

Se necessário, copie `.env.example` para `.env` e altere `BACKEND_URL`. Essa variável configura o proxy do Vite no servidor; nunca coloque senhas ou certificados em variáveis `VITE_*`.

## Telas

- Login: autenticação real por `/api/auth/login` e restauração por `/api/auth/session`, erro de credenciais e opção mostrar/ocultar senha.
- Visão geral: faturamento diário/mensal e quantidade de notas válidas autorizadas, pendências e certificados, com filtros de data, emitente e ambiente. Rascunhos não contam como faturamento.
- Empresas: listagem, busca, cadastro e importação CSV com prévia e correção de campos inválidos em modal.
- Tomadores: cadastro, edição e vínculo do mesmo tomador a vários prestadores, reutilizado na emissão. O contador gerencia os vínculos; clientes acessam apenas sua empresa.
- Contábil · XML: prévia e exportação em lote dos XMLs oficiais e eventos em ZIP.
- Padrões fiscais: consulta por emitente/competência e criação de novas versões com LC 116, tributação nacional, CNAE, descrição, percentuais e ISS retido. Cliente tem acesso de consulta.
- Nova nota: formulário em três etapas (documento/destinatário, serviços, pagamento/emissão), com quantidades, unitários, descontos, retenções e resumo. Rascunho persistente e transmissão após confirmação do CNPJ. Veja [campos e regras](../docs/FORMULARIO-EMISSAO.md).
- Rascunhos: consulta paginada, empresa ativa, filtro por competência e detalhes do snapshot histórico.
- A1 e procurações: importação protegida do A1 do representante, vínculo por cliente, poderes e enquadramento/numeração.
- Notas fiscais: preparação de DPS, revisão, confirmação de transmissão, consulta/reconciliação, XML e pedido de cancelamento com histórico oficial.

Navegação responsiva com menu lateral recolhido em celular, campos com labels e estados de carregamento, vazio e erro. Não há dados de demonstração inseridos automaticamente na base do usuário.

A sessão permanece ativa por **8 horas desde o login**, mesmo após atualizar a página, fechar a aba ou reabrir o navegador com o mesmo perfil e endereço de acesso. O prazo não é renovado pelo uso. A senha é enviada somente no login e não é guardada pelo frontend; a autenticação usa um cookie HttpOnly/SameSite=Strict, inacessível ao JavaScript, com Max-Age de 28.800 segundos. O frontend restaura o usuário por `/api/auth/session`, envia o token CSRF nas alterações e volta ao login ao expirar. “Sair da conta” revoga a sessão no backend e avisa as outras abas. As sessões ficam na memória do servidor: reiniciar o backend ou apagar os cookies exige novo login. Os cadastros e rascunhos permanecem no banco. Para HTTPS, definir `APP_COOKIE_SECURE=true` no backend. Não alternar entre localhost e 127.0.0.1, pois são origens diferentes.

## Build e preview

```sh
npm run build
npm run preview
```

Preview em **http://127.0.0.1:4173**, com o mesmo proxy de API. `dist/` contém o build estático. Para hospedar em outro servidor, configurar fallback de rotas para `index.html` e reverse proxy de `/api` para o Java. O proxy do Vite não faz parte dos arquivos estáticos. A aplicação é local nesta entrega, sem publicação em serviço externo.

## Testes de ponta a ponta

Na raiz, gere o JAR com `mvn verify`. Depois, nesta pasta:

```sh
npm run test:e2e
```

Playwright inicia backend isolado na porta 18081, com H2 em memória, e Vite na porta 5174. Não usa o banco `data/fiscal` nem precisa da senha real do escritório. Essas duas portas precisam estar livres. No macOS usa Google Chrome se instalado; em outro ambiente instale o Chromium do Playwright (`npx playwright install chromium`) ou informe o executável em `BROWSER_PATH`.

Os testes usam também A1 e âncora artificiais, restritos ao banco de laboratório. Não transmitem ao Fisco. Verificam importação do A1, procuração, enquadramento, geração/assinatura da DPS, login inválido/válido, cadastro real de empresa e padrões, preenchimento automático, gravação de rascunho, preservação do snapshot após nova versão, filtro de competência, persistência após recarga e em outra aba, cookie HttpOnly de 8 horas, logout com revogação, retorno automático ao login no prazo e navegação mobile. O teste do prazo no navegador avança o relógio JavaScript; os testes Java também comprovam a expiração no servidor usando um relógio controlado. Screenshots ficam em `test-results/` e traces são mantidos em caso de falha.

Estrutura: `src/api.js` centraliza chamadas e mensagens de erro; `context.jsx` controla autenticação e recursos; `theme.js` configura Material UI; `components` reúne elementos compartilhados; `pages` reúne as telas; `styles.css` controla os layouts responsivos.

Referências usadas: [instalação oficial do Material UI](https://mui.com/material-ui/getting-started/installation/) e [proxy de desenvolvimento do Vite](https://vite.dev/config/server-options#server-proxy).

## Limites

A integração NFS-e foi implementada, mas exige configuração do cofre, truststore ICP-Brasil, A1 e procuração válidos e homologação oficial antes da produção. Não há credenciais reais incluídas. O DANFSe é acessado pelo portal oficial, pois sua antiga API foi descontinuada. Cenários fiscais especiais usam complementos oficiais ou DPS completa não assinada; não há motor tributário universal. O cadastro básico ainda aceita CNPJ numérico; o fluxo de geração fiscal verifica os dígitos de CPF/CNPJ. A3 e NF-e de produtos permanecem pendentes. O compartilhamento é assistido por WhatsApp/e-mail com confirmação no aplicativo externo; disparos automáticos permanecem pendentes. Consulte [o guia fiscal](../docs/INTEGRACAO-NFSE.md).

Para verificar o build estático em vez do servidor de desenvolvimento, execute `npm run build` e depois `E2E_PREVIEW=true npm run test:e2e`. O teste continua usando backend e banco isolados.
