package br.com.fiscal;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Transferência explícita, sem apagar ou alterar a base H2 de origem. */
final class DatabaseTransfer {
    static final List<String> TABLES=List.of("issuer","issuer_registration","recipient","recipient_issuer","fiscal_defaults","draft","fiscal_certificate","fiscal_proxy","nfse_profile","nfse_sequence","nfse_document","nfse_audit");
    record Column(String target,String source,int type) {}

    static void run() throws Exception {
        String file=System.getenv().getOrDefault("H2_SOURCE_FILE","./data/fiscal");
        if(file.contains(";"))throw new IllegalArgumentException("H2_SOURCE_FILE deve conter somente o caminho do arquivo, sem opções JDBC");
        String source="jdbc:h2:file:"+Path.of(file).toAbsolutePath().normalize()+";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
        String destination=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://127.0.0.1:5432/fiscal");
        String password=System.getenv("DB_PASSWORD");
        if(password==null||password.isBlank())throw new IllegalArgumentException("Defina DB_PASSWORD para o PostgreSQL de destino");
        var counts=transfer(source,System.getenv().getOrDefault("H2_SOURCE_USER","sa"),System.getenv().getOrDefault("H2_SOURCE_PASSWORD",""),
            destination,System.getenv().getOrDefault("DB_USER","fiscal"),password);
        counts.forEach((table,count)->System.out.println(table+": "+count+" registros transferidos"));
        System.out.println("Transferência concluída. A base H2 foi preservada. Mantenha a mesma NFSE_MASTER_KEY para abrir os certificados.");
    }

    static Map<String,Long> transfer(String sourceUrl,String sourceUser,String sourcePassword,String targetUrl,String targetUser,String targetPassword)throws Exception {
        if(!sourceUrl.startsWith("jdbc:h2:")||!targetUrl.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("A transferência exige origem H2 e destino PostgreSQL");
        try(var source=DriverManager.getConnection(sourceUrl,sourceUser,sourcePassword)) {
            source.setReadOnly(true);source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);source.setAutoCommit(false);
            Flyway.configure().dataSource(targetUrl,targetUser,targetPassword).locations("classpath:db/migration/postgresql","classpath:db/common").defaultSchema("public").cleanDisabled(true).load().migrate();
            try(var target=DriverManager.getConnection(targetUrl,targetUser,targetPassword)) {
                target.setSchema("public");target.setAutoCommit(false);
                try {
                    try(var statement=target.createStatement()) {
                        statement.execute("LOCK TABLE "+String.join(",",TABLES)+" IN EXCLUSIVE MODE");
                        for(String table:TABLES)try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {
                            rows.next();if(rows.getLong(1)!=0)throw new IllegalStateException("O banco de destino deve estar vazio. Nenhum registro existente será substituído");
                        }
                    }
                    var counts=new LinkedHashMap<String,Long>();
                    for(String table:TABLES)counts.put(table,copy(source,target,table));
                    try(var statement=target.createStatement()){statement.executeUpdate("INSERT INTO recipient_issuer(tenant_id,recipient_id,issuer_id) SELECT r.tenant_id,r.id,r.issuer_id FROM recipient r WHERE NOT EXISTS (SELECT 1 FROM recipient_issuer l WHERE l.tenant_id=r.tenant_id AND l.recipient_id=r.id AND l.issuer_id=r.issuer_id)");statement.executeUpdate("UPDATE nfse_document SET authorized_at=created_at WHERE authorized_at IS NULL AND official_xml IS NOT NULL");}
                    target.commit();return Collections.unmodifiableMap(counts);
                } catch(Exception ex) {target.rollback();throw ex;}
            } finally {source.rollback();}
        }
    }

    private static long copy(Connection source,Connection target,String table)throws SQLException {
        var sourceColumns=new LinkedHashMap<String,String>();
        try(var columns=source.getMetaData().getColumns(null,"PUBLIC",table.toUpperCase(Locale.ROOT),null)) {
            while(columns.next()){String name=columns.getString("COLUMN_NAME");sourceColumns.put(name.toLowerCase(Locale.ROOT),name);}
        }
        if(sourceColumns.isEmpty())return 0; // Bancos H2 anteriores ao módulo fiscal podem não ter todas as tabelas.
        var columns=new ArrayList<Column>();
        try(var metadata=target.getMetaData().getColumns(null,"public",table,null)) {
            while(metadata.next()){
                String name=metadata.getString("COLUMN_NAME");
                if(sourceColumns.containsKey(name))columns.add(new Column(name,sourceColumns.get(name),metadata.getInt("DATA_TYPE")));
            }
        }
        if(columns.isEmpty())throw new SQLException("Tabela sem colunas compatíveis: "+table);
        String select="SELECT "+String.join(",",columns.stream().map(c->quote(c.source())).toList())+" FROM "+quote(table.toUpperCase(Locale.ROOT));
        String insert="INSERT INTO "+table+" ("+String.join(",",columns.stream().map(c->quote(c.target())).toList())+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        long total=0;
        try(var statement=source.createStatement();var input=statement.executeQuery(select);var output=target.prepareStatement(insert)){
            while(input.next()){
                for(int i=0;i<columns.size();i++){
                    int type=columns.get(i).type();Object value;
                    if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(type))value=input.getBytes(i+1);
                    else if(Set.of(Types.VARCHAR,Types.LONGVARCHAR,Types.CLOB,Types.NVARCHAR,Types.LONGNVARCHAR).contains(type))value=input.getString(i+1);
                    else value=input.getObject(i+1);
                    if(value==null)output.setNull(i+1,type);
                    else if(value instanceof byte[] bytes)output.setBytes(i+1,bytes);
                    else output.setObject(i+1,value);
                }
                output.addBatch();total++;
                if(total%250==0)output.executeBatch();
            }
            if(total%250!=0)output.executeBatch();
        }
        try(var check=target.createStatement();var rows=check.executeQuery("SELECT COUNT(*) FROM "+table)){
            rows.next();if(rows.getLong(1)!=total)throw new SQLException("Contagem divergente após copiar "+table);
        }
        return total;
    }
    private static String quote(String name){
        if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Identificador de coluna não permitido");
        return "\""+name+"\"";
    }
}
