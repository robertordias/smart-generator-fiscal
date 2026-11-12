# Schemas oficiais NFS-e

Arquivos de produção: pacote `nfse-esquemas_xsd-v1-01-20260209.zip` do Portal Nacional. Arquivos em `homologacao/`: pacote `esquemas_prod_restrita_20261020.zip` do ambiente de testes. Os originais são preservados, incluindo avisos de licença do schema W3C XMLDSig.

Fontes: https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual e https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/producao-restrita.

O resolver Java carrega somente os arquivos incluídos no classpath. A DTD externa declarada pelo schema XMLDSig não é buscada; sua resolução retorna conteúdo vazio e preserva as declarações internas. O schema de produção traz um padrão de série `^0{0,4}\d{1,5}$`, com âncoras que o Xerces interpreta como caracteres literais em regex XSD 1.0. Somente esse padrão é normalizado em memória para `0{0,4}\d{1,5}`, conservando o limite de cinco caracteres e a regra pretendida. O pacote oficial não é reescrito. O schema mais recente de homologação já utiliza uma expressão compatível com XSD.
