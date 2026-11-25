ALTER TABLE recipient ADD CONSTRAINT recipient_tenant_id_key UNIQUE(tenant_id,id);
CREATE TABLE recipient_issuer (
 tenant_id UUID NOT NULL,
 recipient_id UUID NOT NULL,
 issuer_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,recipient_id,issuer_id),
 FOREIGN KEY(tenant_id,recipient_id) REFERENCES recipient(tenant_id,id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
INSERT INTO recipient_issuer(tenant_id,recipient_id,issuer_id) SELECT tenant_id,id,issuer_id FROM recipient;
CREATE INDEX recipient_issuer_lookup ON recipient_issuer(tenant_id,issuer_id);
