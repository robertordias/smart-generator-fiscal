package br.com.fiscal;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/office")
public class OfficeApi {
    private final JdbcTemplate db;private final FiscalApi fiscal;
    private static final int MAX_DOCUMENTS=500,MAX_XML_BYTES=50*1024*1024;
    private static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
    private static final String ACTIVE="('AUTHORIZED','CANCELLING','CANCEL_UNKNOWN')";
    public OfficeApi(JdbcTemplate db,FiscalApi fiscal){this.db=db;this.fiscal=fiscal;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static void environment(String env){if(!Set.of("PRODUCAO","HOMOLOGACAO").contains(env))throw bad("Escolha produção ou homologação");}
    private UUID scope(Security.Account a,UUID issuerId){if(issuerId!=null){fiscal.issuer(a,issuerId,false);return issuerId;}return a.issuer();}
    private record Filter(String where,List<Object> args) {}
    private Filter scopeFilter(Security.Account a,UUID issuerId,String env){
        environment(env);var issuer=scope(a,issuerId);var args=new ArrayList<Object>(List.of(a.tenant(),env));
        String where=" WHERE n.tenant_id=? AND n.environment=?";
        if(issuer!=null){where+=" AND n.issuer_id=?";args.add(issuer);}
        return new Filter(where,args);
    }
    public record Overview(BigDecimal dayRevenue,long dayNotes,BigDecimal monthRevenue,long monthNotes,long cancelled,long pending,long rejected,long expiringCertificates,long expiredCertificates) {}
    @GetMapping("/dashboard")
    public Overview dashboard(@AuthenticationPrincipal Security.Account a,@RequestParam(required=false) UUID issuerId,
        @RequestParam LocalDate referenceDate,@RequestParam(defaultValue="PRODUCAO") String environment){
        if(referenceDate.getYear()<2000||referenceDate.getYear()>2100)throw bad("Data de referência inválida");
        var filter=scopeFilter(a,issuerId,environment);
        var dayStart=referenceDate.atStartOfDay(ZONE).toOffsetDateTime();var dayEnd=referenceDate.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        var monthStart=referenceDate.withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();var monthEnd=referenceDate.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toOffsetDateTime();
        String source=" FROM nfse_document n JOIN draft d ON d.id=n.draft_id";
        var day=new ArrayList<>(filter.args());day.add(dayStart);day.add(dayEnd);
        var month=new ArrayList<>(filter.args());month.add(monthStart);month.add(monthEnd);
        String dayWhere=filter.where()+" AND n.status IN "+ACTIVE+" AND n.authorized_at>=? AND n.authorized_at<?";
        String monthWhere=filter.where()+" AND n.status IN "+ACTIVE+" AND n.authorized_at>=? AND n.authorized_at<?";
        BigDecimal dayRevenue=db.queryForObject("SELECT COALESCE(SUM(d.amount),0)"+source+dayWhere,BigDecimal.class,day.toArray());
        long dayNotes=db.queryForObject("SELECT COUNT(*)"+source+dayWhere,Long.class,day.toArray());
        BigDecimal monthRevenue=db.queryForObject("SELECT COALESCE(SUM(d.amount),0)"+source+monthWhere,BigDecimal.class,month.toArray());
        long monthNotes=db.queryForObject("SELECT COUNT(*)"+source+monthWhere,Long.class,month.toArray());
        long cancelled=db.queryForObject("SELECT COUNT(*)"+source+filter.where()+" AND n.status='CANCELLED' AND n.authorized_at>=? AND n.authorized_at<?",Long.class,month.toArray());
        long pending=db.queryForObject("SELECT COUNT(*)"+source+filter.where()+" AND n.status IN ('PREPARED','SENDING','UNKNOWN','CANCELLING','CANCEL_UNKNOWN')",Long.class,filter.args().toArray());
        long rejected=db.queryForObject("SELECT COUNT(*)"+source+filter.where()+" AND n.status='REJECTED'",Long.class,filter.args().toArray());
        UUID issuer=scope(a,issuerId);var certArgs=new ArrayList<Object>();certArgs.add(a.tenant());
        String cert=" FROM fiscal_certificate c WHERE c.tenant_id=? AND c.status='ACTIVE'";
        if(issuer!=null){cert+=" AND EXISTS(SELECT 1 FROM fiscal_proxy p WHERE p.tenant_id=c.tenant_id AND p.certificate_id=c.id AND p.issuer_id=?)";certArgs.add(issuer);}
        var expiring=new ArrayList<>(certArgs);expiring.add(dayStart);expiring.add(dayStart.plusDays(31));
        long soon=db.queryForObject("SELECT COUNT(*)"+cert+" AND c.expires_at>=? AND c.expires_at<?",Long.class,expiring.toArray());
        certArgs.add(dayStart);
        long expired=db.queryForObject("SELECT COUNT(*)"+cert+" AND c.expires_at<?",Long.class,certArgs.toArray());
        return new Overview(dayRevenue,dayNotes,monthRevenue,monthNotes,cancelled,pending,rejected,soon,expired);
    }
    private Filter accounting(Security.Account a,UUID issuerId,String env,LocalDate from,LocalDate to){
        IssuerCsvImport.manager(a);
        if(from==null||to==null||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>366)throw bad("Escolha um período de competência de até 367 dias");
        var base=scopeFilter(a,issuerId,env);var args=new ArrayList<>(base.args());args.add(from);args.add(to);
        return new Filter(base.where()+" AND n.official_xml IS NOT NULL AND n.status IN ('AUTHORIZED','CANCELLED','CANCELLING','CANCEL_UNKNOWN') AND d.competence>=? AND d.competence<=?",args);
    }
    public record ExportSummary(long documents,long cancelled,BigDecimal documentAmount,int limit) {}
    @GetMapping("/xml/summary")
    public ExportSummary summary(@AuthenticationPrincipal Security.Account a,@RequestParam(required=false) UUID issuerId,
        @RequestParam String environment,@RequestParam LocalDate from,@RequestParam LocalDate to){
        var f=accounting(a,issuerId,environment,from,to);
        String source=" FROM nfse_document n JOIN draft d ON d.id=n.draft_id";
        long count=db.queryForObject("SELECT COUNT(*)"+source+f.where(),Long.class,f.args().toArray());
        long cancelled=db.queryForObject("SELECT COUNT(*)"+source+f.where()+" AND n.status='CANCELLED'",Long.class,f.args().toArray());
        BigDecimal total=db.queryForObject("SELECT COALESCE(SUM(d.amount),0)"+source+f.where(),BigDecimal.class,f.args().toArray());
        return new ExportSummary(count,cancelled,total,MAX_DOCUMENTS);
    }
    @GetMapping("/xml/export") @Transactional(readOnly=true)
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Security.Account a,@RequestParam(required=false) UUID issuerId,
        @RequestParam String environment,@RequestParam LocalDate from,@RequestParam LocalDate to) throws IOException {
        var filter=accounting(a,issuerId,environment,from,to);var summary=summary(a,issuerId,environment,from,to);
        if(summary.documents()==0)throw bad("Não há XMLs oficiais para os filtros selecionados");
        if(summary.documents()>MAX_DOCUMENTS)throw bad("Mais de 500 documentos: reduza o período ou selecione uma empresa");
        var output=new ByteArrayOutputStream();var manifest=new StringBuilder("arquivo;cnpj;competencia;numero;ambiente;situacao;valor;sha256\r\n");
        int[] bytes={0},count={0};
        try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            String query="SELECT n.id,n.official_xml,n.cancel_event,n.official_number,n.environment,n.status,d.competence,d.amount,i.cnpj FROM nfse_document n JOIN draft d ON d.id=n.draft_id JOIN issuer i ON i.id=n.issuer_id AND i.tenant_id=n.tenant_id"+filter.where()+" ORDER BY i.cnpj,d.competence,n.id";
            db.query(connection->{var statement=connection.prepareStatement(query);statement.setFetchSize(25);for(int i=0;i<filter.args().size();i++)statement.setObject(i+1,filter.args().get(i));return statement;},(org.springframework.jdbc.core.RowCallbackHandler)row->{
                if(++count[0]>MAX_DOCUMENTS)throw bad("Mais de 500 documentos: reduza o período");
                String prefix=row.getString("cnpj")+"/"+row.getObject("competence",LocalDate.class)+"/nfse-"+row.getObject("id",UUID.class);
                for(String column:List.of("official_xml","cancel_event")) {
                    String xml=row.getString(column);if(xml==null)continue;
                    byte[] content=xml.getBytes(StandardCharsets.UTF_8);bytes[0]+=content.length;
                    if(bytes[0]>MAX_XML_BYTES)throw bad("Os XMLs excedem 50 MB: reduza o período");
                    String file=prefix+(column.equals("cancel_event")?"-cancelamento.xml":".xml");
                    try {
                        zip.putNextEntry(new ZipEntry(file));zip.write(content);zip.closeEntry();
                        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                        manifest.append(String.join(";",List.of(file,row.getString("cnpj"),row.getObject("competence",LocalDate.class).toString(),safeCsv(row.getString("official_number")),row.getString("environment"),row.getString("status"),row.getBigDecimal("amount").toPlainString(),hash))).append("\r\n");
                    }catch(IOException|java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("Falha ao gerar pacote XML",ex);}
                }
            });
            zip.putNextEntry(new ZipEntry("manifesto.csv"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"xml-"+environment.toLowerCase(Locale.ROOT)+"-"+from+"-"+to+".zip\"").body(output.toByteArray());
    }
    private static String safeCsv(String value){if(value==null)return "";if(value.matches("^[=+@-].*"))value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> business(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(error.getReason(),"Recurso indisponível")));}
}
