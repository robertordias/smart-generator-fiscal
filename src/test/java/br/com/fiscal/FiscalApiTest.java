package br.com.fiscal;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"APP_ADMIN_PASSWORD=test-password-123", "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class FiscalApiTest extends DatabaseTestSupport {
    @Autowired FiscalApi api;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    final Security.Account manager = new Security.Account("contador","unused",UUID.fromString("00000000-0000-0000-0000-000000000001"),null,"GESTOR");
    final String basic="Basic "+Base64.getEncoder().encodeToString("contador:test-password-123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    FiscalApi.Issuer createIssuer() {
        String cnpj=String.format("%014d",Math.abs(UUID.randomUUID().getLeastSignificantBits()%100000000000000L));
        return api.createIssuer(manager,new FiscalApi.IssuerInput(cnpj,"Empresa teste","3550308"));
    }
    FiscalApi.DefaultsInput policy(LocalDate date,String description) {
        return new FiscalApi.DefaultsInput(date,"01.01","010101","6201501",description,
            new BigDecimal("5"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,false);
    }
    FiscalApi.DefaultsInput variant(LocalDate date,String name,boolean withheld,String nbs) {
        return new FiscalApi.DefaultsInput(date,"01.01","010101","6201501","Serviço de teste",new BigDecimal("5"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,withheld,name,nbs);
    }
    @Test void preservesCommercialFormRecalculatesTotalsAndRequiresReasonForTaxOverrides() {
        var issuer=createIssuer();api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,1,1),"Serviço original"));
        var commercial=InvoiceFormTest.details(false,"0","0");
        var draft=api.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000"),null,null,"Padrão",commercial));
        var stored=api.getDraft(manager,draft.id()).snapshot();
        assertEquals(commercial,stored.commercial());assertEquals(new BigDecimal("1000.00"),stored.totals().estimatedNet());
        assertTrue(stored.description().contains("4 x R$ 250.00"));
        assertThrows(ResponseStatusException.class,()->api.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("999"),null,null,"Padrão",commercial)));
        var changed=InvoiceFormTest.details(true,"100","10");
        assertThrows(ResponseStatusException.class,()->api.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000"),null,null,"Padrão",changed)));
        var adjusted=api.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000"),null,"Retenções confirmadas para este tomador","Padrão",changed));
        assertNotNull(adjusted.snapshot().sourceDefaults());assertTrue(adjusted.snapshot().defaults().values().issWithheld());
        assertFalse(api.effectiveDefaults(manager,issuer.id(),LocalDate.of(2026,11,11)).values().issWithheld());
        assertEquals(new BigDecimal("717.65"),api.getDraft(manager,adjusted.id()).snapshot().totals().estimatedNet());
    }
    @Test void supportsSameServiceCodeAndDateInNamedRetentionVariantsWithIndependentHistory() {
        var a=new Security.Account("contador","",UUID.randomUUID(),null,"GESTOR");
        var issuer=api.createIssuer(a,new FiscalApi.IssuerInput("12345678000195","Variações","3550308"));
        var start=LocalDate.of(2026,1,1);
        api.defaults(a,issuer.id(),variant(start,"Anexo III — sem retenção",false,"115011000"));
        var retained=api.defaults(a,issuer.id(),variant(start,"Anexo III — com retenção",true,"115011000"));
        assertEquals(2,api.services(a,issuer.id(),start).size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->api.defaults(a,issuer.id(),variant(start,"Anexo III — com retenção",true,"115011000")));
        var draft=api.draft(a,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),start,BigDecimal.TEN,null,null,"Anexo III — com retenção"));
        assertEquals(retained.id(),draft.snapshot().defaults().id());assertTrue(draft.snapshot().defaults().values().issWithheld());
        assertEquals("115011000",draft.snapshot().defaults().values().nbs());
        api.defaults(a,issuer.id(),variant(start.plusMonths(1),"Anexo III — com retenção",false,"115012000"));
        assertEquals("115012000",api.effectiveDefaults(a,issuer.id(),start.plusMonths(1),"Anexo III — com retenção").values().nbs());
        assertEquals("115011000",api.getDraft(a,draft.id()).snapshot().defaults().values().nbs());
        assertTrue(api.getDraft(a,draft.id()).snapshot().defaults().values().issWithheld());
        var client=new Security.Account("cliente","",a.tenant(),issuer.id(),"CLIENTE");
        assertEquals(2,api.services(client,issuer.id(),start).size());
        var foreign=new Security.Account("outro","",UUID.randomUUID(),null,"GESTOR");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.services(foreign,issuer.id(),start)).getStatusCode().value());
    }
    @Test void savesAndUpdatesRecipientsWithinIssuerScopeAndRejectsDuplicateAndInvalidTaxIds() {
        var a=new Security.Account("contador","",UUID.randomUUID(),null,"GESTOR");
        var first=api.createIssuer(a,new FiscalApi.IssuerInput("12345678000195","Primeiro","3550308"));
        var second=api.createIssuer(a,new FiscalApi.IssuerInput("11222333000181","Segundo","3550308"));
        var address=new NfseModels.Address("3550308","01001000","Rua de teste","1","Centro");
        var input=new NfseModels.Recipient("52998224725","Tomador inicial","inicial@example.com",address);
        var saved=api.createRecipient(a,first.id(),input);
        assertEquals(1,api.recipients(a,first.id()).size());assertTrue(api.recipients(a,second.id()).isEmpty());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->api.createRecipient(a,first.id(),input));
        assertThrows(ResponseStatusException.class,()->api.createRecipient(a,first.id(),new NfseModels.Recipient("00000000000","Inválido",null,null)));
        var changed=new NfseModels.Recipient(input.taxId(),"Tomador atualizado","atualizado@example.com",address);
        api.updateRecipient(a,first.id(),saved.id(),changed);
        assertEquals(changed,api.recipients(a,first.id()).getFirst().values());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.updateRecipient(a,second.id(),saved.id(),changed)).getStatusCode().value());
        var client=new Security.Account("cliente","",a.tenant(),first.id(),"CLIENTE");
        assertEquals(1,api.recipients(client,first.id()).size());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.recipients(client,second.id())).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.createRecipient(client,second.id(),input)).getStatusCode().value());
        var foreign=new Security.Account("outro","",UUID.randomUUID(),null,"GESTOR");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.recipients(foreign,first.id())).getStatusCode().value());
    }
    @Test void validatesNbsAndRecipientAddressOverHttpAndReadsLegacySnapshots() throws Exception {
        var issuer=createIssuer();
        var nullItem=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000"),null,null,"Padrão",InvoiceFormTest.details(false,"0","0")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)nullItem.get("commercial")).putArray("items").addNull();
        mvc.perform(post("/api/drafts").header("Authorization",basic).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(nullItem))).andExpect(status().isBadRequest());
        var invalid=json.valueToTree(variant(LocalDate.now(),"Variação",false,"123"));
        mvc.perform(post("/api/issuers/"+issuer.id()+"/defaults").header("Authorization",basic).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/issuers/"+issuer.id()+"/recipients").header("Authorization",basic).contentType(MediaType.APPLICATION_JSON).content("{\"taxId\":\"52998224725\",\"name\":\"Tomador\",\"address\":{\"municipality\":\"1\",\"zip\":\"12\",\"street\":\"Rua\",\"number\":\"1\",\"neighborhood\":\"Centro\"}}")).andExpect(status().isBadRequest());
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(policy(LocalDate.now(),"Legado"));
        legacy.remove("variantName");legacy.remove("nbs");
        var decoded=json.treeToValue(legacy,FiscalApi.DefaultsInput.class);
        assertEquals("Padrão",decoded.variantName());assertEquals("",decoded.nbs());
    }
    @Test void selectsByCompetenceAndPreservesHistoricalSnapshot() {
        var issuer=createIssuer(); var old=api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,1,1),"Original"));
        var draft=api.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,2,1),new BigDecimal("1000.00"),null,null));
        api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,3,1),"Nova descrição"));
        assertEquals(old.id(),api.effectiveDefaults(manager,issuer.id(),LocalDate.of(2026,2,28)).id());
        assertEquals(2,api.effectiveDefaults(manager,issuer.id(),LocalDate.of(2026,3,1)).version());
        assertEquals("Original",api.getDraft(manager,draft.id()).snapshot().description());
        assertEquals("ISSUER_DEFAULT",draft.snapshot().descriptionOrigin());
        assertFalse(draft.snapshot().defaults().values().issWithheld());
    }
    @Test void rejectsMissingPolicyAndRequiresOverrideReason() {
        var issuer=createIssuer();
        assertEquals(422,assertThrows(ResponseStatusException.class,()->api.effectiveDefaults(manager,issuer.id(),LocalDate.of(2025,1,1))).getStatusCode().value());
        api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,1,1),"Original"));
        assertThrows(ResponseStatusException.class,()->api.draft(manager,"override-missing",new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,1,1),BigDecimal.TEN,"Outra",null)));
        var draft=api.draft(manager,"override-ok",new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,1,1),BigDecimal.TEN,"Outra","Ajuste da prestação"));
        assertEquals("MANUAL",draft.snapshot().descriptionOrigin());
    }
    @Test void idempotencyReturnsSameDraftAndRejectsChangedPayload() {
        var issuer=createIssuer(); api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,1,1),"Original"));
        String key=UUID.randomUUID().toString();
        var input=new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,1,1),BigDecimal.TEN,null,null);
        var first=api.draft(manager,key,input);
        assertEquals(first.id(),api.draft(manager,key,input).id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->api.draft(manager,key,new FiscalApi.DraftInput(issuer.id(),input.competence(),BigDecimal.ONE,null,null))).getStatusCode().value());
    }
    @Test void isolatesTenantsAndRestrictsClientsToTheirIssuer() {
        var issuer=createIssuer(); var other=createIssuer();
        var foreign=new Security.Account("other","",UUID.randomUUID(),null,"GESTOR");
        var client=new Security.Account("client","",manager.tenant(),issuer.id(),"CLIENTE");
        assertTrue(api.issuers(foreign).isEmpty());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.effectiveDefaults(foreign,issuer.id(),LocalDate.now())).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.effectiveDefaults(client,other.id(),LocalDate.now())).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->api.defaults(client,issuer.id(),policy(LocalDate.now(),"Proibido"))).getStatusCode().value());
        api.defaults(manager,issuer.id(),policy(LocalDate.of(2026,1,1),"Original"));
        var draft=api.draft(client,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,1,1),BigDecimal.TEN,null,null));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.getDraft(foreign,draft.id())).getStatusCode().value());
        var otherClient=new Security.Account("otherClient","",manager.tenant(),other.id(),"CLIENTE");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->api.getDraft(otherClient,draft.id())).getStatusCode().value());
    }
    @Test void httpAuthenticationAndValidation() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization",basic)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GESTOR"));
        mvc.perform(post("/api/issuers").header("Authorization",basic).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cnpj\":\"invalid\",\"name\":\"\",\"municipality\":\"0\"}")).andExpect(status().isBadRequest());
        var issuer=createIssuer();
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(policy(LocalDate.of(2026,1,1),"Teste"));
        policy.put("issRate",-1);
        mvc.perform(post("/api/issuers/"+issuer.id()+"/defaults").header("Authorization",basic).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(policy))).andExpect(status().isBadRequest());
    }
    @Test void duplicateEffectiveDateReturnsConflict() throws Exception {
        var issuer=createIssuer(); var policy=policy(LocalDate.of(2026,1,1),"Teste");
        api.defaults(manager,issuer.id(),policy);
        mvc.perform(post("/api/issuers/"+issuer.id()+"/defaults").header("Authorization",basic).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(policy))).andExpect(status().isConflict());
    }
    @Test void listsAndSummarizesOnlyAuthorizedDraftsWithPaginationAndCompetenceFilters() {
        var a=new Security.Account("a","",UUID.randomUUID(),null,"GESTOR");
        var b=new Security.Account("b","",UUID.randomUUID(),null,"GESTOR");
        var first=api.createIssuer(a,new FiscalApi.IssuerInput("12345678000195","Primeira","3550308"));
        var second=api.createIssuer(a,new FiscalApi.IssuerInput("12345678000276","Segunda","3550308"));
        api.defaults(a,first.id(),policy(LocalDate.of(2026,1,1),"A"));
        api.defaults(a,second.id(),policy(LocalDate.of(2026,1,1),"B"));
        api.draft(a,"first",new FiscalApi.DraftInput(first.id(),LocalDate.of(2026,11,1),new BigDecimal("100.00"),null,null));
        api.draft(a,"second",new FiscalApi.DraftInput(second.id(),LocalDate.of(2026,10,1),new BigDecimal("200.00"),null,null));
        var page=api.drafts(a,null,null,null,0,1);
        assertEquals(2,page.total()); assertEquals(1,page.items().size());
        assertEquals(1,api.drafts(a,null,null,null,1,1).items().size());
        assertTrue(api.drafts(a,null,null,null,2,1).items().isEmpty());
        assertEquals(1,api.drafts(a,null,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),0,10).total());
        var overview=api.dashboard(a,null,LocalDate.of(2026,11,11));
        assertEquals(2,overview.companies()); assertEquals(2,overview.drafts());
        assertEquals(0,new BigDecimal("300.00").compareTo(overview.draftAmount()));
        assertEquals(1,overview.monthDrafts()); assertEquals(0,new BigDecimal("100.00").compareTo(overview.monthAmount()));
        assertEquals(0,api.drafts(b,null,null,null,0,10).total());
        assertEquals(0,api.dashboard(b,null,LocalDate.of(2026,11,11)).drafts());
        var client=new Security.Account("client","",a.tenant(),first.id(),"CLIENTE");
        assertEquals(1,api.drafts(client,null,null,null,0,10).total());
        assertEquals(1,api.dashboard(client,null,LocalDate.of(2026,11,11)).companies());
        assertEquals(1,api.dashboard(client,null,LocalDate.of(2026,11,11)).drafts());
        assertThrows(ResponseStatusException.class,()->api.drafts(client,second.id(),null,null,0,10));
        assertThrows(ResponseStatusException.class,()->api.dashboard(b,first.id(),LocalDate.of(2026,11,11)));
    }
    @Test void rejectsInvalidPaginationAndInvertedPeriod() throws Exception {
        mvc.perform(get("/api/drafts?page=-1").header("Authorization",basic)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/drafts?size=101").header("Authorization",basic)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/drafts?from=2026-11-11&to=2026-01-01").header("Authorization",basic)).andExpect(status().isBadRequest());
    }
}
