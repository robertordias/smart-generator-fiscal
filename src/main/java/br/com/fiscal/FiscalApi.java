package br.com.fiscal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class FiscalApi {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final IssuerCsvImport csvImport;
    private final CnpjRegistry cnpjRegistry;
    public FiscalApi(JdbcTemplate db, ObjectMapper json, IssuerCsvImport csvImport,CnpjRegistry cnpjRegistry) { this.db = db; this.json = json; this.csvImport=csvImport; this.cnpjRegistry=cnpjRegistry; }

    @PostMapping(value="/issuers/import/preview",consumes="multipart/form-data")
    public IssuerCsvImport.Result previewImport(@AuthenticationPrincipal Security.Account a,
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
        @RequestParam(defaultValue="{}") String corrections) throws java.io.IOException {
        IssuerCsvImport.manager(a);
        if(file.getSize()>IssuerCsvImport.MAX_BYTES)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione um CSV de até 1 MB");
        return csvImport.preview(a,file.getBytes(),csvImport.readCorrections(corrections));
    }
    @PostMapping(value="/issuers/import",consumes="multipart/form-data")
    public IssuerCsvImport.Result importIssuers(@AuthenticationPrincipal Security.Account a,
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
        @RequestParam(defaultValue="{}") String corrections) throws java.io.IOException {
        IssuerCsvImport.manager(a);
        if(file.getSize()>IssuerCsvImport.MAX_BYTES)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione um CSV de até 1 MB");
        return csvImport.save(a,file.getBytes(),csvImport.readCorrections(corrections));
    }
    @GetMapping("/issuers/{id}/registration")
    public IssuerCsvImport.Registration registration(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id) {
        issuer(a,id,false);
        return db.query("SELECT * FROM issuer_registration WHERE tenant_id=? AND issuer_id=?",(r,n)->new IssuerCsvImport.Registration(
            r.getString("trade_name"),r.getString("state_registration"),r.getString("municipal_registration"),r.getString("cnae"),
            r.getString("taxation_reference"),r.getString("street"),r.getString("address_number"),r.getString("complement"),r.getString("zip"),
            r.getString("neighborhood"),r.getString("city"),r.getString("uf"),r.getString("phone"),r.getString("email")),a.tenant(),id)
            .stream().findFirst().orElse(new IssuerCsvImport.Registration("","","","","","","","","","","","","",""));
    }

    public record IssuerInput(@NotBlank @Pattern(regexp="[0-9]{14}") String cnpj,
        @NotBlank @Size(max=160) String name, @NotBlank @Pattern(regexp="[0-9]{7}") String municipality) {}
    public record Issuer(UUID id, String cnpj, String name, String municipality) {}
    public record DefaultsInput(@NotNull LocalDate effectiveFrom,
        @NotBlank @Pattern(regexp="[0-9]{2}\\.[0-9]{2}") String lc116,
        @NotBlank @Pattern(regexp="[0-9]{6}") String nationalService,
        @NotBlank @Pattern(regexp="[0-9]{7}") String cnae,
        @NotBlank @Size(max=2000) String description,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal issRate,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal irrfRate,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal pisRate,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal cofinsRate,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal csllRate,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal inssRate,
        @NotNull Boolean issWithheld,
        @NotBlank @Size(max=120) String variantName, @Pattern(regexp="[0-9]{9}|") String nbs) {
        public DefaultsInput { variantName=variantName==null?"Padrão":variantName.strip();nbs=nbs==null?"":nbs; }
        public DefaultsInput(LocalDate effectiveFrom,String lc116,String nationalService,String cnae,String description,
            BigDecimal issRate,BigDecimal irrfRate,BigDecimal pisRate,BigDecimal cofinsRate,BigDecimal csllRate,BigDecimal inssRate,Boolean issWithheld) {
            this(effectiveFrom,lc116,nationalService,cnae,description,issRate,irrfRate,pisRate,cofinsRate,csllRate,inssRate,issWithheld,"Padrão","");
        }
    }
    public record FiscalDefaults(UUID id, int version, DefaultsInput values) {}
    public record DraftInput(@NotNull UUID issuerId, @NotNull LocalDate competence,
        @NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
        @Size(min=1,max=2000) String description, @Size(max=300) String overrideReason,
        @NotBlank @Size(max=120) String variantName,@Valid InvoiceForm.Details commercial) {
        public DraftInput {variantName=variantName==null?"Padrão":variantName.strip();}
        public DraftInput(UUID issuerId,LocalDate competence,BigDecimal amount,String description,String overrideReason,String variantName) {
            this(issuerId,competence,amount,description,overrideReason,variantName,null);
        }
        public DraftInput(UUID issuerId,LocalDate competence,BigDecimal amount,String description,String overrideReason) {
            this(issuerId,competence,amount,description,overrideReason,"Padrão",null);
        }
    }
    public record Snapshot(FiscalDefaults defaults, String description, String descriptionOrigin,
        String overrideReason, String actor,InvoiceForm.Details commercial,InvoiceForm.Totals totals,FiscalDefaults sourceDefaults) {
        public Snapshot(FiscalDefaults defaults,String description,String descriptionOrigin,String overrideReason,String actor) {
            this(defaults,description,descriptionOrigin,overrideReason,actor,null,null,null);
        }
    }
    public record Draft(UUID id, UUID issuerId, LocalDate competence, BigDecimal amount, String status, Snapshot snapshot) {}
    public record DraftPage(List<Draft> items, long total, int page, int size) {}
    public record Dashboard(long companies, long drafts, BigDecimal draftAmount, long monthDrafts, BigDecimal monthAmount) {}
    @GetMapping("/issuers/{id}/recipients/cnpj/{cnpj}")
    public CnpjRegistry.Result lookupRecipient(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@PathVariable String cnpj) {
        issuer(a,id,false);return cnpjRegistry.lookup(cnpj);
    }
    public record SavedRecipient(UUID id,NfseModels.Recipient values) {}
    @GetMapping("/issuers/{id}/recipients")
    public List<SavedRecipient> recipients(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id) {
        issuer(a,id,false);
        return db.query("SELECT r.* FROM recipient r JOIN recipient_issuer l ON l.recipient_id=r.id AND l.tenant_id=r.tenant_id WHERE l.tenant_id=? AND l.issuer_id=? ORDER BY r.name,r.id",(r,n)-> {
            try {
                var address=r.getString("address_json");
                return new SavedRecipient(r.getObject("id",UUID.class),new NfseModels.Recipient(r.getString("tax_id"),r.getString("name"),r.getString("email"),address==null?registeredRecipientAddress(a,r.getString("tax_id")):json.readValue(address,NfseModels.Address.class)));
            } catch(JsonProcessingException ex) {throw new IllegalStateException("Endereço cadastrado inválido",ex);}
        },a.tenant(),id);
    }
    private NfseModels.Address registeredRecipientAddress(Security.Account a,String taxId) {
        // Reuse imported company registration only inside the same office.
        return db.query("SELECT i.municipality,r.zip,r.street,r.address_number,r.neighborhood FROM issuer i JOIN issuer_registration r ON r.issuer_id=i.id AND r.tenant_id=i.tenant_id WHERE i.tenant_id=? AND i.cnpj=?",(r,n)-> {
            String zip=Objects.toString(r.getString("zip"),"").replaceAll("[^0-9]","");
            String street=r.getString("street"),number=r.getString("address_number"),neighborhood=r.getString("neighborhood");
            if(!zip.matches("[0-9]{8}")||street==null||street.isBlank()||number==null||number.isBlank()||neighborhood==null||neighborhood.isBlank()||street.length()>255||number.length()>60||neighborhood.length()>60)return null;
            return new NfseModels.Address(r.getString("municipality"),zip,street,number,neighborhood);
        },a.tenant(),taxId).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    @PostMapping("/issuers/{id}/recipients") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public SavedRecipient createRecipient(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody NfseModels.Recipient input) {
        issuer(a,id,true);
        if(!NationalXml.validTaxId(input.taxId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CPF/CNPJ do tomador inválido");
        if(db.queryForObject("SELECT COUNT(*) FROM recipient r JOIN recipient_issuer l ON l.recipient_id=r.id AND l.tenant_id=r.tenant_id WHERE l.tenant_id=? AND l.issuer_id=? AND r.tax_id=?",Integer.class,a.tenant(),id,input.taxId())>0)
            throw new DataIntegrityViolationException("CPF/CNPJ já vinculado ao prestador");
        var key=UUID.randomUUID();
        db.update("INSERT INTO recipient(id,tenant_id,issuer_id,tax_id,name,email,address_json) VALUES(?,?,?,?,?,?,?)",key,a.tenant(),id,input.taxId(),input.name().strip(),input.email(),input.address()==null?null:serialize(input.address()));
        db.update("INSERT INTO recipient_issuer(tenant_id,recipient_id,issuer_id) VALUES(?,?,?)",a.tenant(),key,id);
        return new SavedRecipient(key,input);
    }
    @PutMapping("/issuers/{id}/recipients/{recipientId}") @Transactional
    public SavedRecipient updateRecipient(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@PathVariable UUID recipientId,@Valid @RequestBody NfseModels.Recipient input) {
        issuer(a,id,false);
        if(db.queryForList("SELECT id FROM recipient WHERE tenant_id=? AND id=? FOR UPDATE",UUID.class,a.tenant(),recipientId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!NationalXml.validTaxId(input.taxId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CPF/CNPJ do tomador inválido");
        if(db.queryForObject("SELECT COUNT(*) FROM recipient_issuer WHERE tenant_id=? AND issuer_id=? AND recipient_id=?",Integer.class,a.tenant(),id,recipientId)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!a.role().equals("GESTOR") && db.queryForObject("SELECT COUNT(*) FROM recipient_issuer WHERE tenant_id=? AND recipient_id=?",Integer.class,a.tenant(),recipientId)>1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tomador compartilhado: solicite ao contador a edição do cadastro");
        var linkedIssuers=db.queryForList("SELECT issuer_id FROM recipient_issuer WHERE tenant_id=? AND recipient_id=? ORDER BY issuer_id",UUID.class,a.tenant(),recipientId);
        for(var linked:linkedIssuers) issuer(a,linked,true);
        for(var linked:linkedIssuers) {
            if(db.queryForObject("SELECT COUNT(*) FROM recipient r JOIN recipient_issuer l ON l.recipient_id=r.id AND l.tenant_id=r.tenant_id WHERE l.tenant_id=? AND l.issuer_id=? AND r.tax_id=? AND r.id<>?",Integer.class,a.tenant(),linked,input.taxId(),recipientId)>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"CPF/CNPJ já cadastrado em um dos prestadores vinculados");
        }
        int count=db.update("UPDATE recipient SET tax_id=?,name=?,email=?,address_json=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
            input.taxId(),input.name().strip(),input.email(),input.address()==null?null:serialize(input.address()),a.tenant(),recipientId);
        if(count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new SavedRecipient(recipientId,input);
    }

    public record RecipientCatalog(UUID id,NfseModels.Recipient values,List<UUID> issuerIds) {}
    public record RecipientLinks(@NotEmpty @Size(max=100) List<@NotNull UUID> issuerIds) {}
    @GetMapping("/recipients")
    public List<RecipientCatalog> recipientCatalog(@AuthenticationPrincipal Security.Account a) {
        manager(a);
        var result=new LinkedHashMap<UUID,RecipientCatalog>();
        for(var issuer:issuers(a)) for(var row:recipients(a,issuer.id())) {
            var entry=result.computeIfAbsent(row.id(),key->new RecipientCatalog(key,row.values(),new ArrayList<>()));
            entry.issuerIds().add(issuer.id());
        }
        return result.values().stream().sorted(Comparator.comparing(row->row.values().name())).toList();
    }
    @PostMapping("/recipients/{recipientId}/issuers") @Transactional
    public Map<String,Integer> linkRecipient(@AuthenticationPrincipal Security.Account a,@PathVariable UUID recipientId,@Valid @RequestBody RecipientLinks input) {
        manager(a);
        // Serialize link and edit operations on this recipient, and scope the source to the office.
        var source=db.queryForList("SELECT tax_id FROM recipient WHERE tenant_id=? AND id=? FOR UPDATE",String.class,a.tenant(),recipientId);
        if(source.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tomador não encontrado");
        int added=0;
        for(var target:new TreeSet<>(input.issuerIds())) {
            issuer(a,target,true);
            if(db.queryForObject("SELECT COUNT(*) FROM recipient_issuer WHERE tenant_id=? AND recipient_id=? AND issuer_id=?",Integer.class,a.tenant(),recipientId,target)>0)continue;
            if(db.queryForObject("SELECT COUNT(*) FROM recipient r JOIN recipient_issuer l ON l.recipient_id=r.id AND l.tenant_id=r.tenant_id WHERE l.tenant_id=? AND l.issuer_id=? AND r.tax_id=?",Integer.class,a.tenant(),target,source.getFirst())>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Um dos prestadores já possui outro cadastro com este CPF/CNPJ. Revise os cadastros antes de vincular");
            db.update("INSERT INTO recipient_issuer(tenant_id,recipient_id,issuer_id) VALUES(?,?,?)",a.tenant(),recipientId,target);added++;
        }
        return Map.of("linked",added);
    }

    private UUID scopedIssuer(Security.Account a, UUID requested) {
        if (requested != null) { issuer(a,requested,false); return requested; }
        return a.issuer();
    }
    private Draft readDraft(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        return new Draft(r.getObject("id",UUID.class),r.getObject("issuer_id",UUID.class),
            r.getObject("competence",LocalDate.class),r.getBigDecimal("amount"),r.getString("status"),snapshot(r.getString("snapshot")));
    }
    @GetMapping("/drafts")
    public DraftPage drafts(@AuthenticationPrincipal Security.Account a,
        @RequestParam(required=false) UUID issuerId,
        @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || (from != null && to != null && from.isAfter(to)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Paginação ou período inválido");
        UUID selected=scopedIssuer(a,issuerId);
        var params=new ArrayList<Object>(); params.add(a.tenant());
        String where=" WHERE tenant_id=?";
        if (selected!=null) { where+=" AND issuer_id=?"; params.add(selected); }
        if (from!=null) { where+=" AND competence>=?"; params.add(from); }
        if (to!=null) { where+=" AND competence<=?"; params.add(to); }
        long total=db.queryForObject("SELECT COUNT(*) FROM draft"+where,Long.class,params.toArray());
        params.add(size); params.add((long)page*size);
        var items=db.query("SELECT * FROM draft"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::readDraft,params.toArray());
        return new DraftPage(items,total,page,size);
    }
    @GetMapping("/dashboard")
    public Dashboard dashboard(@AuthenticationPrincipal Security.Account a,
        @RequestParam(required=false) UUID issuerId, @RequestParam LocalDate referenceDate) {
        UUID selected=scopedIssuer(a,issuerId);
        Object[] params=selected==null?new Object[]{a.tenant()}:new Object[]{a.tenant(),selected};
        String where=" WHERE tenant_id=?"+(selected==null?"":" AND issuer_id=?");
        long companies=db.queryForObject("SELECT COUNT(*) FROM issuer WHERE tenant_id=?"+(a.issuer()==null?"":" AND id=?"),Long.class,
            a.issuer()==null?new Object[]{a.tenant()}:new Object[]{a.tenant(),a.issuer()});
        long count=db.queryForObject("SELECT COUNT(*) FROM draft"+where,Long.class,params);
        var amount=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM draft"+where,BigDecimal.class,params);
        var monthParams=new ArrayList<>(Arrays.asList(params));
        monthParams.add(referenceDate.withDayOfMonth(1)); monthParams.add(referenceDate.withDayOfMonth(1).plusMonths(1));
        String monthWhere=where+" AND competence>=? AND competence<?";
        long monthCount=db.queryForObject("SELECT COUNT(*) FROM draft"+monthWhere,Long.class,monthParams.toArray());
        var monthAmount=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM draft"+monthWhere,BigDecimal.class,monthParams.toArray());
        return new Dashboard(companies,count,amount,monthCount,monthAmount);
    }

    private void manager(Security.Account account) {
        if (!account.role().equals("GESTOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Exige perfil gestor");
    }
    Issuer issuer(Security.Account account, UUID id, boolean lock) {
        if (account.issuer() != null && !account.issuer().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return db.query("SELECT * FROM issuer WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            (r,n) -> new Issuer(r.getObject("id",UUID.class),r.getString("cnpj"),r.getString("name"),r.getString("municipality")),
            account.tenant(),id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping(value="/municipalities",produces="application/json")
    public org.springframework.core.io.Resource municipalityCatalog() {
        return new org.springframework.core.io.ClassPathResource("registry/municipalities.json");
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Security.Account a) {
        return Map.of("username",a.username(),"tenantId",a.tenant(),"role",a.role(),"mode","NFSE_NATIONAL");
    }
    @PostMapping("/issuers") @ResponseStatus(HttpStatus.CREATED)
    public Issuer createIssuer(@AuthenticationPrincipal Security.Account a, @Valid @RequestBody IssuerInput input) {
        manager(a); var id = UUID.randomUUID();
        db.update("INSERT INTO issuer(id,tenant_id,cnpj,name,municipality) VALUES(?,?,?,?,?)",id,a.tenant(),input.cnpj(),input.name(),input.municipality());
        return new Issuer(id,input.cnpj(),input.name(),input.municipality());
    }
    @GetMapping("/issuers") public List<Issuer> issuers(@AuthenticationPrincipal Security.Account a) {
        return db.query("SELECT * FROM issuer WHERE tenant_id=?" + (a.issuer()==null ? "" : " AND id=?"),
            (r,n) -> new Issuer(r.getObject("id",UUID.class),r.getString("cnpj"),r.getString("name"),r.getString("municipality")),
            a.issuer()==null ? new Object[]{a.tenant()} : new Object[]{a.tenant(),a.issuer()});
    }
    @PostMapping("/issuers/{id}/defaults") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public FiscalDefaults defaults(@AuthenticationPrincipal Security.Account a, @PathVariable UUID id, @Valid @RequestBody DefaultsInput v) {
        manager(a); issuer(a,id,true);
        Integer next = db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM fiscal_defaults WHERE tenant_id=? AND issuer_id=?",Integer.class,a.tenant(),id);
        var key=UUID.randomUUID();
        db.update("INSERT INTO fiscal_defaults(id,tenant_id,issuer_id,version,effective_from,lc116,national_service,cnae,description,iss_rate,irrf_rate,pis_rate,cofins_rate,csll_rate,inss_rate,iss_withheld,variant_name,nbs) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            key,a.tenant(),id,next,v.effectiveFrom(),v.lc116(),v.nationalService(),v.cnae(),v.description(),v.issRate(),v.irrfRate(),v.pisRate(),v.cofinsRate(),v.csllRate(),v.inssRate(),v.issWithheld(),v.variantName(),v.nbs());
        return new FiscalDefaults(key,next,v);
    }
    @PutMapping("/issuers/{id}/defaults/{defaultsId}") @Transactional
    public FiscalDefaults updateDefaults(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,
        @PathVariable UUID defaultsId,@RequestParam int version,@Valid @RequestBody DefaultsInput v) {
        manager(a); issuer(a,id,true);
        var current=db.queryForList("SELECT version FROM fiscal_defaults WHERE tenant_id=? AND issuer_id=? AND id=?",Integer.class,a.tenant(),id,defaultsId);
        if(current.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Configuração fiscal não encontrada");
        if(current.getFirst()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuração alterada por outro usuário. Atualize a tela antes de editar novamente");
        Integer next=db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM fiscal_defaults WHERE tenant_id=? AND issuer_id=?",Integer.class,a.tenant(),id);
        db.update("UPDATE fiscal_defaults SET version=?,effective_from=?,lc116=?,national_service=?,cnae=?,description=?,iss_rate=?,irrf_rate=?,pis_rate=?,cofins_rate=?,csll_rate=?,inss_rate=?,iss_withheld=?,variant_name=?,nbs=? WHERE tenant_id=? AND issuer_id=? AND id=?",
            next,v.effectiveFrom(),v.lc116(),v.nationalService(),v.cnae(),v.description(),v.issRate(),v.irrfRate(),v.pisRate(),v.cofinsRate(),v.csllRate(),v.inssRate(),v.issWithheld(),v.variantName(),v.nbs(),a.tenant(),id,defaultsId);
        return new FiscalDefaults(defaultsId,next,v);
    }
    private FiscalDefaults resolve(Security.Account a, UUID id, LocalDate date,String variantName) {
        return db.query("SELECT * FROM fiscal_defaults WHERE tenant_id=? AND issuer_id=? AND effective_from<=? AND variant_name=? ORDER BY effective_from DESC FETCH FIRST 1 ROW ONLY",
            (r,n) -> new FiscalDefaults(r.getObject("id",UUID.class),r.getInt("version"),new DefaultsInput(r.getObject("effective_from",LocalDate.class),
                r.getString("lc116"),r.getString("national_service"),r.getString("cnae"),r.getString("description"),r.getBigDecimal("iss_rate"),
                r.getBigDecimal("irrf_rate"),r.getBigDecimal("pis_rate"),r.getBigDecimal("cofins_rate"),r.getBigDecimal("csll_rate"),r.getBigDecimal("inss_rate"),r.getBoolean("iss_withheld"),r.getString("variant_name"),r.getString("nbs"))),
            a.tenant(),id,date,variantName).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Sem padrão vigente na competência"));
    }
    @GetMapping("/issuers/{id}/defaults")
    public FiscalDefaults effectiveDefaults(@AuthenticationPrincipal Security.Account a, @PathVariable UUID id,
        @RequestParam LocalDate competence,@RequestParam(defaultValue="Padrão") String variantName) {
        issuer(a,id,false); return resolve(a,id,competence,variantName);
    }
    public FiscalDefaults effectiveDefaults(Security.Account a,UUID id,LocalDate competence) { return effectiveDefaults(a,id,competence,"Padrão"); }
    @GetMapping("/issuers/{id}/services")
    public List<FiscalDefaults> services(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@RequestParam LocalDate competence) {
        issuer(a,id,false);
        return db.queryForList("SELECT DISTINCT variant_name FROM fiscal_defaults WHERE tenant_id=? AND issuer_id=? AND effective_from<=? ORDER BY variant_name",String.class,a.tenant(),id,competence)
            .stream().map(name->resolve(a,id,competence,name)).toList();
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private Snapshot snapshot(String text) {
        try { return json.readValue(text,Snapshot.class); } catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(value).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @PostMapping("/drafts") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Draft draft(@AuthenticationPrincipal Security.Account a, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DraftInput v) {
        if (!key.matches("[a-zA-Z0-9_-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key inválida");
        issuer(a,v.issuerId(),true);
        var previous=db.queryForList("SELECT id,request_hash FROM draft WHERE tenant_id=? AND idempotency_key=?",a.tenant(),key);
        if (!previous.isEmpty()) {
            if (!hash(v).equals(previous.getFirst().get("REQUEST_HASH"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Chave reutilizada com outro conteúdo");
            return getDraft(a,(UUID)previous.getFirst().get("ID"));
        }
        var defaults=resolve(a,v.issuerId(),v.competence(),v.variantName());
        boolean override=v.description()!=null && !v.description().equals(defaults.values().description());
        if (override && (v.overrideReason()==null || v.overrideReason().isBlank() || v.description().isBlank()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Alteração da descrição exige texto e justificativa");
        String description=override?v.description():defaults.values().description();
        InvoiceForm.Totals totals=null;FiscalDefaults source=null;
        if(v.commercial()!=null) {
            totals=InvoiceForm.calculate(v.commercial());
            if(totals.gross().compareTo(v.amount())!=0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Valor informado difere da soma dos itens");
            var applied=InvoiceForm.applied(defaults.values(),v.commercial());
            if(InvoiceForm.changed(defaults.values(),applied)) {
                if(v.overrideReason()==null||v.overrideReason().isBlank())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Alterar retenções ou alíquotas do padrão exige justificativa");
                source=defaults;defaults=new FiscalDefaults(defaults.id(),defaults.version(),applied);
            }
            description=InvoiceForm.itemDescription(description,v.commercial());
        }
        var snapshot=new Snapshot(defaults,description,override?"MANUAL":v.commercial()!=null?"SERVICE_ITEMS":"ISSUER_DEFAULT",v.overrideReason(),a.username(),v.commercial(),totals,source);
        var id=UUID.randomUUID();
        db.update("INSERT INTO draft(id,tenant_id,issuer_id,defaults_id,idempotency_key,request_hash,competence,amount,status,snapshot) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,a.tenant(),v.issuerId(),defaults.id(),key,hash(v),v.competence(),v.amount(),"DRAFT",serialize(snapshot));
        return new Draft(id,v.issuerId(),v.competence(),v.amount(),"DRAFT",snapshot);
    }
    @GetMapping("/drafts/{id}") public Draft getDraft(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id) {
        return db.query("SELECT * FROM draft WHERE tenant_id=? AND id=?"+(a.issuer()==null?"":" AND issuer_id=?"),
            (r,n)->new Draft(r.getObject("id",UUID.class),r.getObject("issuer_id",UUID.class),r.getObject("competence",LocalDate.class),r.getBigDecimal("amount"),r.getString("status"),snapshot(r.getString("snapshot"))),
            a.issuer()==null?new Object[]{a.tenant(),id}:new Object[]{a.tenant(),id,a.issuer()})
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @ExceptionHandler(DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> conflict() { return Map.of("error","Cadastro, vigência ou chave já existente; consulte o recurso antes de tentar novamente"); }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> businessError(ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("error",ex.getReason()==null?"Recurso não encontrado ou indisponível":ex.getReason()));
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> validationError(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        var fields=new LinkedHashMap<String,String>();
        ex.getBindingResult().getFieldErrors().forEach(e->fields.putIfAbsent(e.getField(),e.getDefaultMessage()==null?"Valor inválido":e.getDefaultMessage()));
        return Map.of("error","Revise os campos informados", "fields",fields);
    }
}
