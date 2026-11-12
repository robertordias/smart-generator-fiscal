package br.com.fiscal;

import com.fasterxml.jackson.databind.JsonNode;

interface NationalGateway {
    record Reply(int status,JsonNode body) {
        @Override public String toString(){return "FiscalReply[status="+status+", payload=PROTECTED]";}
    }
    Reply exchange(String environment,String method,String path,String payload,CertificateVault.Material material) throws Exception;
}
