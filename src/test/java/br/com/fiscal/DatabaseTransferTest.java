package br.com.fiscal;

import java.nio.file.Path;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_MIGRATION_URL",matches="jdbc:postgresql:.*")
class DatabaseTransferTest {
    @TempDir Path directory;
    private void execute(Connection connection,String sql,Object...values)throws SQLException{
        try(var statement=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();}
    }
    private long count(Connection connection,String table)throws SQLException{
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}
    }
    @Test void transfersAllFiscalRecordsAndRollsBackOnFailureWithoutOverwritingExistingData()throws Exception{
        assertEquals("true",System.getenv("TEST_POSTGRES_ALLOW_WRITES"),"Use exclusivamente um PostgreSQL de teste vazio");
        String target=System.getenv("TEST_POSTGRES_MIGRATION_URL"),user=System.getenv().getOrDefault("TEST_POSTGRES_USER","fiscal"),password=System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","");
        String source="jdbc:h2:file:"+directory.resolve("source");
        Flyway.configure().dataSource(source,"sa","").locations("classpath:db/migration/h2","classpath:db/common").load().migrate();
        UUID tenant=UUID.randomUUID(),issuer=UUID.randomUUID(),defaults=UUID.randomUUID(),draft=UUID.randomUUID(),certificate=UUID.randomUUID(),note=UUID.randomUUID();
        byte[] encrypted=new byte[]{0,1,2,3,-1,0,42},secret=new byte[]{4,5,0,-127};
        String snapshot="{\"descrição\":\"Prestação de serviço — São Paulo\"}",official="<NFSe>ação &amp; integração</NFSe>";
        var expires=OffsetDateTime.parse("2030-01-02T23:30:00-03:00");
        try(var input=DriverManager.getConnection(source,"sa","")){
            input.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE");
            execute(input,"INSERT INTO issuer(id,tenant_id,cnpj,name,municipality) VALUES(?,?,?,?,?)",issuer,tenant,"12345678000195","Escritório São João","3550308");
            execute(input,"INSERT INTO issuer_registration(tenant_id,issuer_id,trade_name,state_registration,municipal_registration,cnae,taxation_reference,street,address_number,complement,zip,neighborhood,city,uf,phone,email) VALUES(?,?,'São João','','123','6201501','Simples nacional','Rua de teste','1','','01001000','Centro','São Paulo','SP','','teste@example.com')",tenant,issuer);
            execute(input,"INSERT INTO recipient(id,tenant_id,issuer_id,tax_id,name,email) VALUES(?,?,?,'52998224725','Tomador de teste','teste@example.com')",UUID.randomUUID(),tenant,issuer);
            execute(input,"INSERT INTO fiscal_defaults(id,tenant_id,issuer_id,version,effective_from,lc116,national_service,cnae,description,iss_rate,irrf_rate,pis_rate,cofins_rate,csll_rate,inss_rate,iss_withheld) VALUES(?,?,?,1,DATE '2026-01-01','01.01','010101','6201501','Descrição',5,0,0,0,0,0,FALSE)",defaults,tenant,issuer);
            execute(input,"INSERT INTO draft(id,tenant_id,issuer_id,defaults_id,idempotency_key,request_hash,competence,amount,status,snapshot) VALUES(?,?,?,?,'import-test','hash',DATE '2026-11-11',1000.25,'DRAFT',?)",draft,tenant,issuer,UUID.randomUUID(),snapshot);
            execute(input,"INSERT INTO fiscal_certificate(id,tenant_id,label,holder_tax_id,fingerprint,expires_at,encrypted_pfx,encrypted_password,status) VALUES(?,?,'A1 de laboratório','11222333000181',?,?,?,?, 'ACTIVE')",certificate,tenant,"f".repeat(64),expires,encrypted,secret);
            execute(input,"INSERT INTO fiscal_proxy(tenant_id,issuer_id,certificate_id,reference,valid_from,valid_to,can_issue,can_query,can_cancel,active,declared_by) VALUES(?,?,?,'PROC-TEST',DATE '2026-01-01',DATE '2030-01-01',TRUE,TRUE,TRUE,TRUE,'contador')",tenant,issuer,certificate);
            execute(input,"INSERT INTO nfse_profile(tenant_id,issuer_id,profile) VALUES(?,?,?)",tenant,issuer,snapshot);
            execute(input,"INSERT INTO nfse_sequence(tenant_id,issuer_id,environment,series,last_number) VALUES(?,?,'HOMOLOGACAO','1',123)",tenant,issuer);
            execute(input,"INSERT INTO nfse_document(id,tenant_id,issuer_id,draft_id,certificate_id,environment,dps_id,series,dps_number,status,signed_dps,request_snapshot,official_xml,created_by) VALUES(?,?,?,?,?,'HOMOLOGACAO',?,'1',123,'PREPARED',?,?,?,'contador')",note,tenant,issuer,draft,certificate,"DPS"+"1".repeat(42),official,snapshot,official);
            execute(input,"INSERT INTO nfse_audit(id,tenant_id,document_id,actor,operation,outcome,message,request_xml,response_payload) VALUES(?,?,?,'contador','PREPARE','PREPARED','Importação de teste',?,?)",UUID.randomUUID(),tenant,note,official,snapshot);
        }
        String readOnly=source+";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
        // Deliberately corrupt only the laboratory source FK: the destination transaction must roll back.
        assertThrows(SQLException.class,()->DatabaseTransfer.transfer(readOnly,"sa","",target,user,password));
        try(var output=DriverManager.getConnection(target,user,password)){
            for(String table:DatabaseTransfer.TABLES)assertEquals(0,count(output,table),table);
        }
        try(var input=DriverManager.getConnection(source,"sa","")){execute(input,"UPDATE draft SET defaults_id=? WHERE id=?",defaults,draft);}
        var totals=DatabaseTransfer.transfer(readOnly,"sa","",target,user,password);
        for(String table:DatabaseTransfer.TABLES)assertEquals(1L,totals.get(table));
        try(var output=DriverManager.getConnection(target,user,password);var statement=output.createStatement()){
            try(var rows=statement.executeQuery("SELECT encrypted_pfx,encrypted_password,expires_at FROM fiscal_certificate")){
                assertTrue(rows.next());assertArrayEquals(encrypted,rows.getBytes(1));assertArrayEquals(secret,rows.getBytes(2));assertEquals(expires.toInstant(),rows.getObject(3,OffsetDateTime.class).toInstant());
            }
            try(var rows=statement.executeQuery("SELECT snapshot,amount FROM draft")){rows.next();assertEquals(snapshot,rows.getString(1));assertEquals("1000.25",rows.getBigDecimal(2).toPlainString());}
            try(var rows=statement.executeQuery("SELECT signed_dps,official_xml FROM nfse_document")){rows.next();assertEquals(official,rows.getString(1));assertEquals(official,rows.getString(2));}
        }
        assertThrows(IllegalStateException.class,()->DatabaseTransfer.transfer(readOnly,"sa","",target,user,password));
        try(var input=DriverManager.getConnection(readOnly,"sa","")){assertEquals(1,count(input,"issuer"));}
    }
}
