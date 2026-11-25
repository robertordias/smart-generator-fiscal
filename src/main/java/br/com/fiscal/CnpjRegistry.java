package br.com.fiscal;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import static br.com.fiscal.NfseSettings.bad;

/** Public company data only; never sends an A1 or taxpayer credentials. */
@Component
class CnpjRegistry {
    record Result(String taxId,String name,String email,NfseModels.Address address,String source,Instant consultedAt) {}
    private final ObjectMapper json;
    private final ConcurrentHashMap<String,Result> cache=new ConcurrentHashMap<>();
    CnpjRegistry(ObjectMapper json){this.json=json;}
    Result lookup(String cnpj) {
        if(cnpj==null||!cnpj.matches("[0-9]{14}")||!NationalXml.validTaxId(cnpj))throw bad("Informe um CNPJ válido com 14 dígitos. A consulta não está disponível para CPF");
        var saved=cache.get(cnpj);
        if(saved!=null&&saved.consultedAt().isAfter(Instant.now().minus(Duration.ofHours(6))))return saved;
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request=HttpRequest.newBuilder(URI.create("https://brasilapi.com.br/api/cnpj/v1/"+cnpj)).timeout(Duration.ofSeconds(20)).header("Accept","application/json").header("User-Agent","SA-Trindade/0.2").GET().build();
            var response=client.send(request,info->new HttpNationalGateway.LimitedBody());
            if(response.statusCode()==404)throw bad("CNPJ não encontrado na consulta cadastral. Preencha os dados manualmente");
            if(response.statusCode()==429)throw bad("Limite de consultas atingido. Tente novamente mais tarde ou preencha manualmente");
            if(response.statusCode()!=200)throw bad("Consulta cadastral indisponível. Tente novamente ou preencha os dados manualmente");
            var result=parse(cnpj,json.readTree(response.body()));
            if(cache.size()>=500)cache.clear();cache.put(cnpj,result);return result;
        } catch(org.springframework.web.server.ResponseStatusException ex){throw ex;}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw bad("Consulta interrompida. Tente novamente");}
        catch(Exception ex){throw bad("Não foi possível consultar o CNPJ agora. Os dados digitados foram preservados; preencha manualmente ou tente novamente");}
    }
    static Result parse(String cnpj,JsonNode value) {
        if(!cnpj.equals(text(value,"cnpj",20).replaceAll("[^0-9]","")))throw bad("O serviço cadastral retornou outro CNPJ. Os dados não foram aplicados");
        String municipality=text(value,"codigo_municipio_ibge",7),zip=text(value,"cep",10).replaceAll("[^0-9]","");
        if(!municipality.matches("[0-9]{7}"))municipality="";
        if(!zip.matches("[0-9]{8}"))zip="";
        String street=(text(value,"descricao_tipo_de_logradouro",30)+" "+text(value,"logradouro",220)).strip();
        String email=text(value,"email",80);
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))email=null;
        return new Result(cnpj,text(value,"razao_social",150),email,new NfseModels.Address(municipality,zip,street,text(value,"numero",60),text(value,"bairro",60)),"BrasilAPI",Instant.now());
    }
    private static String text(JsonNode value,String field,int max) {
        String text=value.path(field).asText("").strip();return text.substring(0,Math.min(max,text.length()));
    }
}
