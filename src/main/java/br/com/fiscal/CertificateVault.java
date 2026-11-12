package br.com.fiscal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static br.com.fiscal.NfseSettings.bad;

@Service
class CertificateVault {
    record Metadata(UUID id, String label, String holderTaxId, String fingerprint, OffsetDateTime expiresAt, String status) {}
    record Material(PrivateKey privateKey, X509Certificate certificate, KeyStore store, char[] password) implements AutoCloseable {
        public void close() { Arrays.fill(password,'\0'); }
        SSLContext sslContext() throws GeneralSecurityException {
            var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store,password);
            var ssl=SSLContext.getInstance("TLS"); ssl.init(keys.getKeyManagers(),null,new SecureRandom()); return ssl;
        }
    }
    private final JdbcTemplate db;
    private final NfseSettings settings;
    private final SecureRandom random=new SecureRandom();
    CertificateVault(JdbcTemplate db,NfseSettings settings) { this.db=db; this.settings=settings; }

    List<Metadata> list(Security.Account a) {
        manager(a);
        return db.query("SELECT * FROM fiscal_certificate WHERE tenant_id=? ORDER BY created_at DESC",
            (r,n)->new Metadata(r.getObject("id",UUID.class),r.getString("label"),r.getString("holder_tax_id"),r.getString("fingerprint"),r.getObject("expires_at",OffsetDateTime.class),r.getString("status")),a.tenant());
    }
    Metadata save(Security.Account a,String label,String holder,byte[] bytes,char[] password) {
        manager(a);
        if (bytes.length>1024*1024) throw bad("Certificado excede o limite de 1 MB");
        try (var material=open(bytes,password)) {
            verify(material,holder);
            UUID id=UUID.randomUUID();
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.certificate().getEncoded()));
            var expires=material.certificate().getNotAfter().toInstant().atOffset(ZoneOffset.UTC);
            byte[] encrypted=encrypt(bytes,a.tenant()+":"+id+":pfx");
            byte[] secret=new String(password).getBytes(StandardCharsets.UTF_8);
            try {
                db.update("INSERT INTO fiscal_certificate(id,tenant_id,label,holder_tax_id,fingerprint,expires_at,encrypted_pfx,encrypted_password,status) VALUES(?,?,?,?,?,?,?,?,?)",
                    id,a.tenant(),label,holder,fingerprint,expires,encrypted,encrypt(secret,a.tenant()+":"+id+":password"),"ACTIVE");
            } finally { Arrays.fill(secret,(byte)0); }
            return new Metadata(id,label,holder,fingerprint,expires,"ACTIVE");
        } catch (org.springframework.web.server.ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw bad("Não foi possível importar o A1. Confira o arquivo PKCS#12 e a senha"); }
        finally { Arrays.fill(password,'\0'); Arrays.fill(bytes,(byte)0); }
    }
    Material load(UUID tenant,UUID id) {
        var rows=db.queryForList("SELECT * FROM fiscal_certificate WHERE tenant_id=? AND id=? AND status='ACTIVE'",tenant,id);
        if (rows.isEmpty()) throw bad("Certificado não encontrado, revogado ou sem acesso");
        var row=rows.getFirst();
        byte[] bytes=decrypt((byte[])row.get("ENCRYPTED_PFX"),tenant+":"+id+":pfx");
        byte[] secret=decrypt((byte[])row.get("ENCRYPTED_PASSWORD"),tenant+":"+id+":password");
        char[] password=new String(secret,StandardCharsets.UTF_8).toCharArray();
        try {
            Material material=open(bytes,password);
            try { verify(material,(String)row.get("HOLDER_TAX_ID")); return material; }
            catch (Exception ex) { material.close(); throw ex; }
        } catch (org.springframework.web.server.ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw bad("Não foi possível abrir ou validar o A1 cadastrado"); }
        finally { Arrays.fill(bytes,(byte)0); Arrays.fill(secret,(byte)0); Arrays.fill(password,'\0'); }
    }
    void revoke(Security.Account a,UUID id) {
        manager(a);
        if (db.update("UPDATE fiscal_certificate SET status='REVOKED' WHERE tenant_id=? AND id=?",a.tenant(),id)==0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
    static void manager(Security.Account a) {
        if (!a.role().equals("GESTOR")) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Exige perfil gestor/contador");
    }
    private Material open(byte[] bytes,char[] password) throws Exception {
        var store=KeyStore.getInstance("PKCS12"); store.load(new ByteArrayInputStream(bytes),password);
        List<String> aliases=Collections.list(store.aliases()).stream().filter(alias->{try{return store.isKeyEntry(alias);}catch(Exception e){return false;}}).toList();
        if (aliases.size()!=1) throw bad("O PKCS#12 deve conter exatamente uma chave privada");
        var key=store.getKey(aliases.getFirst(),password);
        if (!(key instanceof PrivateKey privateKey) || !(store.getCertificate(aliases.getFirst()) instanceof X509Certificate certificate)) throw bad("A1 inválido");
        return new Material(privateKey,certificate,store,password.clone());
    }
    private void verify(Material material,String holder) throws Exception {
        var cert=material.certificate(); cert.checkValidity(Date.from(settings.clock.instant()));
        if (cert.getBasicConstraints()>=0 || !"RSA".equals(cert.getPublicKey().getAlgorithm()) || ((java.security.interfaces.RSAKey)cert.getPublicKey()).getModulus().bitLength()<2048)
            throw bad("O A1 deve ser de usuário final, com chave RSA de pelo menos 2048 bits");
        if (cert.getKeyUsage()!=null && !cert.getKeyUsage()[0]) throw bad("Certificado não permite assinatura digital");
        if (cert.getExtendedKeyUsage()!=null && !cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) throw bad("Certificado não permite autenticação cliente");
        if (!taxIds(cert).contains(holder)) throw bad("CPF/CNPJ informado não corresponde à identidade ICP-Brasil do A1");
        String trustPath=settings.env.getProperty("NFSE_ICP_TRUSTSTORE");
        if (trustPath==null || trustPath.isBlank()) throw bad("Configure NFSE_ICP_TRUSTSTORE com as âncoras ICP-Brasil confiáveis");
        char[] trustPassword=settings.env.getProperty("NFSE_ICP_TRUSTSTORE_PASSWORD","").toCharArray();
        var trust=KeyStore.getInstance("PKCS12");
        try (var input=Files.newInputStream(Path.of(trustPath))) { trust.load(input,trustPassword); }
        finally { Arrays.fill(trustPassword,'\0'); }
        var selector=new X509CertSelector(); selector.setCertificate(cert);
        var params=new PKIXBuilderParameters(trust,selector); params.setDate(Date.from(settings.clock.instant()));
        params.setRevocationEnabled(settings.env.getProperty("NFSE_CHECK_REVOCATION",Boolean.class,true));
        var chain=Arrays.asList(material.store().getCertificateChain(Collections.list(material.store().aliases()).stream().filter(alias->{try{return material.store().isKeyEntry(alias);}catch(Exception e){return false;}}).findFirst().orElseThrow()));
        params.addCertStore(CertStore.getInstance("Collection",new CollectionCertStoreParameters(chain)));
        try { CertPathBuilder.getInstance("PKIX").build(params); }
        catch (GeneralSecurityException ex) { throw bad("Falha ao validar cadeia ICP-Brasil, validade ou revogação do certificado"); }
        byte[] challenge=new byte[32]; random.nextBytes(challenge);
        var signature=java.security.Signature.getInstance("SHA256withRSA"); signature.initSign(material.privateKey()); signature.update(challenge); byte[] signed=signature.sign();
        signature.initVerify(cert); signature.update(challenge);
        if (!signature.verify(signed)) throw bad("Chave privada incompatível com o certificado");
    }
    static Set<String> taxIds(X509Certificate cert) throws CertificateParsingException {
        Set<String> result=new HashSet<>();
        if (cert.getSubjectAlternativeNames()==null) return result;
        for (var san:cert.getSubjectAlternativeNames()) {
            if (!Integer.valueOf(0).equals(san.getFirst()) || !(san.get(1) instanceof byte[] bytes)) continue;
            try {
                var nodes=der(bytes); String oid=nodes.stream().filter(n->n.tag==6).map(n->HexFormat.of().formatHex(n.bytes)).findFirst().orElse("");
                for (var node:nodes) {
                    if (!Set.of(4,12,19,22).contains(node.tag)) continue;
                    String text=new String(node.bytes,StandardCharsets.UTF_8);
                    if (oid.equals("604c010303") && text.matches("[0-9]{14}")) result.add(text);
                    if (oid.equals("604c010301") && text.matches("[0-9]{19,}")) result.add(text.substring(8,19));
                }
            } catch (IllegalArgumentException ignored) { /* Invalid SAN cannot establish the certificate's identity. */ }
        }
        return result;
    }
    record Der(int tag,byte[] bytes) {}
    private static List<Der> der(byte[] bytes) { return der(bytes,0); }
    private static List<Der> der(byte[] bytes,int depth) {
        if(depth>16)throw new IllegalArgumentException();
        List<Der> result=new ArrayList<>(); var buffer=ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int tag=Byte.toUnsignedInt(buffer.get()), length=Byte.toUnsignedInt(buffer.get());
            if ((length&128)!=0) { int count=length&127; if(count==0||count>4)throw new IllegalArgumentException(); length=0; for(int i=0;i<count;i++)length=Math.addExact(Math.multiplyExact(length,256),Byte.toUnsignedInt(buffer.get())); }
            if(length<0||length>buffer.remaining())throw new IllegalArgumentException();
            byte[] value=new byte[length]; buffer.get(value); result.add(new Der(tag,value));
            if((tag&32)!=0)result.addAll(der(value,depth+1));
        }
        return result;
    }
    private byte[] key() {
        try { byte[] key=Base64.getDecoder().decode(settings.env.getRequiredProperty("NFSE_MASTER_KEY")); if(key.length!=32)throw new IllegalArgumentException(); return key; }
        catch (Exception ex) { throw bad("Configure NFSE_MASTER_KEY com uma chave Base64 de 32 bytes e preserve-a para reabrir os certificados"); }
    }
    private byte[] encrypt(byte[] data,String context) {
        try { byte[] iv=new byte[12]; random.nextBytes(iv); var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv)); cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8)); byte[] encrypted=cipher.doFinal(data); return ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array(); }
        catch(org.springframework.web.server.ResponseStatusException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Falha ao proteger certificado",ex);}
    }
    private byte[] decrypt(byte[] data,String context) {
        try { var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Arrays.copyOf(data,12))); cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(Arrays.copyOfRange(data,12,data.length)); }
        catch(Exception ex){throw bad("Não foi possível descriptografar o A1. Confira a chave mestra do cofre");}
    }
}
