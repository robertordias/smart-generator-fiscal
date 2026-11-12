-- Referência de produção; NÃO é a migração do protótipo H2.
-- UUIDs gerados pela aplicação. Completar RLS e migrações antes de produção.
CREATE TABLE tenant (
 id UUID PRIMARY KEY, name TEXT NOT NULL, verified_domain TEXT UNIQUE,
 status TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE brand (
 tenant_id UUID PRIMARY KEY REFERENCES tenant(id), display_name TEXT NOT NULL,
 logo_object_key TEXT, primary_color VARCHAR(7), version INT NOT NULL DEFAULT 1
);
CREATE TABLE app_user (
 id UUID PRIMARY KEY, identity_subject TEXT UNIQUE NOT NULL,
 email TEXT NOT NULL, status TEXT NOT NULL
);
CREATE TABLE membership (
 tenant_id UUID REFERENCES tenant(id), user_id UUID REFERENCES app_user(id),
 role TEXT NOT NULL CHECK(role IN ('GESTOR','CONTADOR','CLIENTE')),
 permissions JSONB NOT NULL DEFAULT '[]', PRIMARY KEY(tenant_id,user_id)
);
CREATE TABLE issuer (
 tenant_id UUID REFERENCES tenant(id), id UUID NOT NULL,
 tax_id VARCHAR(14) NOT NULL, legal_name TEXT NOT NULL,
 municipal_registration TEXT, state_registration TEXT,
 municipality_code VARCHAR(7) NOT NULL, address JSONB NOT NULL,
 active BOOLEAN NOT NULL DEFAULT true,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,tax_id)
);
CREATE TABLE issuer_access (
 tenant_id UUID, user_id UUID, issuer_id UUID,
 permissions JSONB NOT NULL DEFAULT '[]', PRIMARY KEY(tenant_id,user_id,issuer_id),
 FOREIGN KEY(tenant_id,user_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE issuer_tax_profile (
 tenant_id UUID, issuer_id UUID, id UUID NOT NULL, valid_from DATE NOT NULL,
 valid_to DATE, regime TEXT NOT NULL, qualifications JSONB NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,issuer_id,valid_from),
 CHECK(valid_to IS NULL OR valid_to >= valid_from),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE party (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 identification JSONB NOT NULL, name TEXT NOT NULL, address JSONB,
 email TEXT, phone_e164 TEXT, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE service (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 name TEXT NOT NULL, lc116 TEXT, national_tax_code TEXT, municipal_tax_code TEXT,
 cnae TEXT, nbs TEXT, description TEXT NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE product (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL, sku TEXT NOT NULL,
 description TEXT NOT NULL, unit TEXT NOT NULL, ncm TEXT NOT NULL,
 cest TEXT, gtin TEXT, origin TEXT NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,issuer_id,sku),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE operation_nature (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 name TEXT NOT NULL, model TEXT NOT NULL, scenarios JSONB NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE fiscal_policy (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 model TEXT NOT NULL CHECK(model IN ('NFSE','NFE55')), scenario_key TEXT NOT NULL,
 version INT NOT NULL, valid_from DATE NOT NULL, valid_to DATE,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','APPROVED','INACTIVE')),
 defaults JSONB NOT NULL, field_locks JSONB NOT NULL DEFAULT '[]',
 approved_by UUID REFERENCES app_user(id), approved_at TIMESTAMPTZ,
 reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,issuer_id,model,scenario_key,version),
 CHECK(valid_to IS NULL OR valid_to>=valid_from),
 CHECK(state<>'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
-- Na migração final: exclusion constraint GiST sobre daterange de políticas
-- aprovadas por tenant/emitente/modelo/cenário, usando extensão btree_gist.
CREATE TABLE fiscal_tax_rule (
 tenant_id UUID, id UUID NOT NULL, policy_id UUID NOT NULL,
 tax_type TEXT NOT NULL, rate NUMERIC(9,6), base_rule JSONB NOT NULL,
 applicability JSONB NOT NULL, withholding_rule JSONB NOT NULL,
 rounding_rule TEXT NOT NULL, legal_reference TEXT,
 PRIMARY KEY(tenant_id,id), CHECK(rate IS NULL OR rate BETWEEN 0 AND 100),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES fiscal_policy(tenant_id,id)
);
CREATE TABLE certificate (
 tenant_id UUID, id UUID NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('A1','A3')),
 subject_tax_id TEXT NOT NULL, thumbprint TEXT NOT NULL,
 valid_from TIMESTAMPTZ NOT NULL, valid_to TIMESTAMPTZ NOT NULL,
 secret_reference TEXT, agent_reference TEXT, status TEXT NOT NULL,
 PRIMARY KEY(tenant_id,id), CHECK(valid_to>valid_from)
);
CREATE TABLE issuer_certificate (
 tenant_id UUID, issuer_id UUID, certificate_id UUID,
 PRIMARY KEY(tenant_id,issuer_id,certificate_id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id),
 FOREIGN KEY(tenant_id,certificate_id) REFERENCES certificate(tenant_id,id)
);
CREATE TABLE power_of_attorney (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 grantor_tax_id TEXT NOT NULL, grantee_tax_id TEXT NOT NULL,
 authority TEXT NOT NULL, external_reference TEXT, scopes JSONB NOT NULL,
 valid_from TIMESTAMPTZ NOT NULL, valid_to TIMESTAMPTZ NOT NULL,
 status TEXT NOT NULL, last_verified_at TIMESTAMPTZ, evidence_object_key TEXT,
 PRIMARY KEY(tenant_id,id), CHECK(valid_to>valid_from),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
CREATE TABLE fiscal_document (
 tenant_id UUID, id UUID NOT NULL, issuer_id UUID NOT NULL,
 model TEXT NOT NULL, environment TEXT NOT NULL, competence DATE NOT NULL,
 series TEXT, number BIGINT, status TEXT NOT NULL, authority_identity TEXT,
 recipient_id UUID, policy_id UUID, policy_snapshot JSONB NOT NULL,
 issuer_snapshot JSONB NOT NULL, recipient_snapshot JSONB NOT NULL,
 field_origins JSONB NOT NULL, layout_version TEXT NOT NULL, engine_version TEXT NOT NULL,
 gross_amount NUMERIC(18,2) NOT NULL, retained_amount NUMERIC(18,2) NOT NULL,
 net_amount NUMERIC(18,2) NOT NULL, authorized_at TIMESTAMPTZ,
 protocol TEXT, replaces_document_id UUID, lock_version BIGINT NOT NULL DEFAULT 0,
 idempotency_key TEXT NOT NULL, request_hash TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,idempotency_key),
 UNIQUE(tenant_id,issuer_id,model,environment,series,number),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id),
 FOREIGN KEY(tenant_id,recipient_id) REFERENCES party(tenant_id,id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES fiscal_policy(tenant_id,id),
 FOREIGN KEY(tenant_id,replaces_document_id) REFERENCES fiscal_document(tenant_id,id)
);
CREATE TABLE fiscal_item (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL,
 sequence INT NOT NULL, product_id UUID, service_id UUID,
 snapshot JSONB NOT NULL, quantity NUMERIC(18,6) NOT NULL,
 unit_price NUMERIC(21,10) NOT NULL, amount NUMERIC(18,2) NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,document_id,sequence),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id),
 FOREIGN KEY(tenant_id,product_id) REFERENCES product(tenant_id,id),
 FOREIGN KEY(tenant_id,service_id) REFERENCES service(tenant_id,id)
);
CREATE TABLE document_tax (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL, item_id UUID,
 tax_type TEXT NOT NULL, base NUMERIC(18,2) NOT NULL, rate NUMERIC(9,6),
 amount NUMERIC(18,2) NOT NULL, withheld BOOLEAN NOT NULL, calculation JSONB NOT NULL,
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id),
 FOREIGN KEY(tenant_id,item_id) REFERENCES fiscal_item(tenant_id,id)
);
CREATE TABLE document_event (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL,
 type TEXT NOT NULL, status TEXT NOT NULL, reason TEXT,
 external_id TEXT, protocol TEXT, occurred_at TIMESTAMPTZ NOT NULL,
 payload JSONB NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id)
);
CREATE TABLE fiscal_attempt (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL,
 operation TEXT NOT NULL, attempt INT NOT NULL, correlation_id TEXT NOT NULL,
 started_at TIMESTAMPTZ NOT NULL, finished_at TIMESTAMPTZ,
 outcome TEXT, response_code TEXT, request_object_key TEXT, response_object_key TEXT,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id)
);
CREATE TABLE document_artifact (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL,
 kind TEXT NOT NULL, object_key TEXT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id)
);
CREATE TABLE outbox (
 tenant_id UUID, id UUID NOT NULL, aggregate_id UUID NOT NULL,
 event_type TEXT NOT NULL, payload JSONB NOT NULL, state TEXT NOT NULL,
 attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id)
);
CREATE TABLE export_job (
 tenant_id UUID, id UUID NOT NULL, requested_by UUID NOT NULL REFERENCES app_user(id),
 filters JSONB NOT NULL, status TEXT NOT NULL, object_key TEXT, manifest JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,id)
);
CREATE TABLE export_item (
 tenant_id UUID, job_id UUID, artifact_id UUID,
 PRIMARY KEY(tenant_id,job_id,artifact_id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES export_job(tenant_id,id),
 FOREIGN KEY(tenant_id,artifact_id) REFERENCES document_artifact(tenant_id,id)
);
CREATE TABLE delivery (
 tenant_id UUID, id UUID NOT NULL, document_id UUID NOT NULL,
 channel TEXT NOT NULL, recipient TEXT NOT NULL, status TEXT NOT NULL,
 requested_by UUID NOT NULL REFERENCES app_user(id), idempotency_key TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,idempotency_key),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fiscal_document(tenant_id,id)
);
CREATE TABLE delivery_attempt (
 tenant_id UUID, id UUID NOT NULL, delivery_id UUID NOT NULL,
 attempt INT NOT NULL, provider_message_id TEXT, status TEXT NOT NULL,
 response_code TEXT, occurred_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,delivery_id) REFERENCES delivery(tenant_id,id)
);
CREATE TABLE audit_event (
 tenant_id UUID, id UUID NOT NULL, actor_id UUID REFERENCES app_user(id),
 action TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id UUID,
 reason TEXT, correlation_id TEXT NOT NULL, details JSONB NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id)
);
CREATE INDEX document_search ON fiscal_document(tenant_id,issuer_id,competence,status);
CREATE INDEX document_authorized ON fiscal_document(tenant_id,authorized_at) WHERE status='AUTHORIZED';
CREATE INDEX fiscal_policy_resolve ON fiscal_policy(tenant_id,issuer_id,model,scenario_key,valid_from);
CREATE INDEX outbox_pending ON outbox(state,available_at);
CREATE INDEX audit_resource ON audit_event(tenant_id,resource_type,resource_id,occurred_at);
-- Aplicar políticas RLS a TODAS as tabelas de tenant e configuração de tenant
-- por transação; o usuário da aplicação não deve contornar RLS nem ser owner.
-- Vínculos entre recursos da mesma empresa também precisam de validação no domínio.
