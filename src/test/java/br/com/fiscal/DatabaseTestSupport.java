package br.com.fiscal;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class DatabaseTestSupport {
    private static final String SCHEMA="it_"+java.util.UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry){
        String url=System.getenv("TEST_POSTGRES_URL");
        if(url!=null&&!url.isBlank()){
            if(!"true".equals(System.getenv("TEST_POSTGRES_ALLOW_WRITES")))
                throw new IllegalStateException("Os testes gravam dados. Use um banco isolado e defina TEST_POSTGRES_ALLOW_WRITES=true");
            if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("TEST_POSTGRES_URL deve apontar para PostgreSQL");
            if(url.contains("currentSchema="))throw new IllegalArgumentException("Use TEST_POSTGRES_URL sem currentSchema; os testes criam seu próprio schema isolado");
            registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
            registry.add("spring.flyway.schemas",()->SCHEMA);
            registry.add("spring.flyway.default-schema",()->SCHEMA);
            registry.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_POSTGRES_USER","fiscal"));
            registry.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD",""));
        }
    }
}
