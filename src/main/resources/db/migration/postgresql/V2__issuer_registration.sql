CREATE TABLE issuer_registration (
 tenant_id UUID NOT NULL, issuer_id UUID NOT NULL,
 trade_name VARCHAR(160) NOT NULL, state_registration VARCHAR(30) NOT NULL,
 municipal_registration VARCHAR(30) NOT NULL, cnae VARCHAR(7) NOT NULL,
 taxation_reference VARCHAR(200) NOT NULL, street VARCHAR(255) NOT NULL,
 address_number VARCHAR(60) NOT NULL, complement VARCHAR(160) NOT NULL,
 zip VARCHAR(8) NOT NULL, neighborhood VARCHAR(100) NOT NULL,
 city VARCHAR(100) NOT NULL, uf VARCHAR(2) NOT NULL,
 phone VARCHAR(30) NOT NULL, email VARCHAR(254) NOT NULL,
 imported_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,issuer_id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES issuer(tenant_id,id)
);
