package br.com.fiscal;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class NfseSettingsTest {
    @Test void missingTruststoreIsNotReportedAsConfigured() {
        var settings=new NfseSettings(new MockEnvironment().withProperty("NFSE_ICP_TRUSTSTORE","/nonexistent/sa-trindade-truststore.p12"),Clock.systemUTC());
        assertFalse(settings.trustConfigured());
        assertThrows(ResponseStatusException.class,settings::truststorePath);
    }
    @Test void keepsReadableExplicitTruststoreAndSupportsComposePathInLocalExecution() throws Exception {
        Path local=Path.of("secrets","test-truststore-"+UUID.randomUUID()+".p12").toAbsolutePath();
        Files.createDirectories(local.getParent());Files.write(local,new byte[]{1});
        try {
            var env=new MockEnvironment().withProperty("NFSE_ICP_TRUSTSTORE",local.toString());
            var settings=new NfseSettings(env,Clock.systemUTC());
            assertEquals(local,settings.truststorePath());assertTrue(settings.trustConfigured());
            if(!Files.exists(Path.of("/run/fiscal-secrets"))) {
                env.setProperty("NFSE_ICP_TRUSTSTORE","/run/fiscal-secrets/"+local.getFileName());
                assertEquals(local,settings.truststorePath());assertTrue(settings.trustConfigured());
            }
        } finally {Files.deleteIfExists(local);}
    }
}
