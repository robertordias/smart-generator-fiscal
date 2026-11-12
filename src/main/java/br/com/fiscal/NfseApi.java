package br.com.fiscal;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static br.com.fiscal.NfseModels.*;

@RestController
@RequestMapping("/api/nfse")
class NfseApi {
    private final NfseService service;private final CertificateVault vault;private final NfseSettings settings;
    NfseApi(NfseService service,CertificateVault vault,NfseSettings settings){this.service=service;this.vault=vault;this.settings=settings;}
    @GetMapping("/config") Map<String,Object> config(){return settings.describe();}
    @GetMapping("/certificates") List<CertificateVault.Metadata> certificates(@AuthenticationPrincipal Security.Account a){return vault.list(a);}
    @PostMapping("/certificates") @ResponseStatus(HttpStatus.CREATED)
    CertificateVault.Metadata certificate(@AuthenticationPrincipal Security.Account a,@Valid @RequestBody CertificateInput input){
        byte[] bytes;try{bytes=Base64.getDecoder().decode(input.pfxBase64());}catch(IllegalArgumentException ex){throw NfseSettings.bad("Arquivo A1 em Base64 inválido");}
        return vault.save(a,input.label(),input.holderTaxId(),bytes,input.password().toCharArray());
    }
    @DeleteMapping("/certificates/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){vault.revoke(a,id);}
    @GetMapping("/issuers/{id}/proxy") Proxy proxy(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.proxy(a,id);}
    @PutMapping("/issuers/{id}/proxy") Proxy proxy(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody ProxyInput input){return service.saveProxy(a,id,input);}
    @GetMapping("/issuers/{id}/profile") Profile profile(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.profile(a,id);}
    @PutMapping("/issuers/{id}/profile") Profile profile(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody Profile input){return service.saveProfile(a,id,input);}
    @PostMapping("/prepare") @ResponseStatus(HttpStatus.CREATED)
    Note prepare(@AuthenticationPrincipal Security.Account a,@Valid @RequestBody Prepare input){return service.prepare(a,input);}
    @GetMapping("/notes") NotePage list(@AuthenticationPrincipal Security.Account a,@RequestParam(required=false) UUID issuerId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
        @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String environment,
        @RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to,
        @RequestParam(defaultValue="") String search){return service.list(a,issuerId,page,size,status,environment,from,to,search);}
    @GetMapping("/notes/{id}/sharing") NfseService.Sharing sharing(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.sharing(a,id);}
    @GetMapping("/notes/{id}") Note note(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.get(a,id);}
    @PostMapping("/notes/{id}/transmit") Note transmit(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody Confirm input){return service.transmit(a,id,input);}
    @PostMapping("/notes/{id}/query") Note query(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.reconcile(a,id);}
    @PostMapping("/notes/{id}/cancel") Note cancel(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody Cancel input){return service.cancel(a,id,input);}
    @PostMapping("/notes/{id}/retry-cancel") Note retryCancel(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@Valid @RequestBody Confirm input){return service.retryCancel(a,id,input);}
    @GetMapping("/notes/{id}/history") List<Map<String,Object>> history(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id){return service.history(a,id);}
    @GetMapping("/notes/{id}/files/{kind}") ResponseEntity<byte[]> file(@AuthenticationPrincipal Security.Account a,@PathVariable UUID id,@PathVariable String kind){
        byte[] content=service.artifact(a,id,kind).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+kind+"-"+id+".xml\"").body(content);
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<Map<String,String>> business(org.springframework.web.server.ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(error.getReason(),"Recurso indisponível")));}
}
