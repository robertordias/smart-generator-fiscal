package br.com.fiscal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IssuerCsvImport {
    static final int MAX_BYTES=1024*1024, MAX_ROWS=5000;
    static final String[] HEADERS={"Código","Razão Social","Nome Fantasia","CNPJ/CPF","Tipo Tributação","Responsável","IE","IM","CEI","Tipo Empresa","Tipo de Contribuição","Incidência Tributária","Apropriação de Crédito","Número do Livro","Grupo","Tributação Real","Início da Atividade","Número do Órgão","Órgão de Registro","Desmem. CNAEF","Inscrição Suframa","CNAEF","Data Empresarial","Situação Empresa","Logradouro","Número","Complemento","CEP","Bairro","Cidade","UF","Telefone","E-mail"};
    public record Registration(String tradeName,String stateRegistration,String municipalRegistration,String cnae,
        String taxationReference,String street,String number,String complement,String zip,String neighborhood,
        String city,String uf,String phone,String email) {}
    public record Row(int line,String name,String cnpj,String municipality,Registration registration,String status,List<String> messages,Map<String,String> fieldErrors,String rawLine) {}
    public record Result(int total,int ready,int imported,int skipped,int invalid,List<Row> rows) {}
    record Municipality(String id,String name,String uf) {}
    private static final Map<String,Integer> COLUMNS=Map.ofEntries(
        Map.entry("name",1),Map.entry("tradeName",2),Map.entry("cnpj",3),Map.entry("taxationReference",4),
        Map.entry("stateRegistration",6),Map.entry("municipalRegistration",7),Map.entry("cnae",21),
        Map.entry("street",24),Map.entry("number",25),Map.entry("complement",26),Map.entry("zip",27),
        Map.entry("neighborhood",28),Map.entry("city",29),Map.entry("uf",30),Map.entry("phone",31),Map.entry("email",32));
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final Map<String,String> municipalities=new HashMap<>();
    public IssuerCsvImport(JdbcTemplate db,ObjectMapper json) throws IOException {
        this.db=db;this.json=json;
        try(var in=new ClassPathResource("registry/municipalities.json").getInputStream()) {
            for(var m:json.readValue(in,new TypeReference<List<Municipality>>(){})) municipalities.put(key(m.name(),m.uf()),m.id());
        }
    }
    private static String normalized(String text) {
        return Normalizer.normalize(text.replace("\ufeff",""),Normalizer.Form.NFD).replaceAll("\\p{M}","").strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
    }
    private static String key(String city,String uf) { return normalized(uf)+"/"+normalized(city); }
    private static String digits(String text) { return text.replaceAll("[^0-9]",""); }
    private static ResponseStatusException bad(String reason) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
    static void manager(Security.Account a) {
        if(!"GESTOR".equals(a.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Importação exige perfil gestor");
    }
    public Result preview(Security.Account account,byte[] bytes) { return preview(account,bytes,Map.of()); }
    public Result preview(Security.Account account,byte[] bytes,Map<Integer,Map<String,String>> corrections) {
        manager(account);return inspect(account,bytes,corrections);
    }
    Map<Integer,Map<String,String>> readCorrections(String text) {
        if(text.length()>MAX_BYTES)throw bad("Correções excedem o limite de 1 MB");
        try {
            var result=json.readValue(text,new TypeReference<Map<Integer,Map<String,String>>>(){});
            if(result==null)throw bad("Correções inválidas");
            return result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw bad("Correções inválidas");}
    }

    @Transactional
    public Result save(Security.Account account,byte[] bytes) { return save(account,bytes,Map.of()); }
    @Transactional
    public Result save(Security.Account account,byte[] bytes,Map<Integer,Map<String,String>> corrections) {
        manager(account);
        var preview=inspect(account,bytes,corrections);
        var output=new ArrayList<Row>();
        int imported=0;
        for(var row:preview.rows()) {
            if(!row.status().equals("READY")) {output.add(row);continue;}
            var id=UUID.randomUUID();
            db.update("INSERT INTO issuer(id,tenant_id,cnpj,name,municipality) VALUES(?,?,?,?,?)",id,account.tenant(),row.cnpj(),row.name(),row.municipality());
            var r=row.registration();
            db.update("INSERT INTO issuer_registration(tenant_id,issuer_id,trade_name,state_registration,municipal_registration,cnae,taxation_reference,street,address_number,complement,zip,neighborhood,city,uf,phone,email) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                account.tenant(),id,r.tradeName(),r.stateRegistration(),r.municipalRegistration(),r.cnae(),r.taxationReference(),r.street(),r.number(),r.complement(),r.zip(),r.neighborhood(),r.city(),r.uf(),r.phone(),r.email());
            imported++;
            output.add(new Row(row.line(),row.name(),row.cnpj(),row.municipality(),r,"IMPORTED",row.messages(),Map.of(),""));
        }
        return new Result(preview.total(),0,imported,preview.skipped(),preview.invalid(),output);
    }
    private Result inspect(Security.Account account,byte[] bytes,Map<Integer,Map<String,String>> corrections) {
        if(bytes.length==0 || bytes.length>MAX_BYTES)throw bad("Selecione um CSV de até 1 MB");
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException ex) {text=Charset.forName("windows-1252").decode(ByteBuffer.wrap(bytes)).toString();}
        var records=parse(text);
        if(records.isEmpty())throw bad("CSV vazio");
        var header=records.getFirst();
        if(header.size()!=HEADERS.length)throw bad("O CSV deve conter as 33 colunas do modelo, separadas por ponto e vírgula");
        for(int i=0;i<HEADERS.length;i++)if(!normalized(header.get(i)).equals(normalized(HEADERS[i])))
            throw bad("Coluna "+(i+1)+" deve ser "+HEADERS[i]+". Mantenha a ordem do modelo");
        if(records.size()==1)throw bad("O CSV contém apenas o cabeçalho");
        if(corrections==null || corrections.size()>MAX_ROWS)throw bad("Correções inválidas");
        for(var correction:corrections.entrySet()) {
            if(correction.getKey()==null || correction.getKey()<2 || correction.getKey()>records.size() || correction.getValue()==null)
                throw bad("Linha de correção inexistente");
            for(var entry:correction.getValue().entrySet())
                if((!COLUMNS.containsKey(entry.getKey()) && !"layout".equals(entry.getKey())) || entry.getValue()==null || entry.getValue().length()>65536)
                    throw bad("Campo de correção inválido");
        }
        Set<String> existing=new HashSet<>(db.queryForList("SELECT cnpj FROM issuer WHERE tenant_id=?",String.class,account.tenant()));
        Set<String> seen=new HashSet<>();
        var output=new ArrayList<Row>();
        int ready=0,skipped=0,invalid=0;
        for(int i=1;i<records.size();i++) {
            int line=i+1;
            var changes=corrections.getOrDefault(line,Map.of());
            var c=new ArrayList<>(records.get(i));
            if(changes.containsKey("layout")) {
                var replacement=parse(changes.get("layout"));
                if(replacement.size()!=1)throw bad("A correção estrutural deve conter exatamente um registro CSV");
                c=new ArrayList<>(replacement.getFirst());
            }
            if(c.size()!=33) {
                String message="Esperadas 33 colunas; recebidas "+c.size();
                String raw=String.join(";",c.stream().map(v->"\""+v.replace("\"","\"\"")+"\"").toList());
                output.add(new Row(line,"","","",null,"INVALID",List.of(message),Map.of("layout",message),raw));invalid++;continue;
            }
            for(var entry:changes.entrySet())if(COLUMNS.containsKey(entry.getKey()))c.set(COLUMNS.get(entry.getKey()),entry.getValue().strip());
            String name=c.get(1),taxId=digits(c.get(3)),city=c.get(29),uf=c.get(30).toUpperCase(Locale.ROOT);
            String municipality=municipalities.getOrDefault(key(city,uf),"");
            var r=new Registration(c.get(2),c.get(6),c.get(7),digits(c.get(21)),c.get(4),c.get(24),c.get(25),c.get(26),digits(c.get(27)),c.get(28),city,uf,c.get(31),c.get(32));
            var errors=new LinkedHashMap<String,String>();var notices=new ArrayList<String>();
            if(name.isBlank() || name.length()>160)errors.put("name","Razão social obrigatória, até 160 caracteres");
            if(!validCnpj(taxId))errors.put("cnpj",taxId.length()==11?"CPF não suportado no cadastro de emitentes; informe CNPJ":"CNPJ inválido");
            if(municipality.isEmpty()) {errors.put("city","Cidade/UF não encontrada no catálogo IBGE");errors.put("uf","Confira a UF do município");}
            if(!r.cnae().isEmpty() && !r.cnae().matches("[0-9]{7}"))errors.put("cnae","CNAE deve ter 7 dígitos");
            if(!r.zip().isEmpty() && !r.zip().matches("[0-9]{8}"))errors.put("zip","CEP deve ter 8 dígitos");
            if(!r.email().isEmpty() && !r.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))errors.put("email","E-mail inválido");
            String[] values={r.tradeName(),r.stateRegistration(),r.municipalRegistration(),r.taxationReference(),r.street(),r.number(),r.complement(),r.neighborhood(),r.city(),r.phone(),r.email()};
            int[] limits={160,30,30,200,255,60,160,100,100,30,254};
            String[] fields={"tradeName","stateRegistration","municipalRegistration","taxationReference","street","number","complement","neighborhood","city","phone","email"};
            for(int j=0;j<values.length;j++)if(values[j].length()>limits[j])errors.put(fields[j],"Máximo de "+limits[j]+" caracteres");
            if(r.municipalRegistration().isBlank())notices.add("Inscrição municipal ausente; revisar antes da emissão");
            if(r.cnae().isBlank())notices.add("CNAE ausente; configurar antes da emissão");
            String status;
            if(existing.contains(taxId)) {status="SKIPPED";skipped++;notices.add("CNPJ já cadastrado: dados e padrões fiscais preservados");}
            else if(!errors.isEmpty()) {status="INVALID";invalid++;notices.addAll(0,errors.values());}
            else if(!seen.add(taxId)) {status="SKIPPED";skipped++;notices.add("CNPJ repetido no arquivo");}
            else {status="READY";ready++;}
            output.add(new Row(line,name,taxId,municipality,r,status,notices,status.equals("INVALID")?errors:Map.of(),""));
        }
        return new Result(output.size(),ready,0,skipped,invalid,output);
    }
    static boolean validCnpj(String s) {
        if(!s.matches("[0-9]{14}") || s.chars().distinct().count()==1)return false;
        for(int size=12;size<=13;size++) {
            int sum=0,weight=size-7;
            for(int i=0;i<size;i++) {sum+=(s.charAt(i)-'0')*weight;if(--weight==1)weight=9;}
            int digit=sum%11<2?0:11-sum%11;
            if(s.charAt(size)-'0'!=digit)return false;
        }
        return true;
    }
    // RFC-style quoted fields, escaped quotes, CRLF and embedded line breaks; fixed separator ';'.
    static List<List<String>> parse(String text) {
        text=text.replaceFirst("^\\ufeff+","");
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<text.length() && text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}
                else field.append(c);
            } else if(c==';' || c=='\r' || c=='\n') {
                row.add(field.toString().strip());field.setLength(0);closed=false;
                if(c!=';') {
                    if(row.stream().anyMatch(v->!v.isBlank()))rows.add(List.copyOf(row));row.clear();
                    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;
                    if(rows.size()>MAX_ROWS+1)throw bad("Limite de 5.000 empresas por arquivo");
                }
            } else if(c=='"' && field.isEmpty() && !closed)quoted=true;
            else if(closed && !Character.isWhitespace(c))throw bad("CSV malformado após campo entre aspas");
            else if(c=='"')throw bad("Aspas inválidas no CSV");
            else if(!closed)field.append(c);
        }
        if(quoted)throw bad("CSV com aspas não fechadas");
        row.add(field.toString().strip());
        if(row.stream().anyMatch(v->!v.isBlank()))rows.add(List.copyOf(row));
        if(rows.size()>MAX_ROWS+1)throw bad("Limite de 5.000 empresas por arquivo");
        return rows;
    }
}
