package db.common;

import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Shared migration: discover the legacy generated constraint name in PostgreSQL and H2. */
public class V3__Named_services_and_recipients extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        var constraints=new LinkedHashMap<String,Set<String>>();
        try(var query=connection.prepareStatement("SELECT tc.constraint_name,kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog=kcu.constraint_catalog AND tc.constraint_schema=kcu.constraint_schema AND tc.constraint_name=kcu.constraint_name WHERE tc.table_schema=? AND LOWER(tc.table_name)='fiscal_defaults' AND tc.constraint_type='UNIQUE'")) {
            query.setString(1,connection.getSchema());
            try(var rows=query.executeQuery()) {while(rows.next())constraints.computeIfAbsent(rows.getString(1),k->new HashSet<>()).add(rows.getString(2).toLowerCase(Locale.ROOT));}
        }
        try(var sql=connection.createStatement()) {
            sql.execute("ALTER TABLE fiscal_defaults ADD COLUMN variant_name VARCHAR(120) NOT NULL DEFAULT 'Padrão'");
            sql.execute("ALTER TABLE fiscal_defaults ADD COLUMN nbs VARCHAR(9) NOT NULL DEFAULT ''");
            var old=constraints.entrySet().stream().filter(e->e.getValue().equals(Set.of("tenant_id","issuer_id","effective_from"))).findFirst().orElseThrow(()->new IllegalStateException("Restrição de vigência anterior não encontrada"));
            sql.execute("ALTER TABLE fiscal_defaults DROP CONSTRAINT \""+old.getKey().replace("\"","\"\"")+"\"");
            sql.execute("ALTER TABLE fiscal_defaults ADD CONSTRAINT fiscal_defaults_variant_effective_key UNIQUE(tenant_id,issuer_id,variant_name,effective_from)");
            sql.execute("CREATE TABLE recipient (id UUID PRIMARY KEY,tenant_id UUID NOT NULL,issuer_id UUID NOT NULL,tax_id VARCHAR(14) NOT NULL,name VARCHAR(150) NOT NULL,email VARCHAR(80),address_json TEXT,created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,issuer_id,tax_id),FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id))");
        }
    }
}
