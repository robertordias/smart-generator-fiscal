ALTER TABLE nfse_document ADD COLUMN authorized_at TIMESTAMP WITH TIME ZONE;
UPDATE nfse_document SET authorized_at=created_at WHERE official_xml IS NOT NULL;
CREATE INDEX nfse_office_status ON nfse_document(tenant_id,environment,status);
CREATE INDEX draft_competence ON draft(tenant_id,competence,issuer_id);
