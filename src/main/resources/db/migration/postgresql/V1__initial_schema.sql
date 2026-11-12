-- Persistência da aplicação SA Trindade em PostgreSQL.
CREATE TABLE issuer (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, cnpj VARCHAR(14) NOT NULL,
 name VARCHAR(160) NOT NULL, municipality VARCHAR(7) NOT NULL,
 UNIQUE(tenant_id, cnpj), UNIQUE(tenant_id, id)
);
CREATE TABLE fiscal_defaults (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, issuer_id UUID NOT NULL,
 version INTEGER NOT NULL, effective_from DATE NOT NULL, lc116 VARCHAR(10) NOT NULL,
 national_service VARCHAR(10) NOT NULL, cnae VARCHAR(7) NOT NULL,
 description VARCHAR(2000) NOT NULL, iss_rate DECIMAL(7,4) NOT NULL,
 irrf_rate DECIMAL(7,4) NOT NULL, pis_rate DECIMAL(7,4) NOT NULL,
 cofins_rate DECIMAL(7,4) NOT NULL, csll_rate DECIMAL(7,4) NOT NULL,
 inss_rate DECIMAL(7,4) NOT NULL, iss_withheld BOOLEAN NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id, issuer_id) REFERENCES issuer(tenant_id, id),
 UNIQUE(tenant_id, issuer_id, version), UNIQUE(tenant_id, issuer_id, effective_from)
);
CREATE TABLE draft (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, issuer_id UUID NOT NULL,
 defaults_id UUID NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, competence DATE NOT NULL,
 amount DECIMAL(18,2) NOT NULL, status VARCHAR(30) NOT NULL,
 snapshot TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id, issuer_id) REFERENCES issuer(tenant_id, id),
 FOREIGN KEY(defaults_id) REFERENCES fiscal_defaults(id),
 UNIQUE(tenant_id, idempotency_key)
);
CREATE TABLE fiscal_certificate (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, label VARCHAR(120) NOT NULL,
 holder_tax_id VARCHAR(14) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, encrypted_pfx BYTEA NOT NULL,
 encrypted_password BYTEA NOT NULL, status VARCHAR(20) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,id)
);
CREATE TABLE fiscal_proxy (
 tenant_id UUID NOT NULL, issuer_id UUID NOT NULL, certificate_id UUID NOT NULL,
 reference VARCHAR(200) NOT NULL, valid_from DATE NOT NULL, valid_to DATE NOT NULL,
 can_issue BOOLEAN NOT NULL, can_query BOOLEAN NOT NULL, can_cancel BOOLEAN NOT NULL,
 active BOOLEAN NOT NULL, declared_by VARCHAR(100) NOT NULL,
 verified_at TIMESTAMP WITH TIME ZONE, verification_status VARCHAR(40) NOT NULL DEFAULT 'DECLARED',
 PRIMARY KEY(tenant_id,issuer_id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id),
 FOREIGN KEY(tenant_id,certificate_id) REFERENCES fiscal_certificate(tenant_id,id)
);
CREATE TABLE nfse_profile (
 tenant_id UUID NOT NULL, issuer_id UUID NOT NULL, profile TEXT NOT NULL,
 PRIMARY KEY(tenant_id,issuer_id), FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE nfse_sequence (
 tenant_id UUID NOT NULL, issuer_id UUID NOT NULL, environment VARCHAR(20) NOT NULL,
 series VARCHAR(5) NOT NULL, last_number BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,issuer_id,environment,series), FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE nfse_document (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, issuer_id UUID NOT NULL, draft_id UUID NOT NULL,
 certificate_id UUID NOT NULL, environment VARCHAR(20) NOT NULL, dps_id VARCHAR(45) NOT NULL,
 series VARCHAR(5) NOT NULL, dps_number BIGINT NOT NULL, status VARCHAR(30) NOT NULL,
 signed_dps TEXT NOT NULL, request_snapshot TEXT NOT NULL,
 access_key VARCHAR(50), official_xml TEXT, official_number VARCHAR(30),
 last_code VARCHAR(60), last_message VARCHAR(2000), cancel_request TEXT, cancel_event TEXT,
 retry_allowed BOOLEAN NOT NULL DEFAULT FALSE,
 created_by VARCHAR(100) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,draft_id,environment),
 UNIQUE(tenant_id,environment,dps_id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id),
 FOREIGN KEY(draft_id) REFERENCES draft(id),
 FOREIGN KEY(tenant_id,certificate_id) REFERENCES fiscal_certificate(tenant_id,id)
);
CREATE TABLE nfse_audit (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, document_id UUID NOT NULL,
 actor VARCHAR(100) NOT NULL, operation VARCHAR(30) NOT NULL, outcome VARCHAR(30) NOT NULL,
 response_code VARCHAR(60), message VARCHAR(2000), request_xml TEXT, response_payload TEXT,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(tenant_id,document_id) REFERENCES nfse_document(tenant_id,id)
);
CREATE INDEX nfse_listing ON nfse_document(tenant_id,issuer_id,created_at);
