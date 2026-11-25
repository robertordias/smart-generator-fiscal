package br.com.fiscal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLParameters;
import org.springframework.stereotype.Component;

@Component
class HttpNationalGateway implements NationalGateway {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(HttpNationalGateway.class);
    private final NfseSettings settings;
    private final ObjectMapper json;
    HttpNationalGateway(NfseSettings settings,ObjectMapper json){this.settings=settings;this.json=json;}
    @Override public Reply exchange(String environment,String method,String path,String payload,CertificateVault.Material material) throws Exception {
        settings.enabled(environment);
        var ssl=new SSLParameters();ssl.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});ssl.setEndpointIdentificationAlgorithm("HTTPS");
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(material.sslContext()).sslParameters(ssl).connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request=HttpRequest.newBuilder(settings.endpoint(environment,path)).timeout(Duration.ofSeconds(45)).header("Accept","application/json")
                .header("User-Agent","SA-Trindade/0.2");
            if(method.equals("POST"))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8));else request.GET();
            HttpResponse<byte[]> response;
            try {response=client.send(request.build(),info->new LimitedBody());}
            catch(java.io.IOException ex) {
                String detail=String.valueOf(ex.getMessage()).replaceAll("[\\r\\n]"," ").replaceAll("[0-9]{11,}","[identificador]");
                log.warn("Falha de transporte NFS-e: {}: {}",ex.getClass().getSimpleName(),detail.substring(0,Math.min(300,detail.length())));
                throw ex;
            }
            try{return new Reply(response.statusCode(),json.readTree(response.body()));}
            catch(Exception ex){return new Reply(response.statusCode(),json.createObjectNode().put("transportError","Resposta da API fora do formato JSON esperado"));}
        }
    }
    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private int length;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers)length+=buffer.remaining();
            if(length>4_000_000){subscription.cancel();delegate.onError(new java.io.IOException("Resposta fiscal muito grande"));return;}delegate.onNext(buffers);
        }
        public void onError(Throwable error){delegate.onError(error);}public void onComplete(){delegate.onComplete();}
    }
}
