package br.com.fiscal;

import java.nio.charset.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"APP_ADMIN_PASSWORD=test-password-123","spring.datasource.url=jdbc:h2:mem:csv_test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IssuerCsvImportTest extends DatabaseTestSupport {
    @Autowired IssuerCsvImport importer;
    @Autowired FiscalApi fiscal;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    Security.Account manager(){return new Security.Account("contador","",UUID.randomUUID(),null,"GESTOR");}
    String record(String taxId,String name) {
        var values=new ArrayList<>(Collections.nCopies(33,""));
        values.set(0,"999");values.set(1,name);values.set(2,"Fantasia teste");values.set(3,taxId);
        values.set(4,"[Simples nacional, Lucro Presumido]");values.set(5,"NÃO IMPORTAR RESPONSÁVEL");
        values.set(6,"ISENTO");values.set(7,"00012345");values.set(8,"NÃO IMPORTAR CEI");values.set(21,"6201-5/01");
        values.set(24,"Rua São João");values.set(25,"12");values.set(26,"Sala; 1\nAndar 2");
        values.set(27,"01001-000");values.set(28,"Centro");values.set(29,"São Paulo");values.set(30,"SP");
        values.set(31,"(11) 99999-0000");values.set(32,"fiscal@example.com");
        return String.join(";",values.stream().map(s->"\""+s.replace("\"","\"\"")+"\"").toList());
    }
    byte[] csv(String... records){return ("\ufeff\ufeff"+String.join(";",IssuerCsvImport.HEADERS)+"\r\n"+String.join("\r\n",records)+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);}
    @Test void previewsWithoutWritingThenPersistsOnlyFiscalRegistrationAndSkipsDuplicates() {
        var a=manager();var bytes=csv(record("11.222.333/0001-81","São João \"Fiscal\""));
        var preview=importer.preview(a,bytes);
        assertEquals(1,preview.ready());assertTrue(fiscal.issuers(a).isEmpty());
        assertEquals("3550308",preview.rows().getFirst().municipality());
        assertEquals("6201501",preview.rows().getFirst().registration().cnae());
        assertEquals(1,importer.save(a,bytes).imported());
        var issuer=fiscal.issuers(a).getFirst();var details=fiscal.registration(a,issuer.id());
        assertEquals("São João \"Fiscal\"",issuer.name());assertEquals("00012345",details.municipalRegistration());
        assertEquals("Sala; 1\nAndar 2",details.complement());assertEquals("01001000",details.zip());
        assertEquals("[Simples nacional, Lucro Presumido]",details.taxationReference());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fiscal_defaults WHERE tenant_id=?",Integer.class,a.tenant()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nfse_profile WHERE tenant_id=?",Integer.class,a.tenant()));
        var repeated=importer.save(a,csv(record(issuer.cnpj(),"NOME NÃO SOBRESCREVER")));
        assertEquals(0,repeated.imported());assertEquals(1,repeated.skipped());assertEquals(issuer.name(),fiscal.issuers(a).getFirst().name());
        var other=manager();assertEquals(1,importer.save(other,bytes).imported());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->fiscal.registration(other,issuer.id())).getStatusCode().value());
    }
    @Test void reportsInvalidRowsWhileImportingValidOnesAndDeduplicatesFile() {
        var a=manager();
        var bytes=csv(record("11222333000181","Pronta"),record("11222333000181","Repetida"),record("00000000000000","Inválida"),record("12345678909","CPF"),record("04252011000110","Cidade incorreta").replace("São Paulo","Cidade inexistente"));
        var result=importer.save(a,bytes);
        assertEquals(5,result.total());assertEquals(1,result.imported());assertEquals(1,result.skipped());assertEquals(3,result.invalid());
        assertEquals(1,fiscal.issuers(a).size());
        assertTrue(result.rows().get(3).messages().getFirst().contains("CPF"));
    }
    @Test void supportsWindows1252AndIgnoresAccentsForCityLookup() {
        var source=String.join(";",IssuerCsvImport.HEADERS)+"\n"+record("11222333000181","São José").replace("São Paulo","sao paulo");
        var result=importer.preview(manager(),source.getBytes(Charset.forName("windows-1252")));
        assertEquals(1,result.ready());assertEquals("São José",result.rows().getFirst().name());
        assertEquals("3550308",result.rows().getFirst().municipality());
    }
    @Test void rejectsWrongLayoutMalformedQuotesEmptyAndOversizedFiles() {
        var a=manager();
        assertThrows(ResponseStatusException.class,()->importer.preview(a,new byte[0]));
        assertThrows(ResponseStatusException.class,()->importer.preview(a,new byte[1024*1024+1]));
        assertThrows(ResponseStatusException.class,()->importer.preview(a,"Nome;CNPJ\nTeste;123".getBytes(StandardCharsets.UTF_8)));
        var changed=new ArrayList<>(Arrays.asList(IssuerCsvImport.HEADERS));Collections.swap(changed,1,2);
        assertThrows(ResponseStatusException.class,()->importer.preview(a,(String.join(";",changed)+"\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,()->IssuerCsvImport.parse("a;\"não fechada"));
        assertThrows(ResponseStatusException.class,()->IssuerCsvImport.parse("a;\"valor\"indevido"));
        assertThrows(ResponseStatusException.class,()->IssuerCsvImport.parse("a\n".repeat(5002)));
        assertTrue(fiscal.issuers(a).isEmpty());
    }
    @Test void rejectsInvalidContactAndReportsShortRows() {
        var result=importer.preview(manager(),csv(record("11222333000181","Contato inválido").replace("fiscal@example.com","email-invalido"),"1;2;3"));
        assertEquals(2,result.invalid());assertTrue(result.rows().getFirst().messages().contains("E-mail inválido"));
        assertTrue(result.rows().get(1).messages().getFirst().contains("33 colunas"));
    }
    @Test void protectsUploadEndpointsAndReturnsPreviewOverMultipart() throws Exception {
        var file=new MockMultipartFile("file","empresas.csv","text/csv",csv(record("11222333000181","Teste HTTP")));
        mvc.perform(multipart("/api/issuers/import/preview").file(file)).andExpect(status().isUnauthorized());
        var a=manager();var client=new Security.Account("cliente","",a.tenant(),UUID.randomUUID(),"CLIENTE");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->importer.preview(client,file.getBytes())).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->importer.save(client,file.getBytes())).getStatusCode().value());
        String basic="Basic "+Base64.getEncoder().encodeToString("contador:test-password-123".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/issuers/import/preview").file(file).header("Authorization",basic))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].municipality").value("3550308"));
    }
    @Test void correctsOnlySpecifiedFieldsAndRevalidatesBeforePersisting() {
        var a=manager();
        byte[] source=csv(record("00000000000000","Corrigida").replace("São Paulo","Cidade inexistente").replace("fiscal@example.com","email-invalido"));
        var original=importer.preview(a,source).rows().getFirst();
        assertEquals(Set.of("cnpj","city","uf","email"),original.fieldErrors().keySet());
        var wrong=Map.of(2,Map.of("cnpj","123","city","São Paulo","uf","SP","email","novo@example.com"));
        var invalid=importer.save(a,source,wrong);
        assertEquals(0,invalid.imported());assertEquals(Set.of("cnpj"),invalid.rows().getFirst().fieldErrors().keySet());
        assertTrue(fiscal.issuers(a).isEmpty());
        var corrections=Map.of(2,Map.of("cnpj","11.222.333/0001-81","city","São Paulo","uf","SP","email","novo@example.com"));
        assertEquals(1,importer.preview(a,source,corrections).ready());
        assertTrue(fiscal.issuers(a).isEmpty());
        assertEquals(1,importer.save(a,source,corrections).imported());
        var issuer=fiscal.issuers(a).getFirst();var details=fiscal.registration(a,issuer.id());
        assertEquals("11222333000181",issuer.cnpj());assertEquals("3550308",issuer.municipality());
        assertEquals("novo@example.com",details.email());assertEquals("Fantasia teste",details.tradeName());
        assertEquals("00012345",details.municipalRegistration());
        assertEquals(1,importer.preview(a,source).invalid(),"O arquivo original permanece inalterado");
    }
    @Test void correctionsRespectDuplicatesAndAllowFixingRemainingRowsAfterPartialImport() {
        var a=manager();byte[] source=csv(record("11222333000181","Pronta"),record("00000000000000","A corrigir"));
        assertEquals(1,importer.save(a,source).imported());
        var duplicate=Map.of(3,Map.of("cnpj","11222333000181"));
        assertEquals(2,importer.preview(a,source,duplicate).skipped());
        var correction=Map.of(3,Map.of("cnpj","04252011000110"));
        assertEquals(1,importer.preview(a,source,correction).ready());
        assertEquals(1,importer.save(a,source,correction).imported());assertEquals(2,fiscal.issuers(a).size());
        assertEquals(2,importer.save(a,source,correction).skipped());
    }
    @Test void identifiesEveryOversizedFieldAndSupportsStructuralCorrection() {
        var a=manager();var badRow=record("11222333000181","X".repeat(161)).replace("Fantasia teste","F".repeat(161)).replace("Rua São João","R".repeat(256));
        var result=importer.preview(a,csv(badRow));
        assertEquals(Set.of("name","tradeName","street"),result.rows().getFirst().fieldErrors().keySet());
        var shortSource=csv("1;2;3");var shortRow=importer.preview(a,shortSource).rows().getFirst();
        assertTrue(shortRow.fieldErrors().containsKey("layout"));assertEquals("\"1\";\"2\";\"3\"",shortRow.rawLine());
        assertEquals(1,importer.save(a,shortSource,Map.of(2,Map.of("layout",record("11222333000181","Linha reconstruída")))).imported());
    }
    @Test void rejectsUnknownFieldsAndInvalidCorrectionRowsAndValidatesMultipartCorrections() throws Exception {
        var a=manager();var source=csv(record("00000000000000","Correção HTTP"));
        assertThrows(ResponseStatusException.class,()->importer.preview(a,source,Map.of(1,Map.of("cnpj","11222333000181"))));
        assertThrows(ResponseStatusException.class,()->importer.preview(a,source,Map.of(999,Map.of("cnpj","11222333000181"))));
        assertThrows(ResponseStatusException.class,()->importer.preview(a,source,Map.of(2,Map.of("tenantId",UUID.randomUUID().toString()))));
        assertThrows(ResponseStatusException.class,()->importer.readCorrections("null"));
        assertThrows(ResponseStatusException.class,()->importer.readCorrections("not-json"));
        var client=new Security.Account("cliente","",a.tenant(),UUID.randomUUID(),"CLIENTE");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->importer.save(client,source,Map.of(2,Map.of("cnpj","11222333000181")))).getStatusCode().value());
        String basic="Basic "+Base64.getEncoder().encodeToString("contador:test-password-123".getBytes(StandardCharsets.UTF_8));
        var file=new MockMultipartFile("file","empresas.csv","text/csv",source);
        mvc.perform(multipart("/api/issuers/import/preview").file(file).param("corrections","{\"2\":{\"cnpj\":\"11222333000181\"}}").header("Authorization",basic))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1)).andExpect(jsonPath("$.rows[0].fieldErrors").isEmpty());
        mvc.perform(multipart("/api/issuers/import/preview").file(file).param("corrections","bad-json").header("Authorization",basic)).andExpect(status().isBadRequest());
    }
}
