package br.com.fiscal;

/** Classifies failures without exposing credentials or raw remote responses. */
final class FiscalConnectionError {
    record Detail(String code,String message) {}
    static Detail describe(Throwable error) {
        for(Throwable current=error;current!=null;current=current.getCause()) {
            if(current instanceof javax.net.ssl.SSLException) return new Detail("TLS_FAILURE","Não foi possível estabelecer a conexão segura com o Fisco. Verifique a cadeia TLS e a aceitação do A1 pelo serviço");
            if(current instanceof java.net.UnknownHostException || current instanceof java.nio.channels.UnresolvedAddressException) return new Detail("DNS_FAILURE","O servidor não conseguiu resolver o endereço do Fisco");
            if(current instanceof java.net.http.HttpTimeoutException || current instanceof java.net.SocketTimeoutException) return new Detail("FISCO_TIMEOUT","O serviço do Fisco não respondeu dentro do prazo");
            if(current instanceof java.net.ConnectException) return new Detail("CONNECTION_FAILURE","Não foi possível conectar ao serviço do Fisco");
        }
        return new Detail("RESPONSE_UNCONFIRMED","Falha ao comunicar ou validar o retorno do Fisco");
    }
}
