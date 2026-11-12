# Certificados exclusivamente de teste

`test-a1.base64` é um PKCS#12 artificial, com chave privada e CNPJ fictício de teste, assinado por uma CA local. `test-truststore.base64` contém somente essa CA. Senha dos dois: `test-only-password`. Não são ICP-Brasil e não são aceitos pelo Fisco. Servem para testar criptografia, identificação SAN, validação PKIX, XMLDSig e o contrato de emissão com gateway controlado, sem credenciais reais. Nunca usar essa âncora em produção.
