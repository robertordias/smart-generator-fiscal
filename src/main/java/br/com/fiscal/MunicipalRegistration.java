package br.com.fiscal;

import static br.com.fiscal.NfseSettings.bad;

final class MunicipalRegistration {
    private MunicipalRegistration() {}
    static String normalize(String municipality,String registration) {
        if(registration==null)return "";
        String value=registration.strip();
        if(!"3550308".equals(municipality))return value;
        value=value.replaceAll("[.\\s-]","");
        if(!value.matches("[0-9]{0,15}"))throw bad("Informe o CCM de São Paulo somente com números, sem pontos, espaços ou hífen");
        return value;
    }
}
