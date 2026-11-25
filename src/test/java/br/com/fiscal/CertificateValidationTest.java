package br.com.fiscal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertificateValidationTest {
    @Test void enablesCrlDistributionPointsWithoutDependingOnDockerOrVmOptions() {
        String previous=System.getProperty("com.sun.security.enableCRLDP");
        try {
            System.clearProperty("com.sun.security.enableCRLDP");
            CertificateValidation.initialize();
            assertEquals("true",System.getProperty("com.sun.security.enableCRLDP"));
            CertificateValidation.initialize();
            assertEquals("true",System.getProperty("com.sun.security.enableCRLDP"));
        } finally {
            if(previous==null)System.clearProperty("com.sun.security.enableCRLDP");else System.setProperty("com.sun.security.enableCRLDP",previous);
        }
    }
}
