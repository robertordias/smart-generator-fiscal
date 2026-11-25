package br.com.fiscal;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.*;
import org.w3c.dom.*;
import org.w3c.dom.ls.LSInput;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import static br.com.fiscal.NfseSettings.bad;

@Component
class NationalXml {
    static final String NS="http://www.sped.fazenda.gov.br/nfse";
    private final NfseSettings settings;
    private final Map<String,Schema> schemas=new java.util.concurrent.ConcurrentHashMap<>();
    NationalXml(NfseSettings settings) { this.settings=settings; }
    Document parse(String xml) {
        if (xml==null || xml.length()>2_000_000) throw bad("Documento XML ausente ou muito grande");
        try {
            var factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) { throw bad("XML inválido. DTD e referências externas não são permitidos"); }
    }
    String text(Node parent,String path) {
        Node current=parent;
        for (String part:path.split("/")) {
            Node next=null;
            for (Node child=current.getFirstChild();child!=null;child=child.getNextSibling())
                if (child instanceof Element e && NS.equals(e.getNamespaceURI()) && part.equals(e.getLocalName())) { next=child; break; }
            if (next==null) return ""; current=next;
        }
        return current.getTextContent();
    }
    Element element(Node parent,String path) {
        Node current=parent;
        for(String part:path.split("/")) {
            Node next=null;
            for(Node child=current.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e&&NS.equals(e.getNamespaceURI())&&part.equals(e.getLocalName())){next=child;break;}
            if(next==null)throw bad("XML sem o campo obrigatório "+path); current=next;
        }
        return (Element)current;
    }
    Element add(Element parent,String tag,Object value) {
        Element e=parent.getOwnerDocument().createElementNS(NS,tag); parent.appendChild(e);
        if(value!=null)e.setTextContent(value.toString()); return e;
    }
    private Element root(String name) { return parse("<"+name+" xmlns=\""+NS+"\" versao=\"1.01\"/>").getDocumentElement(); }
    private boolean filled(String value) { return value!=null&&!value.isBlank(); }
    private String now() { return OffsetDateTime.ofInstant(settings.clock.instant(),ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")); }
    String dpsId(FiscalApi.Issuer issuer,String series,long number) { return "DPS"+issuer.municipality()+"2"+issuer.cnpj()+String.format("%05d",Integer.parseInt(series))+String.format("%015d",number); }

    String build(NfseModels.Prepare input,NfseModels.Profile profile,FiscalApi.Draft draft,FiscalApi.Issuer issuer,String env,long number) {
        if(!validTaxId(issuer.cnpj()))throw bad("CNPJ do emitente inválido");
        var commercial=draft.snapshot().commercial();
        if(commercial!=null) {
            if(filled(input.advancedDpsXml()))throw bad("Este rascunho usa o formulário estruturado. Preserve seus dados na preparação da nota");
            if(input.recipient()!=null&&!input.recipient().equals(commercial.recipient()))throw bad("O tomador deve corresponder ao formulário salvo");
            input=new NfseModels.Prepare(input.draftId(),commercial.recipient(),commercial.serviceMunicipality(),commercial.municipalServiceCode(),input.nbs(),null,
                InvoiceForm.federal(commercial,draft.snapshot().totals()),commercial.approximation()==null?input.totalTaxesXml():InvoiceForm.totalTaxes(commercial.approximation()),input.ibsCbsXml());
        }
        Element root;
        if (filled(input.advancedDpsXml())) {
            root=parse(input.advancedDpsXml()).getDocumentElement();
            if(!"DPS".equals(root.getLocalName())||!NS.equals(root.getNamespaceURI())||!"1.01".equals(root.getAttribute("versao")))throw bad("Informe uma DPS não assinada no leiaute 1.01");
            if(root.getElementsByTagNameNS(XMLSignature.XMLNS,"Signature").getLength()!=0)throw bad("Importe a DPS sem assinatura. A assinatura será feita pelo A1 do procurador");
            Element inf=element(root,"infDPS");
            if(!issuer.cnpj().equals(text(inf,"prest/CNPJ")) || !draft.competence().toString().equals(text(inf,"dCompet"))
                || !draft.snapshot().description().equals(text(inf,"serv/cServ/xDescServ"))
                || !draft.snapshot().defaults().values().nationalService().equals(text(inf,"serv/cServ/cTribNac")))throw bad("Emitente, competência, descrição e código de serviço da DPS devem corresponder ao rascunho");
            String savedNbs=draft.snapshot().defaults().values().nbs();
            if(filled(savedNbs)&&!savedNbs.equals(text(inf,"serv/cServ/cNBS")))throw bad("NBS da DPS deve corresponder ao rascunho");
            if(!String.valueOf(draft.snapshot().defaults().values().issWithheld()?2:1).equals(text(inf,"valores/trib/tribMun/tpRetISSQN")))throw bad("Retenção de ISS da DPS deve corresponder ao rascunho");
            try { if(draft.amount().compareTo(new BigDecimal(text(inf,"valores/vServPrest/vServ")))!=0)throw bad("Valor da DPS difere do rascunho"); }
            catch(NumberFormatException ex){throw bad("Valor da DPS inválido");}
            element(inf,"tpAmb").setTextContent(""+settings.tpAmb(env)); element(inf,"dhEmi").setTextContent(now());
            element(inf,"verAplic").setTextContent("SATrindade_0.2"); element(inf,"serie").setTextContent(profile.series());
            element(inf,"nDPS").setTextContent(""+number); element(inf,"tpEmit").setTextContent("1");
            element(inf,"cLocEmi").setTextContent(issuer.municipality());
            inf.setAttribute("Id",dpsId(issuer,profile.series(),number));
        } else {
            if(input.recipient()==null)throw bad("Informe o tomador do serviço");
            if(!validTaxId(input.recipient().taxId()) || !validTaxId(issuer.cnpj()))throw bad("CPF/CNPJ do tomador ou emitente inválido");
            var defaults=draft.snapshot().defaults().values();
            if(filled(defaults.nbs())&&filled(input.nbs())&&!defaults.nbs().equals(input.nbs()))throw bad("NBS deve corresponder ao padrão preservado no rascunho");
            String nbs=filled(defaults.nbs())?defaults.nbs():input.nbs();
            if(java.util.stream.Stream.of(defaults.irrfRate(),defaults.pisRate(),defaults.cofinsRate(),defaults.csllRate(),defaults.inssRate()).anyMatch(v->v.signum()!=0)&&!filled(input.federalTaxesXml()))
                throw bad("O padrão contém tributos federais. Informe o grupo tribFed completo ou importe uma DPS completa para não omitir retenções");
            root=root("DPS"); Element inf=add(root,"infDPS",null); inf.setAttribute("Id",dpsId(issuer,profile.series(),number));
            add(inf,"tpAmb",settings.tpAmb(env));add(inf,"dhEmi",now());add(inf,"verAplic","SATrindade_0.2");
            add(inf,"serie",profile.series());add(inf,"nDPS",number);add(inf,"dCompet",draft.competence());add(inf,"tpEmit",1);
            if(env.equals("HOMOLOGACAO"))add(inf,"finNFSe",0);
            add(inf,"cLocEmi",issuer.municipality());
            Element prest=add(inf,"prest",null);add(prest,"CNPJ",issuer.cnpj());
            String municipalRegistration=MunicipalRegistration.normalize(issuer.municipality(),profile.municipalRegistration());
            if(filled(municipalRegistration))add(prest,"IM",municipalRegistration);
            Element reg=add(prest,"regTrib",null);add(reg,"opSimpNac",profile.simplesOption());
            if(profile.simplesOption().equals("3"))add(reg,"regApTribSN",profile.simplesAssessment());add(reg,"regEspTrib",profile.specialRegime());
            var recipient=input.recipient();
            if(defaults.issWithheld() && recipient.address()==null)throw bad("Informe o endereço nacional completo do tomador para emitir com ISS retido. Corrija o cadastro e crie um novo rascunho");
            Element toma=add(inf,"toma",null);add(toma,recipient.taxId().length()==14?"CNPJ":"CPF",recipient.taxId());add(toma,"xNome",recipient.name());
            if(recipient.address()!=null) {
                var address=recipient.address(); Element end=add(toma,"end",null),national=add(end,"endNac",null);
                add(national,"cMun",address.municipality());add(national,"CEP",address.zip());add(end,"xLgr",address.street());add(end,"nro",address.number());add(end,"xBairro",address.neighborhood());
            }
            if(filled(recipient.email()))add(toma,"email",recipient.email());
            Element serv=add(inf,"serv",null),loc=add(serv,"locPrest",null);add(loc,"cLocPrestacao",filled(input.serviceMunicipality())?input.serviceMunicipality():issuer.municipality());
            Element code=add(serv,"cServ",null);add(code,"cTribNac",defaults.nationalService());
            if(filled(input.municipalServiceCode()))add(code,"cTribMun",input.municipalServiceCode());
            if(env.equals("HOMOLOGACAO")&&filled(nbs))add(code,"cNBS",nbs);
            add(code,"xDescServ",draft.snapshot().description());
            if(env.equals("PRODUCAO")&&filled(nbs))add(code,"cNBS",nbs);
            if(commercial!=null&&!InvoiceForm.complement(commercial).isBlank())add(add(serv,"infoCompl",null),"xInfComp",InvoiceForm.complement(commercial));
            Element values=add(inf,"valores",null),serviceValues=add(values,"vServPrest",null);add(serviceValues,"vServ",draft.amount().setScale(2).toPlainString());
            if(commercial!=null&&(commercial.unconditionalDiscount().signum()>0||commercial.conditionalDiscount().signum()>0)) {
                var discount=add(values,"vDescCondIncond",null);
                if(commercial.unconditionalDiscount().signum()>0)add(discount,"vDescIncond",InvoiceForm.money(commercial.unconditionalDiscount()).toPlainString());
                if(commercial.conditionalDiscount().signum()>0)add(discount,"vDescCond",InvoiceForm.money(commercial.conditionalDiscount()).toPlainString());
            }
            Element taxes=add(values,"trib",null),municipal=add(taxes,"tribMun",null);add(municipal,"tribISSQN",1);add(municipal,"tpRetISSQN",defaults.issWithheld()?2:1);
            boolean ownIss=!profile.simplesOption().equals("2")&&(!profile.simplesOption().equals("3")||!profile.simplesAssessment().equals("1")||defaults.issWithheld());
            if(ownIss)try{add(municipal,"pAliq",defaults.issRate().setScale(2).toPlainString());}catch(ArithmeticException ex){throw bad("A alíquota ISS precisa ser compatível com as duas casas do leiaute nacional");}
            if(filled(input.federalTaxesXml())) {
                Element federal=parse(input.federalTaxesXml()).getDocumentElement();
                Map<String,BigDecimal> expected=Map.of("vRetIRRF",defaults.irrfRate(),"vRetCSLL",defaults.csllRate(),"vRetCP",defaults.inssRate(),"piscofins/vPis",defaults.pisRate(),"piscofins/vCofins",defaults.cofinsRate());
                for(var entry:expected.entrySet())if(entry.getValue().signum()>0&&text(federal,entry.getKey()).isBlank())throw bad("Confirme o valor de "+entry.getKey()+"; o padrão do emitente contém percentual para esse tributo");
                append(taxes,input.federalTaxesXml(),"tribFed");
            }
            if(filled(input.totalTaxesXml()))append(taxes,input.totalTaxesXml(),"totTrib");
            else if(profile.simplesOption().equals("2"))add(add(taxes,"totTrib",null),"indTotTrib",0);
            else throw bad("Confirme como informar os totais aproximados dos tributos para este documento");
            if(filled(input.ibsCbsXml()))append(inf,input.ibsCbsXml(),"IBSCBS");
        }
        if("2".equals(text(root,"infDPS/prest/regTrib/opSimpNac")) && !"1".equals(text(root,"infDPS/valores/trib/tribMun/tpRetISSQN")))
            throw bad("MEI não pode emitir com ISS retido (E0583). Desmarque ISS nas retenções da emissão e salve um novo rascunho. Corrija também o padrão fiscal para as próximas notas");
        validate(root.getOwnerDocument(),"DPS_v1.01.xsd"); return serialize(root.getOwnerDocument());
    }
    private void append(Element parent,String fragment,String expected) {
        Element node=parse(fragment).getDocumentElement();
        if(!expected.equals(node.getLocalName())||!NS.equals(node.getNamespaceURI()))throw bad("O complemento deve conter o grupo "+expected+" com namespace oficial");
        parent.appendChild(parent.getOwnerDocument().importNode(node,true));
    }
    String sign(String unsigned,String infoTag,CertificateVault.Material material) {
        try {
            Document doc=parse(unsigned);Element info=element(doc.getDocumentElement(),infoTag);info.setIdAttribute("Id",true);
            var factory=XMLSignatureFactory.getInstance("DOM");
            boolean sha1=settings.env.getProperty("NFSE_SIGNATURE_ALGORITHM","SHA256").equals("SHA1");
            var reference=factory.newReference("#"+info.getAttribute("Id"),factory.newDigestMethod(sha1?DigestMethod.SHA1:DigestMethod.SHA256,null),
                List.of(factory.newTransform(Transform.ENVELOPED,(javax.xml.crypto.dsig.spec.TransformParameterSpec)null),factory.newTransform(CanonicalizationMethod.INCLUSIVE,(javax.xml.crypto.dsig.spec.TransformParameterSpec)null)),null,null);
            var signedInfo=factory.newSignedInfo(factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,(javax.xml.crypto.dsig.spec.C14NMethodParameterSpec)null),factory.newSignatureMethod(sha1?SignatureMethod.RSA_SHA1:SignatureMethod.RSA_SHA256,null),List.of(reference));
            var keyInfo=factory.getKeyInfoFactory().newKeyInfo(List.of(factory.getKeyInfoFactory().newX509Data(List.of(material.certificate()))));
            factory.newXMLSignature(signedInfo,keyInfo).sign(new DOMSignContext(material.privateKey(),doc.getDocumentElement()));
            return serialize(doc);
        } catch(org.springframework.web.server.ResponseStatusException ex){throw ex;}catch(Exception ex){throw bad("Não foi possível assinar o XML com o A1");}
    }
    String cancellation(String key,String issuer,String environment,String reasonCode,String reason) {
        Element root=root("pedRegEvento"),inf=add(root,"infPedReg",null);inf.setAttribute("Id","PRE"+key+"101101");
        add(inf,"tpAmb",settings.tpAmb(environment));add(inf,"verAplic","SATrindade_0.2");add(inf,"dhEvento",now());add(inf,"CNPJAutor",issuer);add(inf,"chNFSe",key);
        Element cancel=add(inf,"e101101",null);add(cancel,"xDesc","Cancelamento de NFS-e");add(cancel,"cMotivo",reasonCode);add(cancel,"xMotivo",reason);
        validate(root.getOwnerDocument(),"pedRegEvento_v1.01.xsd");return serialize(root.getOwnerDocument());
    }
    void validate(Document doc,String file) {
        try {
            String directory=settings.environment().equals("HOMOLOGACAO")?"/nfse/xsd/homologacao/":"/nfse/xsd/";
            Schema schema=schemas.computeIfAbsent(directory+file,key->{
                try {
                    var factory=SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                    factory.setResourceResolver((type,ns,publicId,systemId,base)->{
                        if(systemId==null)throw bad("Referência XSD não permitida");
                        // The bundled W3C signature schema declares an optional external DTD.
                        // Its schema content is self-contained; never fetch that DTD over the network.
                        if(systemId.equals("http://www.w3.org/2001/XMLSchema.dtd"))return new SchemaInput(publicId,systemId,new ByteArrayInputStream(new byte[0]));
                        String local=systemId.substring(systemId.lastIndexOf('/')+1);
                        if(!local.matches("[A-Za-z0-9_.-]+\\.xsd"))throw bad("Referência XSD não permitida");
                        var original=getClass().getResourceAsStream(directory+local);if(original==null)throw bad("XSD local não encontrado");
                        byte[] content;try(original){content=original.readAllBytes();}catch(IOException ex){throw new IllegalStateException(ex);}
                        // XML Schema regex has no ^/$ anchors. The official series pattern uses
                        // them with .NET semantics. Normalize only that known pattern for Xerces.
                        String schemaText=new String(content,StandardCharsets.UTF_8).replace("value=\"^0{0,4}\\d{1,5}$\"","value=\"0{0,4}\\d{1,5}\"");
                        var stream=new ByteArrayInputStream(schemaText.getBytes(StandardCharsets.UTF_8));
                        return new SchemaInput(publicId,local,stream);
                    });
                    var url=getClass().getResource(directory+file);if(url==null)throw bad("Pacote XSD indisponível");return factory.newSchema(url);
                } catch(Exception ex){throw new IllegalStateException("Não foi possível carregar os XSD oficiais",ex);}
            });
            schema.newValidator().validate(new DOMSource(doc));
        } catch(org.xml.sax.SAXException ex){throw bad("XML não atende ao leiaute oficial: "+ex.getMessage().substring(0,Math.min(650,ex.getMessage().length())));}
        catch(IOException ex){throw bad("Falha ao validar XML");}
    }
    String serialize(Document doc) {
        try {var factory=TransformerFactory.newInstance();factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var transformer=factory.newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");transformer.setOutputProperty(OutputKeys.INDENT,"no");var out=new StringWriter();transformer.transform(new DOMSource(doc),new StreamResult(out));return out.toString();}
        catch(Exception ex){throw new IllegalStateException("Falha ao serializar XML",ex);}
    }
    static String gzip(String xml) {
        try {var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(xml.getBytes(StandardCharsets.UTF_8));}return Base64.getEncoder().encodeToString(out.toByteArray());}
        catch(IOException ex){throw new IllegalStateException(ex);}
    }
    static String unzip(String encoded) {
        try(var input=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))){
            byte[] bytes=input.readNBytes(2_000_001);if(bytes.length>2_000_000)throw bad("Resposta fiscal excede o limite de XML");return new String(bytes,StandardCharsets.UTF_8);
        }catch(org.springframework.web.server.ResponseStatusException ex){throw ex;}catch(Exception ex){throw bad("A API retornou um XML compactado inválido");}
    }
    static boolean validTaxId(String id) {
        if(id==null||!id.matches("[0-9]{11}|[0-9]{14}")||id.chars().distinct().count()==1)return false;
        int[] weights=id.length()==14?new int[]{5,4,3,2,9,8,7,6,5,4,3,2}:new int[]{10,9,8,7,6,5,4,3,2};
        int length=id.length()-2,sum=0;for(int i=0;i<length;i++)sum+=(id.charAt(i)-'0')*weights[i];int first=sum%11<2?0:11-sum%11;
        if(first!=id.charAt(length)-'0')return false;
        sum=0;for(int i=0;i<=length;i++){int weight=id.length()==14?(i==0?6:weights[i-1]):11-i;sum+=(id.charAt(i)-'0')*weight;}int second=sum%11<2?0:11-sum%11;return second==id.charAt(length+1)-'0';
    }
    static final class SchemaInput implements LSInput {
        private String publicId,systemId;private InputStream stream;
        SchemaInput(String publicId,String systemId,InputStream stream){this.publicId=publicId;this.systemId=systemId;this.stream=stream;}
        public Reader getCharacterStream(){return null;}public void setCharacterStream(Reader r){}public InputStream getByteStream(){return stream;}public void setByteStream(InputStream s){stream=s;}
        public String getStringData(){return null;}public void setStringData(String s){}public String getSystemId(){return systemId;}public void setSystemId(String s){systemId=s;}
        public String getPublicId(){return publicId;}public void setPublicId(String s){publicId=s;}public String getBaseURI(){return null;}public void setBaseURI(String s){}
        public String getEncoding(){return "UTF-8";}public void setEncoding(String s){}public boolean getCertifiedText(){return false;}public void setCertifiedText(boolean b){}
    }
}
