package br.com.fiscal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class MunicipalRegistrationTest {
    @Test void removesOnlyMaskForSaoPauloAndPreservesLeadingZeros() {
        assertEquals("64700062",MunicipalRegistration.normalize("3550308","6.470.006-2"));
        assertEquals("00123456",MunicipalRegistration.normalize("3550308","00.123.456"));
        assertEquals("",MunicipalRegistration.normalize("3550308",null));
        assertThrows(ResponseStatusException.class,()->MunicipalRegistration.normalize("3550308","AB123"));
    }
    @Test void doesNotApplySaoPauloRulesToOtherMunicipalities() {
        assertEquals("AB-123",MunicipalRegistration.normalize("3304557"," AB-123 "));
    }
}
