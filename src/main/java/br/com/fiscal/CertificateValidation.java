package br.com.fiscal;

/** Enable distribution-point lookup before the JDK initializes its PKIX/CRL implementation. */
final class CertificateValidation {
    private CertificateValidation() {}
    static void initialize() {
        System.setProperty("com.sun.security.enableCRLDP","true");
    }
}
