package br.com.fiscal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CnpjRegistryTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void mapsCompanyAddressUsingIbgeCodeInsteadOfRevenueMunicipalityCode() throws Exception {
        var result=CnpjRegistry.parse("11222333000181",json.readTree("""
            {"cnpj":"11.222.333/0001-81","razao_social":"Tomador de teste","cep":"01001-000",
             "codigo_municipio":7107,"codigo_municipio_ibge":3550308,"descricao_tipo_de_logradouro":"RUA",
             "logradouro":"de teste","numero":"25","bairro":"Centro","email":null}
            """));
        assertEquals("3550308",result.address().municipality());assertEquals("01001000",result.address().zip());
        assertEquals("RUA de teste",result.address().street());assertNull(result.email());
        assertEquals("25",result.address().number());
    }
    @Test void rejectsCpfInvalidCnpjAndUnexpectedCompanyWithoutMakingExternalRequests() throws Exception {
        var registry=new CnpjRegistry(json);
        assertThrows(ResponseStatusException.class,()->registry.lookup("52998224725"));
        assertThrows(ResponseStatusException.class,()->registry.lookup("00000000000000"));
        assertThrows(ResponseStatusException.class,()->CnpjRegistry.parse("11222333000181",json.readTree("{\"cnpj\":\"12345678000195\"}")));
    }
}
