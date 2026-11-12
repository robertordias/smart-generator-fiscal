package br.com.fiscal;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
class NfseSettings {
    final Environment env;
    final Clock clock;
    NfseSettings(Environment env, Clock clock) { this.env=env; this.clock=clock; }
    String environment() {
        String value=env.getProperty("NFSE_ENVIRONMENT","HOMOLOGACAO");
        if (!value.equals("HOMOLOGACAO") && !value.equals("PRODUCAO")) throw bad("NFSE_ENVIRONMENT deve ser HOMOLOGACAO ou PRODUCAO");
        return value;
    }
    int tpAmb(String environment) { return environment.equals("PRODUCAO")?1:2; }
    void enabled(String environment) {
        if (!environment.equals(environment())) throw bad("Selecione no servidor o mesmo ambiente do documento");
        if (environment.equals("PRODUCAO") && !env.getProperty("NFSE_PRODUCTION_ENABLED",Boolean.class,false))
            throw bad("Emissão em produção não está habilitada no servidor");
    }
    URI endpoint(String environment, String path) {
        String host=environment.equals("PRODUCAO")?"sefin.nfse.gov.br":"sefin.producaorestrita.nfse.gov.br";
        String fallback="https://"+host+(environment.equals("PRODUCAO")?"/SefinNacional/":"/API/SefinNacional/");
        URI base=URI.create(env.getProperty(environment.equals("PRODUCAO")?"NFSE_PRODUCTION_URL":"NFSE_HOMOLOGATION_URL",fallback));
        if (!"https".equals(base.getScheme()) || !host.equals(base.getHost()) || base.getUserInfo()!=null || base.getQuery()!=null || (base.getPort()!=-1 && base.getPort()!=443))
            throw bad("O endpoint deve usar HTTPS e o domínio oficial do ambiente");
        return URI.create(base.toString().replaceAll("/+$","")+"/"+path);
    }
    Map<String,Object> describe() {
        return Map.of("environment",environment(),"productionEnabled",env.getProperty("NFSE_PRODUCTION_ENABLED",Boolean.class,false),
            "vaultConfigured",org.springframework.util.StringUtils.hasText(env.getProperty("NFSE_MASTER_KEY")),"trustConfigured",org.springframework.util.StringUtils.hasText(env.getProperty("NFSE_ICP_TRUSTSTORE")),
            "layoutVersion","1.01","officialAuthorizationTested",false);
    }
    static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,reason); }
}
