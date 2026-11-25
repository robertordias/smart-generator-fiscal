package br.com.fiscal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        CertificateValidation.initialize();
        if(java.util.Arrays.asList(args).contains("--migrate-h2")){DatabaseTransfer.run();return;}
        SpringApplication.run(Application.class, args);
    }
}
