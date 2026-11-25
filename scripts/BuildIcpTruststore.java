import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Build from public ITI roots downloaded over verified HTTPS. No private certificates. */
class BuildIcpTruststore {
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("Uso: java scripts/BuildIcpTruststore.java destino.p12 raiz1.crt ...; senha em ICP_STORE_PASSWORD");
        var password=Objects.requireNonNull(System.getenv("ICP_STORE_PASSWORD"),"Defina ICP_STORE_PASSWORD").toCharArray();
        var destination=Path.of(args[0]);
        if(Files.exists(destination))throw new IllegalArgumentException("O destino já existe; preserve o truststore anterior");
        var store=KeyStore.getInstance("PKCS12");store.load(null,password);
        var report=new StringBuilder("Origem: https://www.gov.br/iti/pt-br/assuntos/repositorio/repositorio-ac-raiz\n");
        for(int i=1;i<args.length;i++) {
            X509Certificate cert;
            try(var input=Files.newInputStream(Path.of(args[i]))) { cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input); }
            cert.checkValidity();cert.verify(cert.getPublicKey());
            if(cert.getBasicConstraints()<0 || !cert.getIssuerX500Principal().equals(cert.getSubjectX500Principal()) || !cert.getSubjectX500Principal().getName().contains("ICP-Brasil"))
                throw new IllegalArgumentException("Arquivo não é raiz ICP-Brasil autoassinada: "+args[i]);
            var fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            store.setCertificateEntry("icp-"+fingerprint,cert);
            report.append(cert.getSubjectX500Principal()).append("\nSHA-256: ").append(fingerprint).append("\n");
        }
        try(var output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)) {store.store(output,password);}
        finally {Arrays.fill(password,'\0');}
        Files.writeString(Path.of(args[0]+".sources.txt"),report.toString());
        System.out.println("Truststore criado com "+store.size()+" raízes públicas ICP-Brasil.");
    }
}
