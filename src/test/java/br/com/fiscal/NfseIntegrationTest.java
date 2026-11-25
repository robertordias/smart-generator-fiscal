package br.com.fiscal;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"APP_ADMIN_PASSWORD=nfse-test-password-123","spring.datasource.url=jdbc:h2:mem:nfse;DB_CLOSE_DELAY=-1",
    "NFSE_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","NFSE_ICP_TRUSTSTORE_PASSWORD=test-only-password","NFSE_CHECK_REVOCATION=false"},
    classes={Application.class,NfseIntegrationTest.FakeConfiguration.class})
class NfseIntegrationTest extends DatabaseTestSupport {
    @Autowired OfficeApi office;
    static final String KEY="3550308"+"1".repeat(43);
    static final Path TRUST;
    static {
        try { TRUST=Files.createTempFile("nfse-test-trust-",".p12"); TRUST.toFile().deleteOnExit();
            TRUST.toFile().setReadable(false,false);TRUST.toFile().setReadable(true,true);
            Files.write(TRUST,Base64.getDecoder().decode(new String(NfseIntegrationTest.class.getResourceAsStream("/nfse/test-truststore.base64").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("NFSE_ICP_TRUSTSTORE",()->TRUST.toString());}
    @Autowired NfseService service;@Autowired CertificateVault vault;@Autowired NationalXml xml;
    @Autowired FiscalApi fiscal;@Autowired FakeGateway gateway;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    Security.Account manager;FiscalApi.Issuer issuer;UUID certificate;FiscalApi.Draft draft;

    @TestConfiguration static class FakeConfiguration {
        @Bean @Primary FakeGateway fakeGateway(NationalXml xml,ObjectMapper json){return new FakeGateway(xml,json);}
    }
    static class FakeGateway implements NationalGateway {
        final NationalXml xml;final ObjectMapper json;int posts;String mode="OK",signed,requestEvent;boolean authorized,cancelled;
        FakeGateway(NationalXml xml,ObjectMapper json){this.xml=xml;this.json=json;}
        void reset(){posts=0;mode="OK";signed=null;requestEvent=null;authorized=false;cancelled=false;}
        public Reply exchange(String env,String method,String path,String payload,CertificateVault.Material material)throws Exception{
            if(method.equals("POST")&&path.equals("nfse")){
                posts++;signed=NationalXml.unzip(json.readTree(payload).get("dpsXmlGZipB64").asText());
                if(mode.equals("REJECT"))return new Reply(422,json.readTree("{\"erros\":[{\"Codigo\":\"E_TEST\",\"Descricao\":\"Rejeição fiscal de teste\"}]}"));
                if(mode.equals("TIMEOUT_BEFORE"))throw new java.io.IOException("test timeout");
                if(mode.equals("SERVER_ERROR"))return new Reply(503,json.createObjectNode());
                authorized=true;if(mode.equals("TIMEOUT_AFTER"))throw new java.io.IOException("test lost response");
                return official(env,material);
            }
            if(path.startsWith("dps/")&&mode.equals("QUERY_FORBIDDEN"))return new Reply(403,json.createObjectNode().put("transportError","Resposta fora do formato JSON"));
            if(path.startsWith("dps/")&&mode.equals("QUERY_TLS"))throw new javax.net.ssl.SSLHandshakeException("test handshake");
            if(path.startsWith("dps/"))return authorized?new Reply(200,json.createObjectNode().put("chaveAcesso",KEY)):new Reply(404,json.createObjectNode());
            if(path.endsWith("/eventos")&&method.equals("POST")){
                requestEvent=NationalXml.unzip(json.readTree(payload).get("pedidoRegistroEventoXmlGZipB64").asText());
                if(mode.equals("CANCEL_TIMEOUT_BEFORE"))throw new java.io.IOException("test cancellation timeout");
                if(mode.equals("CANCEL_REJECT"))return new Reply(422,json.readTree("{\"erros\":[{\"Codigo\":\"E_CANCEL\",\"Descricao\":\"Fora do prazo\"}]}"));
                cancelled=true;if(mode.equals("CANCEL_TIMEOUT"))throw new java.io.IOException("test lost cancellation");
                return event(material);
            }
            if(path.endsWith("/eventos/101101"))return cancelled?event(material):new Reply(404,json.createObjectNode());
            return official(env,material);
        }
        Reply official(String env,CertificateVault.Material material){
            var root=xml.parse("<NFSe xmlns=\""+NationalXml.NS+"\" versao=\"1.01\"/>").getDocumentElement();var inf=xml.add(root,"infNFSe",null);inf.setAttribute("Id","NFS"+KEY);
            xml.add(inf,"xLocEmi","São Paulo");xml.add(inf,"xLocPrestacao","São Paulo");xml.add(inf,"nNFSe","123");xml.add(inf,"xTribNac","Serviço de teste");xml.add(inf,"verAplic","TEST");xml.add(inf,"ambGer","2");xml.add(inf,"tpEmis","1");xml.add(inf,"cStat","100");xml.add(inf,"dhProc",OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")));xml.add(inf,"nDFSe","123");
            var dps=xml.parse(signed);String prest=xml.text(dps.getDocumentElement(),"infDPS/prest/CNPJ");
            if(mode.equals("MISMATCH"))xml.element(dps.getDocumentElement(),"infDPS").setAttribute("Id","DPS"+"9".repeat(42));
            var emit=xml.add(inf,"emit",null);xml.add(emit,"CNPJ",prest);xml.add(emit,"xNome","Empresa teste");var end=xml.add(emit,"enderNac",null);
            xml.add(end,"xLgr","Rua de teste");xml.add(end,"nro","10");xml.add(end,"xBairro","Centro");xml.add(end,"cMun","3550308");xml.add(end,"UF","SP");xml.add(end,"CEP","01001000");
            var vals=xml.add(inf,"valores",null);xml.add(vals,"vLiq",xml.text(dps.getDocumentElement(),"infDPS/valores/vServPrest/vServ"));
            inf.appendChild(inf.getOwnerDocument().importNode(dps.getDocumentElement(),true));
            String document=xml.sign(xml.serialize(root.getOwnerDocument()),"infNFSe",material);xml.validate(xml.parse(document),"NFSe_v1.01.xsd");
            return new Reply(201,json.createObjectNode().put("chaveAcesso",KEY).put("nfseXmlGZipB64",NationalXml.gzip(document)));
        }
        Reply event(CertificateVault.Material material){
            var root=xml.parse("<evento xmlns=\""+NationalXml.NS+"\" versao=\"1.01\"/>").getDocumentElement();var inf=xml.add(root,"infEvento",null);inf.setAttribute("Id","EVT"+KEY+"101101001");
            xml.add(inf,"verAplic","TEST");xml.add(inf,"ambGer","2");xml.add(inf,"nSeqEvento","001");xml.add(inf,"dhProc",OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")));xml.add(inf,"nDFSe","124");
            inf.appendChild(inf.getOwnerDocument().importNode(xml.parse(requestEvent).getDocumentElement(),true));
            String document=xml.sign(xml.serialize(root.getOwnerDocument()),"infEvento",material);xml.validate(xml.parse(document),"evento_v1.01.xsd");
            return new Reply(200,json.createObjectNode().put("eventoXmlGZipB64",NationalXml.gzip(document)));
        }
    }
    @BeforeEach void setup()throws Exception{
        manager=new Security.Account("contador","",UUID.randomUUID(),null,"GESTOR");gateway.reset();
        issuer=fiscal.createIssuer(manager,new FiscalApi.IssuerInput("12345678000195","Cliente teste","3550308"));
        fiscal.defaults(manager,issuer.id(),new FiscalApi.DefaultsInput(LocalDate.of(2026,1,1),"01.01","010101","6201501","Serviço & teste <nacional>",new BigDecimal("5"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,false));
        byte[] pfx=Base64.getDecoder().decode(new String(getClass().getResourceAsStream("/nfse/test-a1.base64").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        certificate=vault.save(manager,"A1 TEST ONLY","11222333000181",pfx,"test-only-password".toCharArray()).id();
        service.saveProfile(manager,issuer.id(),new NfseModels.Profile("3","1","0","","1",0));
        service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"PROC-TEST",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),true,true,true,true));
        draft=fiscal.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000.00"),null,null));
    }
    NfseModels.Prepare input(){return new NfseModels.Prepare(draft.id(),new NfseModels.Recipient("52998224725","Tomador teste",null,null),"3550308","","",null,null,"<totTrib xmlns='"+NationalXml.NS+"'><pTotTribSN>5.00</pTotTribSN></totTrib>",null);}
    NfseModels.Note prepare(){return service.prepare(manager,input());}
    NfseModels.Confirm confirm(){return new NfseModels.Confirm(issuer.cnpj());}

    @Test void signsTheStructuredVideoFormWithRecipientItemsDiscountsAndFederalRetentions() {
        var commercial=InvoiceFormTest.details(true,"100","10");
        draft=fiscal.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000"),null,"Retenções e bases conferidas para o tomador","Padrão",commercial));
        var formInput=new NfseModels.Prepare(draft.id(),null,"","","",null,null,null,null);
        var note=service.prepare(manager,formInput);var doc=xml.parse(service.artifact(manager,note.id(),"dps"));
        xml.validate(doc,"DPS_v1.01.xsd");var root=doc.getDocumentElement();
        assertEquals("52998224725",xml.text(root,"infDPS/toma/CPF"));assertEquals("form@example.com",xml.text(root,"infDPS/toma/email"));
        assertEquals("100.00",xml.text(root,"infDPS/valores/vDescCondIncond/vDescIncond"));
        assertEquals("10.00",xml.text(root,"infDPS/valores/vDescCondIncond/vDescCond"));
        assertEquals("5.85",xml.text(root,"infDPS/valores/trib/tribFed/piscofins/vPis"));
        assertEquals("13.50",xml.text(root,"infDPS/valores/trib/tribFed/vRetIRRF"));
        assertEquals("99.00",xml.text(root,"infDPS/valores/trib/tribFed/vRetCP"));
        assertEquals("2",xml.text(root,"infDPS/valores/trib/tribMun/tpRetISSQN"));
        assertTrue(xml.text(root,"infDPS/serv/infoCompl/xInfComp").contains("Informação & complemento <teste>"));
        assertTrue(xml.text(root,"infDPS/serv/cServ/xDescServ").contains("4 x R$ 250.00"));
        assertEquals(0,gateway.posts);
        var wrongRecipient=new NfseModels.Prepare(draft.id(),new NfseModels.Recipient("12345678000195","Outro",null,null),"","","",null,null,null,null);
        assertThrows(ResponseStatusException.class,()->service.prepare(manager,wrongRecipient));
    }

    @Test void officeRevenueSeparatesEnvironmentsAndKeepsPendingCancellationsUntilOfficialConfirmation() {
        var note=prepare();service.transmit(manager,note.id(),confirm());
        var authorizedAt=db.queryForObject("SELECT authorized_at FROM nfse_document WHERE id=?",OffsetDateTime.class,note.id());
        var date=authorizedAt.atZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDate();
        var current=office.dashboard(manager,null,date,"HOMOLOGACAO");
        assertEquals(0,new BigDecimal("1000").compareTo(current.dayRevenue()));assertEquals(1,current.dayNotes());assertEquals(1,current.monthNotes());
        assertEquals(0,office.dashboard(manager,null,date,"PRODUCAO").dayRevenue().signum());
        service.reconcile(manager,note.id());
        assertEquals(authorizedAt.toInstant(),db.queryForObject("SELECT authorized_at FROM nfse_document WHERE id=?",OffsetDateTime.class,note.id()).toInstant());
        gateway.mode="CANCEL_TIMEOUT";service.cancel(manager,note.id(),new NfseModels.Cancel("1","Cancelamento de laboratório",issuer.cnpj()));
        assertEquals(1,office.dashboard(manager,null,date,"HOMOLOGACAO").dayNotes());
        service.reconcile(manager,note.id());
        var cancelled=office.dashboard(manager,null,date,"HOMOLOGACAO");
        assertEquals(0,cancelled.dayRevenue().signum());assertEquals(1,cancelled.cancelled());
        db.update("UPDATE fiscal_certificate SET expires_at=? WHERE id=?",date.plusDays(10).atStartOfDay(ZoneId.of("America/Sao_Paulo")).toOffsetDateTime(),certificate);
        assertEquals(1,office.dashboard(manager,issuer.id(),date,"HOMOLOGACAO").expiringCertificates());
        var foreign=new Security.Account("outro","",UUID.randomUUID(),null,"GESTOR");
        assertEquals(0,office.dashboard(foreign,null,date,"HOMOLOGACAO").expiringCertificates());
        assertEquals(0,office.dashboard(foreign,null,date,"HOMOLOGACAO").dayNotes());
    }
    @Test void accountingExportsOnlyOfficialXmlAndCancellationEventsWithinScope() throws Exception {
        var note=prepare();LocalDate from=draft.competence().withDayOfMonth(1),to=from.plusMonths(1).minusDays(1);
        assertEquals(0,office.summary(manager,null,"HOMOLOGACAO",from,to).documents());
        assertThrows(ResponseStatusException.class,()->office.export(manager,null,"HOMOLOGACAO",from,to));
        assertThrows(ResponseStatusException.class,()->service.sharing(manager,note.id()));
        service.transmit(manager,note.id(),confirm());
        var share=service.sharing(manager,note.id());assertEquals("Tomador teste",share.recipientName());assertTrue(share.consultationUrl().contains(KEY));
        assertTrue(share.consultationUrl().startsWith("https://www.producaorestrita.nfse.gov.br/"));
        service.cancel(manager,note.id(),new NfseModels.Cancel("1","Cancelamento de laboratório",issuer.cnpj()));
        var summary=office.summary(manager,issuer.id(),"HOMOLOGACAO",from,to);
        assertEquals(1,summary.documents());assertEquals(1,summary.cancelled());assertEquals(0,office.summary(manager,null,"PRODUCAO",from,to).documents());
        byte[] zip=office.export(manager,null,"HOMOLOGACAO",from,to).getBody();
        var files=new java.util.LinkedHashMap<String,String>();
        try(var stream=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip),java.nio.charset.StandardCharsets.UTF_8)) {
            for(var entry=stream.getNextEntry();entry!=null;entry=stream.getNextEntry())files.put(entry.getName(),new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(3,files.size());String path=issuer.cnpj()+"/"+draft.competence()+"/nfse-"+note.id();
        assertEquals(service.artifact(manager,note.id(),"xml"),files.get(path+".xml"));
        assertEquals(service.artifact(manager,note.id(),"event"),files.get(path+"-cancelamento.xml"));
        assertTrue(files.get("manifesto.csv").contains("CANCELLED"));assertTrue(files.get("manifesto.csv").contains("sha256"));
        var client=new Security.Account("cliente","",manager.tenant(),issuer.id(),"CLIENTE");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->office.export(client,null,"HOMOLOGACAO",from,to)).getStatusCode().value());
        var foreign=new Security.Account("outro","",UUID.randomUUID(),null,"GESTOR");
        assertEquals(0,office.summary(foreign,null,"HOMOLOGACAO",from,to).documents());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.sharing(foreign,note.id())).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->office.summary(manager,null,"HOMOLOGACAO",from,to.plusYears(2)));
    }
    @Test void filtersFiscalNotesByStatusEnvironmentCompetenceAndLiteralSearch() {
        var note=prepare();
        assertEquals(1,service.list(manager,null,0,10,"PREPARED","HOMOLOGACAO",draft.competence(),draft.competence(),issuer.cnpj()).total());
        assertEquals(1,service.list(manager,null,0,10,"PREPARED","HOMOLOGACAO",null,null,"12.345.678/0001-95").total());
        assertEquals(0,service.list(manager,null,0,10,"AUTHORIZED","HOMOLOGACAO",null,null,"").total());
        assertEquals(0,service.list(manager,null,0,10,"","PRODUCAO",null,null,"").total());
        assertEquals(0,service.list(manager,null,0,10,"","",null,null,"%").total());
        service.transmit(manager,note.id(),confirm());
        assertEquals(1,service.list(manager,null,0,10,"AUTHORIZED","HOMOLOGACAO",null,null,"Cliente teste").total());
        var client=new Security.Account("cliente","",manager.tenant(),issuer.id(),"CLIENTE");
        assertEquals(1,service.list(client,null,0,10,"AUTHORIZED","",null,null,KEY).total());
        assertThrows(ResponseStatusException.class,()->service.list(manager,null,0,10,"INVALID","",null,null,""));
        assertThrows(ResponseStatusException.class,()->service.list(manager,null,0,10,"","INVALID",null,null,""));
        assertThrows(ResponseStatusException.class,()->service.list(manager,null,0,10,"","",LocalDate.of(2026,2,1),LocalDate.of(2026,1,1),""));
    }

    @Test void normalizesSaoPauloCcmBeforeSavingAndSigningDps() {
        var current=service.profile(manager,issuer.id());
        var saved=service.saveProfile(manager,issuer.id(),new NfseModels.Profile(current.simplesOption(),current.simplesAssessment(),current.specialRegime(),"6.470.006-2",current.series(),current.lastDpsNumber()));
        assertEquals("64700062",saved.municipalRegistration());
        assertEquals("64700062",service.profile(manager,issuer.id()).municipalRegistration());
        var note=prepare();var doc=xml.parse(service.artifact(manager,note.id(),"dps"));
        assertEquals("64700062",xml.text(doc.getDocumentElement(),"infDPS/prest/IM"));assertEquals(0,gateway.posts);
    }
    @Test void repreparesRejectedDpsWithCurrentRegistrationWithoutAnotherTransmission() {
        var original=prepare();gateway.mode="REJECT";
        assertEquals("REJECTED",service.transmit(manager,original.id(),confirm()).status());
        int transmissions=gateway.posts;
        var profile=service.profile(manager,issuer.id());
        service.saveProfile(manager,issuer.id(),new NfseModels.Profile(profile.simplesOption(),profile.simplesAssessment(),profile.specialRegime(),"6.470.006-2",profile.series(),profile.lastDpsNumber()));
        var revised=prepare();
        assertEquals(original.id(),revised.id());assertEquals(original.dpsNumber(),revised.dpsNumber());
        assertEquals("PREPARED",revised.status());assertEquals(transmissions,gateway.posts);
        var document=xml.parse(service.artifact(manager,revised.id(),"dps"));
        assertEquals("64700062",xml.text(document.getDocumentElement(),"infDPS/prest/IM"));
    }
    @Test void blocksMeiIssRetentionBeforeSigningOrTransmission() {
        var current=service.profile(manager,issuer.id());
        service.saveProfile(manager,issuer.id(),new NfseModels.Profile("2",current.simplesAssessment(),"0",current.municipalRegistration(),current.series(),current.lastDpsNumber()));
        fiscal.defaults(manager,issuer.id(),new FiscalApi.DefaultsInput(LocalDate.of(2026,1,1),"10.01","100104","6622300","Teste MEI",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,true,"MEI retido","109102000"));
        draft=fiscal.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,17),BigDecimal.TEN,null,null,"MEI retido"));
        var base=input();
        var recipient=new NfseModels.Recipient(base.recipient().taxId(),base.recipient().name(),null,new NfseModels.Address("3550308","01001000","Rua teste","10","Centro"));
        var error=assertThrows(ResponseStatusException.class,()->service.prepare(manager,new NfseModels.Prepare(draft.id(),recipient,"3550308","","",null,null,base.totalTaxesXml(),null)));
        assertTrue(error.getReason().contains("E0583"));assertEquals(0,gateway.posts);
    }
    @Test void separatesMunicipalServiceSuffixAndPreservesEffectiveSimplesRetentionRate() {
        String variant="Planos de saúde — ISS retido";
        fiscal.defaults(manager,issuer.id(),new FiscalApi.DefaultsInput(LocalDate.of(2026,1,1),"10.01","100104","6622300","Corretagem de planos de saúde",new BigDecimal("3.24"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,true,variant,"109102000"));
        draft=fiscal.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,17),new BigDecimal("1000.00"),null,null,variant));
        var base=input();
        var recipient=new NfseModels.Recipient(base.recipient().taxId(),base.recipient().name(),null,new NfseModels.Address("3550308","01001000","Rua de teste","10","Centro"));
        var prepared=service.prepare(manager,new NfseModels.Prepare(draft.id(),recipient,"3550308","001","",null,null,base.totalTaxesXml(),null));
        var doc=xml.parse(service.artifact(manager,prepared.id(),"dps"));xml.validate(doc,"DPS_v1.01.xsd");
        assertEquals("100104",xml.text(doc.getDocumentElement(),"infDPS/serv/cServ/cTribNac"));
        assertEquals("001",xml.text(doc.getDocumentElement(),"infDPS/serv/cServ/cTribMun"));
        assertEquals("3.24",xml.text(doc.getDocumentElement(),"infDPS/valores/trib/tribMun/pAliq"));
        assertEquals("2",xml.text(doc.getDocumentElement(),"infDPS/valores/trib/tribMun/tpRetISSQN"));
        assertEquals(0,gateway.posts);
    }
    @Test void signsNbsAndIssRetentionFromTheSelectedVariantAndRejectsNbsMismatch() {
        String name="Anexo III — com retenção";
        fiscal.defaults(manager,issuer.id(),new FiscalApi.DefaultsInput(LocalDate.of(2026,1,1),"01.01","010101","6201501","Serviço de teste",new BigDecimal("5"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,true,name,"115011000"));
        draft=fiscal.draft(manager,UUID.randomUUID().toString(),new FiscalApi.DraftInput(issuer.id(),LocalDate.of(2026,11,11),new BigDecimal("1000.00"),null,null,name));
        var base=input();
        var mismatch=new NfseModels.Prepare(draft.id(),base.recipient(),base.serviceMunicipality(),base.municipalServiceCode(),"115012000",null,null,base.totalTaxesXml(),null);
        assertThrows(ResponseStatusException.class,()->service.prepare(manager,mismatch));
        var missing=assertThrows(ResponseStatusException.class,this::prepare);
        assertTrue(missing.getReason().contains("endereço nacional completo"));
        var recipient=new NfseModels.Recipient(base.recipient().taxId(),base.recipient().name(),null,new NfseModels.Address("3550308","01001000","Rua de teste","10","Centro"));
        var completed=new NfseModels.Prepare(draft.id(),recipient,base.serviceMunicipality(),base.municipalServiceCode(),"",null,null,base.totalTaxesXml(),null);
        var note=service.prepare(manager,completed);var doc=xml.parse(service.artifact(manager,note.id(),"dps"));
        xml.validate(doc,"DPS_v1.01.xsd");
        assertEquals("01001000",xml.text(doc.getDocumentElement(),"infDPS/toma/end/endNac/CEP"));
        assertEquals("115011000",xml.text(doc.getDocumentElement(),"infDPS/serv/cServ/cNBS"));
        assertEquals("2",xml.text(doc.getDocumentElement(),"infDPS/valores/trib/tribMun/tpRetISSQN"));
        assertEquals(0,gateway.posts);
    }

    @Test void encryptsCertificateBuildsValidSignedDpsAndKeepsEmitentIdentity()throws Exception{
        var note=prepare();assertEquals("PREPARED",note.status());assertEquals(1,note.dpsNumber());assertEquals(0,gateway.posts);
        assertEquals(note.id(),prepare().id());assertEquals(1,service.profile(manager,issuer.id()).lastDpsNumber());
        String signed=service.artifact(manager,note.id(),"dps");var doc=xml.parse(signed);xml.validate(doc,"DPS_v1.01.xsd");
        assertEquals(issuer.cnpj(),xml.text(doc.getDocumentElement(),"infDPS/prest/CNPJ"));
        assertEquals(draft.snapshot().description(),xml.text(doc.getDocumentElement(),"infDPS/serv/cServ/xDescServ"));
        try(var material=vault.load(manager.tenant(),certificate)){
            var info=xml.element(doc.getDocumentElement(),"infDPS");info.setIdAttribute("Id",true);
            var validation=new DOMValidateContext(material.certificate().getPublicKey(),doc.getElementsByTagNameNS(XMLSignature.XMLNS,"Signature").item(0));
            assertTrue(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(validation).validate(validation));
        }
        byte[] encrypted=db.queryForObject("SELECT encrypted_pfx FROM fiscal_certificate WHERE id=?",byte[].class,certificate);
        assertFalse(Arrays.equals(encrypted,Base64.getDecoder().decode(new String(getClass().getResourceAsStream("/nfse/test-a1.base64").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))));
        var foreign=new Security.Account("other","",UUID.randomUUID(),null,"GESTOR");assertThrows(ResponseStatusException.class,()->vault.load(foreign.tenant(),certificate));assertThrows(ResponseStatusException.class,()->service.get(foreign,note.id()));
    }
    @Test void authorizesOnlyAfterMatchingOfficialXmlAndCancelsOnlyOnAcceptedEvent(){
        var note=prepare();var authorized=service.transmit(manager,note.id(),confirm());assertEquals("AUTHORIZED",authorized.status());assertEquals(KEY,authorized.accessKey());
        assertTrue(service.artifact(manager,note.id(),"xml").contains("<NFSe"));assertThrows(ResponseStatusException.class,()->service.transmit(manager,note.id(),confirm()));assertEquals(1,gateway.posts);
        gateway.mode="CANCEL_REJECT";var rejected=service.cancel(manager,note.id(),new NfseModels.Cancel("1","Erro de emissão no teste",issuer.cnpj()));assertEquals("AUTHORIZED",rejected.status());
        gateway.mode="CANCEL_TIMEOUT";var pending=service.cancel(manager,note.id(),new NfseModels.Cancel("1","Erro de emissão no teste",issuer.cnpj()));assertEquals("CANCEL_UNKNOWN",pending.status());
        assertEquals("CANCELLED",service.reconcile(manager,note.id()).status());assertTrue(service.artifact(manager,note.id(),"event").contains("e101101"));
        assertEquals("ACCEPTED_BY_FISCO",service.proxy(manager,issuer.id()).verificationStatus());
    }
    @Test void reconcilesLostAuthorizationWithoutSendingAnotherDps(){
        var note=prepare();gateway.mode="TIMEOUT_AFTER";assertEquals("UNKNOWN",service.transmit(manager,note.id(),confirm()).status());
        assertThrows(ResponseStatusException.class,()->service.transmit(manager,note.id(),confirm()));
        assertEquals("AUTHORIZED",service.reconcile(manager,note.id()).status());assertEquals(1,gateway.posts);
    }
    @Test void permitsRetryOnlyAfterOfficialNotFoundAndKeepsExactDpsIdentity(){
        var note=prepare();gateway.mode="TIMEOUT_BEFORE";assertEquals("UNKNOWN",service.transmit(manager,note.id(),confirm()).status());String signed=service.artifact(manager,note.id(),"dps");
        assertTrue(service.reconcile(manager,note.id()).retryAllowed());gateway.mode="OK";
        assertEquals("AUTHORIZED",service.transmit(manager,note.id(),confirm()).status());assertEquals(signed,gateway.signed);assertEquals(1,service.profile(manager,issuer.id()).lastDpsNumber());
    }
    @Test void reportsQueryFailuresWithoutResendingOrChangingFiscalState() {
        var note=prepare();gateway.mode="TIMEOUT_BEFORE";
        service.transmit(manager,note.id(),confirm());int posts=gateway.posts;
        gateway.mode="QUERY_FORBIDDEN";
        var forbidden=service.reconcile(manager,note.id());
        assertEquals("UNKNOWN",forbidden.status());assertEquals("HTTP_403",forbidden.lastCode());assertFalse(forbidden.retryAllowed());
        gateway.mode="QUERY_TLS";
        var tls=service.reconcile(manager,note.id());
        assertEquals("UNKNOWN",tls.status());assertEquals("TLS_FAILURE",tls.lastCode());assertFalse(tls.retryAllowed());
        assertEquals(posts,gateway.posts);
    }
    @Test void permitsOptionalProxyDatesButEnforcesAnyDeclaredBoundaries() {
        var saved=service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"SEM DATAS",null,null,true,true,true,true));
        assertNull(saved.validFrom());assertNull(saved.validTo());assertNotNull(prepare());
        service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"FUTURA",LocalDate.now().plusDays(1),null,true,true,true,true));
        assertThrows(ResponseStatusException.class,this::prepare);
        service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"ENCERRADA",null,LocalDate.now().minusDays(1),true,true,true,true));
        assertThrows(ResponseStatusException.class,this::prepare);
        assertThrows(ResponseStatusException.class,()->service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"INVERTIDA",LocalDate.now(),LocalDate.now().minusDays(1),true,true,true,true)));
    }
    @Test void blocksExpiredProxyRevokedCertificateClientRoleAndNumberRollback(){
        service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"EXPIRED",LocalDate.now().minusDays(10),LocalDate.now().minusDays(1),true,true,true,true));
        assertThrows(ResponseStatusException.class,this::prepare);
        service.saveProxy(manager,issuer.id(),new NfseModels.ProxyInput(certificate,"VALID",LocalDate.now().minusDays(1),LocalDate.now().plusYears(1),true,true,true,true));
        var note=prepare();assertThrows(ResponseStatusException.class,()->service.saveProfile(manager,issuer.id(),new NfseModels.Profile("3","1","0","","1",0)));
        var client=new Security.Account("client","",manager.tenant(),issuer.id(),"CLIENTE");assertThrows(ResponseStatusException.class,()->service.transmit(client,note.id(),confirm()));
        vault.revoke(manager,certificate);assertThrows(ResponseStatusException.class,()->service.transmit(manager,note.id(),confirm()));assertEquals(0,gateway.posts);
    }
    @Test void invalidOfficialIdentityNeverAuthorizes(){
        var note=prepare();gateway.mode="MISMATCH";assertEquals("UNKNOWN",service.transmit(manager,note.id(),confirm()).status());
        assertThrows(ResponseStatusException.class,()->service.artifact(manager,note.id(),"xml"));
    }
    @Test void retriesCancellationOnlyAfterQueryAndPreservesExactSignedRequest(){
        var note=prepare();service.transmit(manager,note.id(),confirm());gateway.mode="CANCEL_TIMEOUT_BEFORE";
        assertEquals("CANCEL_UNKNOWN",service.cancel(manager,note.id(),new NfseModels.Cancel("1","Cancelamento de teste sem resposta",issuer.cnpj())).status());
        String request=gateway.requestEvent;assertThrows(ResponseStatusException.class,()->service.retryCancel(manager,note.id(),confirm()));
        assertTrue(service.reconcile(manager,note.id()).retryAllowed());gateway.mode="OK";assertEquals("CANCELLED",service.retryCancel(manager,note.id(),confirm()).status());assertEquals(request,gateway.requestEvent);
    }
    @Test void schemaOfProductionIsValidatedIndependentlyAndProductionRequiresExplicitEnable(){
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("NFSE_ENVIRONMENT","PRODUCAO");
        var settings=new NfseSettings(env,Clock.systemUTC());var production=new NationalXml(settings);
        assertThrows(ResponseStatusException.class,()->settings.enabled("PRODUCAO"));
        String generated=production.build(input(),new NfseModels.Profile("3","1","0","","1",0),draft,issuer,"PRODUCAO",1);
        production.validate(production.parse(generated),"DPS_v1.01.xsd");assertFalse(generated.contains("finNFSe"));
        env.withProperty("NFSE_PRODUCTION_URL","http://evil.invalid/");assertThrows(ResponseStatusException.class,()->settings.endpoint("PRODUCAO","nfse"));
    }
    @Test void parallelPreparationsReserveOnlyOneIdentity()throws Exception{
        assertEquals("1",service.saveProfile(manager,issuer.id(),new NfseModels.Profile("3","1","0","","00001",0)).series());
        var first=java.util.concurrent.CompletableFuture.supplyAsync(this::prepare);var second=java.util.concurrent.CompletableFuture.supplyAsync(this::prepare);
        assertEquals(first.get().id(),second.get().id());assertEquals(1,service.profile(manager,issuer.id()).lastDpsNumber());
    }
    @Test void storesRejectionWithoutAuthorizationAndRejectsXxeAndBadSchema(){
        var note=prepare();gateway.mode="REJECT";assertEquals("REJECTED",service.transmit(manager,note.id(),confirm()).status());assertThrows(ResponseStatusException.class,()->service.artifact(manager,note.id(),"xml"));
        assertThrows(ResponseStatusException.class,()->xml.parse("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><DPS>&a;</DPS>"));
        assertThrows(ResponseStatusException.class,()->xml.validate(xml.parse("<DPS xmlns='"+NationalXml.NS+"' versao='1.01'/>"),"DPS_v1.01.xsd"));
        assertTrue(NationalXml.validTaxId("12345678000195"));assertFalse(NationalXml.validTaxId("12345678000100"));
    }
}
