package br.com.fiscal;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static br.com.fiscal.NfseSettings.bad;
import static br.com.fiscal.NfseModels.*;

@Service
class NfseService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NfseService.class);
    private final JdbcTemplate db;private final FiscalApi fiscal;private final CertificateVault vault;
    private final NationalXml xml;private final NationalGateway gateway;private final NfseSettings settings;
    private final ObjectMapper json;private final TransactionTemplate tx;
    NfseService(JdbcTemplate db,FiscalApi fiscal,CertificateVault vault,NationalXml xml,NationalGateway gateway,NfseSettings settings,ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager){
        this.db=db;this.fiscal=fiscal;this.vault=vault;this.xml=xml;this.gateway=gateway;this.settings=settings;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception ex){throw new IllegalStateException("Cadastro fiscal armazenado inválido",ex);}}
    private LocalDate today(){return settings.clock.instant().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate();}
    Profile profile(Security.Account a,UUID issuerId){
        var issuer=fiscal.issuer(a,issuerId,false);
        var values=db.queryForList("SELECT profile FROM nfse_profile WHERE tenant_id=? AND issuer_id=?",String.class,a.tenant(),issuerId);
        if(values.isEmpty())throw bad("Configure o enquadramento e a série DPS do emitente");
        var profile=decode(values.getFirst(),Profile.class);
        var numbers=db.queryForList("SELECT last_number FROM nfse_sequence WHERE tenant_id=? AND issuer_id=? AND environment=? AND series=?",Long.class,a.tenant(),issuerId,settings.environment(),profile.series());
        return new Profile(profile.simplesOption(),profile.simplesAssessment(),profile.specialRegime(),MunicipalRegistration.normalize(issuer.municipality(),profile.municipalRegistration()),profile.series(),numbers.isEmpty()?0:numbers.getFirst());
    }
    Profile saveProfile(Security.Account a,UUID issuerId,Profile profile){
        CertificateVault.manager(a);
        var issuer=fiscal.issuer(a,issuerId,false);
        if(Integer.parseInt(profile.series())<1||Integer.parseInt(profile.series())>49999)throw bad("Use uma série DPS própria entre 1 e 49999");
        if(profile.simplesOption().equals("3")&&(profile.simplesAssessment()==null||!profile.simplesAssessment().matches("[123]")))throw bad("Informe o regime de apuração do Simples Nacional");
        String series=Integer.toString(Integer.parseInt(profile.series()));
        var normalized=new Profile(profile.simplesOption(),profile.simplesAssessment(),profile.specialRegime(),MunicipalRegistration.normalize(issuer.municipality(),profile.municipalRegistration()),series,profile.lastDpsNumber());
        return tx.execute(status->{
            fiscal.issuer(a,issuerId,true);
            var numbers=db.queryForList("SELECT last_number FROM nfse_sequence WHERE tenant_id=? AND issuer_id=? AND environment=? AND series=?",Long.class,a.tenant(),issuerId,settings.environment(),series);
            if(!numbers.isEmpty()&&profile.lastDpsNumber()<numbers.getFirst())throw bad("A numeração DPS não pode retroceder");
            if(db.update("UPDATE nfse_sequence SET last_number=? WHERE tenant_id=? AND issuer_id=? AND environment=? AND series=?",profile.lastDpsNumber(),a.tenant(),issuerId,settings.environment(),series)==0)
                db.update("INSERT INTO nfse_sequence(tenant_id,issuer_id,environment,series,last_number) VALUES(?,?,?,?,?)",a.tenant(),issuerId,settings.environment(),series,profile.lastDpsNumber());
            if(db.update("UPDATE nfse_profile SET profile=? WHERE tenant_id=? AND issuer_id=?",encode(normalized),a.tenant(),issuerId)==0)
                db.update("INSERT INTO nfse_profile(tenant_id,issuer_id,profile) VALUES(?,?,?)",a.tenant(),issuerId,encode(normalized));
            return normalized;
        });
    }
    Proxy proxy(Security.Account a,UUID issuerId){
        fiscal.issuer(a,issuerId,false);
        return db.query("SELECT * FROM fiscal_proxy WHERE tenant_id=? AND issuer_id=?",(r,n)->new Proxy(issuerId,r.getObject("certificate_id",UUID.class),r.getString("reference"),r.getObject("valid_from",LocalDate.class),r.getObject("valid_to",LocalDate.class),r.getBoolean("can_issue"),r.getBoolean("can_query"),r.getBoolean("can_cancel"),r.getBoolean("active"),r.getString("verification_status"),r.getObject("verified_at",OffsetDateTime.class)),a.tenant(),issuerId)
            .stream().findFirst().orElseThrow(()->bad("Cadastre a procuração do cliente e vincule o A1 do contador"));
    }
    Proxy saveProxy(Security.Account a,UUID issuerId,ProxyInput input){
        CertificateVault.manager(a);fiscal.issuer(a,issuerId,false);
        if(input.validFrom()!=null && input.validTo()!=null && input.validTo().isBefore(input.validFrom()))throw bad("A validade da procuração está invertida");
        try(var material=vault.load(a.tenant(),input.certificateId())) { /* Validates certificate ownership in the same tenant. */ }
        return tx.execute(status->{
            fiscal.issuer(a,issuerId,true);
            if(db.update("UPDATE fiscal_proxy SET certificate_id=?,reference=?,valid_from=?,valid_to=?,can_issue=?,can_query=?,can_cancel=?,active=?,declared_by=?,verification_status='DECLARED',verified_at=NULL WHERE tenant_id=? AND issuer_id=?",
                input.certificateId(),input.reference(),input.validFrom(),input.validTo(),input.canIssue(),input.canQuery(),input.canCancel(),input.active(),a.username(),a.tenant(),issuerId)==0)
                db.update("INSERT INTO fiscal_proxy(tenant_id,issuer_id,certificate_id,reference,valid_from,valid_to,can_issue,can_query,can_cancel,active,declared_by,verification_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    a.tenant(),issuerId,input.certificateId(),input.reference(),input.validFrom(),input.validTo(),input.canIssue(),input.canQuery(),input.canCancel(),input.active(),a.username(),"DECLARED");
            return proxy(a,issuerId);
        });
    }
    private Proxy permitted(Security.Account a,UUID issuerId,String operation){
        CertificateVault.manager(a);var proxy=proxy(a,issuerId);
        if(!proxy.active()||(proxy.validFrom()!=null && today().isBefore(proxy.validFrom()))||(proxy.validTo()!=null && today().isAfter(proxy.validTo())))throw bad("Procuração inativa ou fora da validade declarada");
        boolean allowed=switch(operation){case "ISSUE"->proxy.canIssue();case "CANCEL"->proxy.canCancel();default->proxy.canQuery();};
        if(!allowed)throw bad("A procuração declarada não contempla esta operação");
        return proxy;
    }
    Note prepare(Security.Account a,Prepare input){
        CertificateVault.manager(a);
        var draft=fiscal.getDraft(a,input.draftId());var proxy=permitted(a,draft.issuerId(),"ISSUE");
        String env=settings.environment();settings.enabled(env);
        try(var material=vault.load(a.tenant(),proxy.certificateId())){
            return tx.execute(status->{
                var issuer=fiscal.issuer(a,draft.issuerId(),true);
                var profile=profile(a,draft.issuerId());
                var existing=db.queryForList("SELECT id,status FROM nfse_document WHERE tenant_id=? AND draft_id=? AND environment=?",a.tenant(),draft.id(),env);
                if(!existing.isEmpty()) {
                    var id=(UUID)existing.getFirst().get("ID");String state=(String)existing.getFirst().get("STATUS");
                    if(!Set.of("PREPARED","REJECTED").contains(state))throw bad("Este rascunho já possui uma emissão. Consulte o resultado antes de alterá-lo");
                    var old=get(a,id);String dps=xml.sign(xml.build(input,profile,draft,issuer,env,old.dpsNumber()),"infDPS",material);xml.validate(xml.parse(dps),"DPS_v1.01.xsd");
                    if(!old.series().equals(profile.series()))throw bad("A série deste rascunho já foi reservada. Preserve a série ou crie outro rascunho");
                    if(db.update("UPDATE nfse_document SET certificate_id=?,signed_dps=?,request_snapshot=?,status='PREPARED',last_code=NULL,last_message=NULL,retry_allowed=FALSE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND status IN ('PREPARED','REJECTED')",
                        proxy.certificateId(),dps,encode(Map.of("input",input,"issuer",issuer,"draft",draft,"profile",profile,"proxy",proxy)),a.tenant(),id)!=1)throw bad("A transmissão já foi iniciada. A DPS não pode mais ser alterada");
                    audit(a,id,"PREPARE","PREPARED",null,"DPS revisada e assinada",dps,null);return get(a,id);
                }
                long number=profile.lastDpsNumber()+1;
                // Re-read under the emitent lock; a parallel preparation may already have reserved a number.
                number=db.queryForObject("SELECT last_number+1 FROM nfse_sequence WHERE tenant_id=? AND issuer_id=? AND environment=? AND series=?",Long.class,a.tenant(),issuer.id(),env,profile.series());
                if(number>999999999999999L)throw bad("Numeração DPS esgotada");
                String unsigned=xml.build(input,profile,draft,issuer,env,number),signed=xml.sign(unsigned,"infDPS",material);xml.validate(xml.parse(signed),"DPS_v1.01.xsd");
                UUID id=UUID.randomUUID();
                db.update("UPDATE nfse_sequence SET last_number=? WHERE tenant_id=? AND issuer_id=? AND environment=? AND series=?",number,a.tenant(),issuer.id(),env,profile.series());
                db.update("INSERT INTO nfse_document(id,tenant_id,issuer_id,draft_id,certificate_id,environment,dps_id,series,dps_number,status,signed_dps,request_snapshot,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,a.tenant(),issuer.id(),draft.id(),proxy.certificateId(),env,xml.dpsId(issuer,profile.series(),number),profile.series(),number,"PREPARED",signed,encode(Map.of("input",input,"issuer",issuer,"draft",draft,"profile",profile,"proxy",proxy)),a.username());
                audit(a,id,"PREPARE","PREPARED",null,"DPS validada por XSD e assinada; ainda não enviada",signed,null);return get(a,id);
            });
        }
    }
    Note get(Security.Account a,UUID id){
        return db.query("SELECT n.*,d.amount,d.competence FROM nfse_document n JOIN draft d ON d.id=n.draft_id WHERE n.tenant_id=? AND n.id=?"+(a.issuer()==null?"":" AND n.issuer_id=?"),this::map,
            a.issuer()==null?new Object[]{a.tenant(),id}:new Object[]{a.tenant(),id,a.issuer()}).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private Note map(ResultSet r,int row)throws SQLException{
        String key=r.getString("access_key");String env=r.getString("environment");
        String consultation=key==null?null:"https://www."+(env.equals("PRODUCAO")?"":"producaorestrita.")+"nfse.gov.br/ConsultaPublica/?tpc=1&chave="+key;
        return new Note(r.getObject("id",UUID.class),r.getObject("issuer_id",UUID.class),r.getObject("draft_id",UUID.class),env,r.getString("dps_id"),r.getString("series"),r.getLong("dps_number"),r.getString("status"),key,r.getString("official_number"),r.getString("last_code"),r.getString("last_message"),r.getBoolean("retry_allowed"),r.getObject("created_at",OffsetDateTime.class),r.getBigDecimal("amount"),r.getObject("competence",LocalDate.class),consultation);
    }
    NotePage list(Security.Account a,UUID issuerId,int page,int size){return list(a,issuerId,page,size,"","",null,null,"");}
    NotePage list(Security.Account a,UUID issuerId,int page,int size,String state,String environment,LocalDate from,LocalDate to,String search){
        if(page<0||page>100000||size<1||size>100)throw bad("Paginação inválida");
        if(!state.isEmpty()&&!Set.of("PREPARED","SENDING","UNKNOWN","AUTHORIZED","REJECTED","CANCELLING","CANCEL_UNKNOWN","CANCELLED").contains(state))throw bad("Situação inválida");
        if(!environment.isEmpty()&&!Set.of("PRODUCAO","HOMOLOGACAO").contains(environment))throw bad("Ambiente inválido");
        if(from!=null&&to!=null&&from.isAfter(to))throw bad("Período invertido");
        if(search.length()>100)throw bad("Busca limitada a 100 caracteres");
        if(issuerId!=null)fiscal.issuer(a,issuerId,false);else issuerId=a.issuer();
        String where=" WHERE n.tenant_id=?"+(issuerId==null?"":" AND n.issuer_id=?");var args=new ArrayList<Object>();args.add(a.tenant());if(issuerId!=null)args.add(issuerId);
        if(!state.isEmpty()){where+=" AND n.status=?";args.add(state);}
        if(!environment.isEmpty()){where+=" AND n.environment=?";args.add(environment);}
        if(from!=null){where+=" AND d.competence>=?";args.add(from);}
        if(to!=null){where+=" AND d.competence<=?";args.add(to);}
        if(!search.isBlank()){
            where+=" AND (LOWER(i.name) LIKE ? ESCAPE '!' OR i.cnpj LIKE ? ESCAPE '!' OR n.access_key LIKE ? ESCAPE '!' OR n.official_number LIKE ? ESCAPE '!' OR CAST(n.dps_number AS VARCHAR) LIKE ? ESCAPE '!')";
            String term="%"+search.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            String taxSearch=search.replaceAll("[./\\-\\s]","");
            for(int i=0;i<5;i++)args.add(i==1&&taxSearch.matches("[0-9]{11,14}")?"%"+taxSearch+"%":term);
        }
        String source=" FROM nfse_document n JOIN draft d ON d.id=n.draft_id JOIN issuer i ON i.id=n.issuer_id AND i.tenant_id=n.tenant_id";
        long count=db.queryForObject("SELECT COUNT(*)"+source+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        return new NotePage(db.query("SELECT n.*,d.amount,d.competence"+source+where+" ORDER BY n.created_at DESC,n.id DESC LIMIT ? OFFSET ?",this::map,args.toArray()),count,page,size);
    }
    record Sharing(String recipientName,String email,String consultationUrl) {}
    Sharing sharing(Security.Account a,UUID id){
        var note=get(a,id);var data=row(a,note);
        if(data.get("OFFICIAL_XML")==null||note.consultationUrl()==null)throw bad("A nota ainda não possui autorização e XML oficial para compartilhar");
        try {
            var snapshot=json.readTree((String)data.get("REQUEST_SNAPSHOT"));
            var recipient=snapshot.path("input").path("recipient");
            if(recipient.isMissingNode()||recipient.isNull())recipient=snapshot.path("draft").path("snapshot").path("commercial").path("recipient");
            return new Sharing(recipient.path("name").asText(""),recipient.path("email").asText(""),note.consultationUrl());
        }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Dados da emissão inválidos",ex);}
    }
    private Map<String,Object> row(Security.Account a,Note note){return db.queryForMap("SELECT * FROM nfse_document WHERE tenant_id=? AND id=?",a.tenant(),note.id());}
    String artifact(Security.Account a,UUID id,String kind){
        var note=get(a,id);var row=row(a,note);
        String column=switch(kind){case "dps"->"SIGNED_DPS";case "event"->"CANCEL_EVENT";case "xml"->"OFFICIAL_XML";default->throw bad("Tipo de arquivo inválido");};
        String value=(String)row.get(column);if(value==null)throw bad("Arquivo ainda não disponível");return value;
    }
    List<Map<String,Object>> history(Security.Account a,UUID id){get(a,id);return db.queryForList("SELECT actor AS \"ACTOR\",operation AS \"OPERATION\",outcome AS \"OUTCOME\",response_code AS \"RESPONSE_CODE\",message AS \"MESSAGE\",occurred_at AS \"OCCURRED_AT\" FROM nfse_audit WHERE tenant_id=? AND document_id=? ORDER BY occurred_at,id",a.tenant(),id);}
    private void confirm(Security.Account a,Note note,String taxId){if(!fiscal.issuer(a,note.issuerId(),false).cnpj().equals(taxId))throw bad("Confirme o CNPJ da empresa emitente");settings.enabled(note.environment());}
    private void audit(Security.Account a,UUID id,String operation,String outcome,String code,String message,String request,JsonNode response){
        db.update("INSERT INTO nfse_audit(id,tenant_id,document_id,actor,operation,outcome,response_code,message,request_xml,response_payload) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),a.tenant(),id,a.username(),operation,outcome,clip(code,60),clip(message,2000),request,response==null?null:encode(response));
    }
    private String clip(String s,int max){return s==null?null:s.substring(0,Math.min(max,s.length()));}
    private void update(Security.Account a,UUID id,String state,String code,String message,boolean retry){
        String guard=switch(state){
            case "UNKNOWN","REJECTED"->" AND status IN ('PREPARED','SENDING','UNKNOWN','REJECTED')";
            case "AUTHORIZED","CANCEL_UNKNOWN"->" AND status IN ('AUTHORIZED','CANCELLING','CANCEL_UNKNOWN')";
            default->"";
        };
        db.update("UPDATE nfse_document SET status=?,last_code=?,last_message=?,retry_allowed=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?"+guard,state,clip(code,60),clip(message,2000),retry,a.tenant(),id);
    }
    private void verified(Security.Account a,Note note,String state){db.update("UPDATE fiscal_proxy SET verification_status=?,verified_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND issuer_id=?",state,a.tenant(),note.issuerId());}
    private String code(NationalGateway.Reply reply){
        JsonNode errors=reply.body().path("erros");if(errors.isArray()&&!errors.isEmpty())return errors.get(0).path("Codigo").asText(errors.get(0).path("codigo").asText("HTTP_"+reply.status()));return "HTTP_"+reply.status();
    }
    private String message(NationalGateway.Reply reply){
        JsonNode errors=reply.body().path("erros");if(errors.isArray()&&!errors.isEmpty())return errors.get(0).path("Descricao").asText(errors.get(0).path("descricao").asText("Consulte o retorno fiscal"));
        return reply.body().path("transportError").asText("A API não confirmou a autorização. Consulte novamente antes de reenviar");
    }
    Note transmit(Security.Account a,UUID id,Confirm input){
        var note=get(a,id);confirm(a,note,input.confirmIssuerTaxId());var proxy=permitted(a,note.issuerId(),"ISSUE");
        if(!proxy.canQuery())throw bad("Habilite o poder de consulta para reconciliar a emissão em caso de timeout");
        try(var material=vault.load(a.tenant(),proxy.certificateId())){
            String signed=tx.execute(status->{
                fiscal.issuer(a,note.issuerId(),true);
                var data=row(a,note);
                if(!proxy.certificateId().equals(data.get("CERTIFICATE_ID")))throw bad("O A1 foi alterado. Revise e assine a DPS novamente antes da transmissão");
                if(db.update("UPDATE nfse_document SET status='SENDING',retry_allowed=FALSE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND (status='PREPARED' OR (status='UNKNOWN' AND retry_allowed=TRUE))",a.tenant(),id)!=1)throw bad("Documento já transmitido ou em processamento. Consulte o resultado");
                String payload=(String)data.get("SIGNED_DPS");audit(a,id,"ISSUE","SENDING",null,"Transmissão iniciada",payload,null);return payload;
            });
            try {
                var reply=gateway.exchange(note.environment(),"POST","nfse",encode(Map.of("dpsXmlGZipB64",NationalXml.gzip(signed))),material);
                audit(a,id,"ISSUE_REPLY","RECEIVED","HTTP_"+reply.status(),"Resposta recebida; validação fiscal pendente",null,reply.body());
                applyIssue(a,note,reply,"ISSUE");
            }catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();update(a,id,"UNKNOWN","TRANSPORT_UNCERTAIN","Não foi possível confirmar o resultado da transmissão. Consulte a DPS antes de reenviar",false);audit(a,id,"ISSUE","UNKNOWN","TRANSPORT_UNCERTAIN","Falha de comunicação; a identidade da DPS foi preservada",null,null);}
            return get(a,id);
        }
    }
    private void applyIssue(Security.Account a,Note note,NationalGateway.Reply reply,String operation){
        boolean ok=reply.status()>=200&&reply.status()<300;
        String compressed=reply.body().path("nfseXmlGZipB64").asText("");
        if(ok&&!compressed.isBlank()){
            String official=NationalXml.unzip(compressed);var doc=xml.parse(official);xml.validate(doc,"NFSe_v1.01.xsd");
            var info=xml.element(doc.getDocumentElement(),"infNFSe");var dps=xml.element(info,"DPS/infDPS");String key=info.getAttribute("Id").replaceFirst("^NFS","");
            var issuer=fiscal.issuer(a,note.issuerId(),false);
            if(!"NFSe".equals(doc.getDocumentElement().getLocalName())||!key.matches("[0-9]{50}")||!note.dpsId().equals(dps.getAttribute("Id"))||!issuer.cnpj().equals(xml.text(dps,"prest/CNPJ"))
                ||!note.competence().toString().equals(xml.text(dps,"dCompet"))||note.amount().compareTo(new BigDecimal(xml.text(dps,"valores/vServPrest/vServ")))!=0
                ||!Integer.toString(settings.tpAmb(note.environment())).equals(xml.text(dps,"tpAmb")))throw bad("O retorno oficial não corresponde à DPS, ao ambiente ou ao emitente");
            if(!Set.of("100","107").contains(xml.text(info,"cStat")))throw bad("Situação fiscal do retorno não suportada por esta emissão");
            tx.executeWithoutResult(status->{
                db.update("UPDATE nfse_document SET access_key=?,official_xml=?,official_number=?,authorized_at=COALESCE(authorized_at,CURRENT_TIMESTAMP) WHERE tenant_id=? AND id=?",key,official,xml.text(info,"nNFSe"),a.tenant(),note.id());
                db.update("UPDATE nfse_document SET status='AUTHORIZED',last_code=?,last_message=?,retry_allowed=FALSE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND status IN ('PREPARED','SENDING','UNKNOWN','REJECTED','AUTHORIZED')",xml.text(info,"cStat"),"NFS-e autorizada pelo Emissor Nacional",a.tenant(),note.id());
                verified(a,note,"ACCEPTED_BY_FISCO");audit(a,note.id(),operation,"AUTHORIZED",xml.text(info,"cStat"),"Retorno oficial validado e armazenado",null,reply.body());
            });return;
        }
        String key=reply.body().path("chaveAcesso").asText("");if(key.matches("[0-9]{50}"))db.update("UPDATE nfse_document SET access_key=? WHERE tenant_id=? AND id=?",key,a.tenant(),note.id());
        boolean rejection=Set.of(400,401,403,422).contains(reply.status())&&reply.body().path("erros").isArray()&&!reply.body().path("erros").isEmpty();
        String state=rejection?"REJECTED":"UNKNOWN";update(a,note.id(),state,code(reply),message(reply),false);
        if(reply.status()==403)verified(a,note,"REFUSED_BY_FISCO");audit(a,note.id(),operation,state,code(reply),message(reply),null,reply.body());
    }
    private void queryFailure(Security.Account a,Note note,String code,String message) {
        db.update("UPDATE nfse_document SET last_code=?,last_message=?,retry_allowed=FALSE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND status=?",clip(code,60),clip(message,2000),a.tenant(),note.id(),note.status());
    }
    Note reconcile(Security.Account a,UUID id){
        var note=get(a,id);settings.enabled(note.environment());var proxy=permitted(a,note.issuerId(),"QUERY");
        if(note.status().equals("CANCELLED"))return note;
        try(var material=vault.load(a.tenant(),proxy.certificateId())){
            if(Set.of("CANCELLING","CANCEL_UNKNOWN").contains(note.status()))return reconcileCancel(a,note,material);
            try {
                String key=note.accessKey();
                if(key==null){
                    var response=gateway.exchange(note.environment(),"GET","dps/"+note.dpsId(),null,material);
                    if(response.status()==404){
                        db.update("UPDATE nfse_document SET status='UNKNOWN',last_code='DPS_NOT_FOUND',last_message=?,retry_allowed=TRUE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND status IN ('UNKNOWN','SENDING')","DPS ainda não localizada. É permitido reenviar somente a mesma DPS assinada",a.tenant(),id);
                        audit(a,id,"QUERY",note.status(),"DPS_NOT_FOUND","Consulta oficial não localizou a DPS",null,response.body());return get(a,id);
                    }
                    if(response.status()<200||response.status()>=300){
                        String detail="Consulta recusada pelo serviço fiscal (HTTP "+response.status()+"). "+message(response);
                        queryFailure(a,note,code(response),detail);
                        audit(a,id,"QUERY",note.status(),code(response),detail,null,response.body());return get(a,id);
                    }
                    key=response.body().path("chaveAcesso").asText("");if(!key.matches("[0-9]{50}"))throw bad("A consulta ainda não retornou chave de acesso válida");
                }
                var reply=gateway.exchange(note.environment(),"GET","nfse/"+key,null,material);
                if(reply.status()>=200&&reply.status()<300)applyIssue(a,note,reply,"QUERY");
                else audit(a,id,"QUERY",note.status(),code(reply),message(reply),null,reply.body());
            }catch(Exception ex){
                if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
                var failure=FiscalConnectionError.describe(ex);
                log.warn("Consulta NFS-e não confirmada: {} ({})",failure.code(),ex.getClass().getSimpleName());
                String detail=failure.message()+". A situação fiscal anterior foi preservada";
                queryFailure(a,note,failure.code(),detail);
                audit(a,id,"QUERY",note.status(),failure.code(),detail,null,null);
            }
            return get(a,id);
        }
    }
    Note cancel(Security.Account a,UUID id,Cancel input){
        var note=get(a,id);confirm(a,note,input.confirmIssuerTaxId());var proxy=permitted(a,note.issuerId(),"CANCEL");
        if(!proxy.canQuery())throw bad("É necessário poder de consulta para confirmar o cancelamento");
        if(!note.status().equals("AUTHORIZED"))throw bad("Somente uma nota autorizada pode solicitar cancelamento");
        try(var material=vault.load(a.tenant(),proxy.certificateId())){
            String request=xml.sign(xml.cancellation(note.accessKey(),input.confirmIssuerTaxId(),note.environment(),input.reasonCode(),input.reason()),"infPedReg",material);xml.validate(xml.parse(request),"pedRegEvento_v1.01.xsd");
            if(db.update("UPDATE nfse_document SET status='CANCELLING',cancel_request=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND status='AUTHORIZED'",request,a.tenant(),id)!=1)throw bad("Cancelamento já solicitado");
            audit(a,id,"CANCEL","CANCELLING",null,input.reason(),request,null);
            try {
                var reply=gateway.exchange(note.environment(),"POST","nfse/"+note.accessKey()+"/eventos",encode(Map.of("pedidoRegistroEventoXmlGZipB64",NationalXml.gzip(request))),material);
                applyCancel(a,note,reply);
            }catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();update(a,id,"CANCEL_UNKNOWN","TRANSPORT_UNCERTAIN","Cancelamento sem resultado confirmado. Consulte os eventos; a emissão original foi preservada",false);audit(a,id,"CANCEL","CANCEL_UNKNOWN","TRANSPORT_UNCERTAIN","Aguardando confirmação oficial do evento",null,null);}
            return get(a,id);
        }
    }
    private Note reconcileCancel(Security.Account a,Note note,CertificateVault.Material material){
        try{var reply=gateway.exchange(note.environment(),"GET","nfse/"+note.accessKey()+"/eventos/101101",null,material);
            if(reply.status()==404){update(a,note.id(),"CANCEL_UNKNOWN","EVENT_NOT_FOUND","Evento ainda não localizado. É permitido reenviar apenas o mesmo pedido assinado",true);audit(a,note.id(),"QUERY_CANCEL","CANCEL_UNKNOWN","EVENT_NOT_FOUND","Consulta oficial não localizou o evento",null,reply.body());}
            else applyCancel(a,note,reply);}
        catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();audit(a,note.id(),"QUERY_CANCEL","CANCEL_UNKNOWN","QUERY_UNCONFIRMED","Não foi possível confirmar o evento de cancelamento",null,null);}
        return get(a,note.id());
    }
    Note retryCancel(Security.Account a,UUID id,Confirm input){
        var note=get(a,id);confirm(a,note,input.confirmIssuerTaxId());var proxy=permitted(a,note.issuerId(),"CANCEL");
        if(!proxy.canQuery())throw bad("É necessário poder de consulta");
        try(var material=vault.load(a.tenant(),proxy.certificateId())){
            String request=(String)row(a,note).get("CANCEL_REQUEST");
            if(request==null||db.update("UPDATE nfse_document SET status='CANCELLING',retry_allowed=FALSE WHERE tenant_id=? AND id=? AND status='CANCEL_UNKNOWN' AND retry_allowed=TRUE",a.tenant(),id)!=1)throw bad("Consulte os eventos antes de reenviar um cancelamento");
            audit(a,id,"CANCEL_RETRY","CANCELLING",null,"Reenvio do mesmo pedido de cancelamento",request,null);
            try{applyCancel(a,note,gateway.exchange(note.environment(),"POST","nfse/"+note.accessKey()+"/eventos",encode(Map.of("pedidoRegistroEventoXmlGZipB64",NationalXml.gzip(request))),material));}
            catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();update(a,id,"CANCEL_UNKNOWN","TRANSPORT_UNCERTAIN","Reenvio do pedido sem resultado confirmado; consulte novamente",false);}
            return get(a,id);
        }
    }
    private void applyCancel(Security.Account a,Note note,NationalGateway.Reply reply){
        for(JsonNode value:reply.body().findValues("eventoXmlGZipB64")){
            String event=NationalXml.unzip(value.asText());var doc=xml.parse(event);xml.validate(doc,"evento_v1.01.xsd");var inf=xml.element(doc.getDocumentElement(),"infEvento/pedRegEvento/infPedReg");
            if(!note.accessKey().equals(xml.text(inf,"chNFSe"))||!Integer.toString(settings.tpAmb(note.environment())).equals(xml.text(inf,"tpAmb"))||xml.text(inf,"e101101/xDesc").isBlank())continue;
            if(reply.status()<200||reply.status()>=300)continue;
            tx.executeWithoutResult(status->{db.update("UPDATE nfse_document SET cancel_event=? WHERE tenant_id=? AND id=?",event,a.tenant(),note.id());update(a,note.id(),"CANCELLED","101101","Cancelamento confirmado por evento oficial",false);audit(a,note.id(),"CANCEL","CANCELLED","101101","Evento oficial validado e armazenado",null,reply.body());});return;
        }
        boolean rejected=Set.of(400,401,403,422).contains(reply.status())&&reply.body().path("erros").isArray()&&!reply.body().path("erros").isEmpty();
        update(a,note.id(),rejected?"AUTHORIZED":"CANCEL_UNKNOWN",code(reply),rejected?"Cancelamento rejeitado: "+message(reply):"Cancelamento ainda sem evento oficial confirmado",false);
        audit(a,note.id(),"CANCEL",rejected?"CANCEL_REJECTED":"CANCEL_UNKNOWN",code(reply),message(reply),null,reply.body());
    }
}
