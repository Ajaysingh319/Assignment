CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_products_sku UNIQUE (sku)
);

CREATE TABLE warehouses (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    location VARCHAR(255),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_warehouses_code UNIQUE (code)
);

CREATE TABLE inventory (
    product_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    available_qty BIGINT NOT NULL DEFAULT 0,
    reserved_qty BIGINT NOT NULL DEFAULT 0,
    fulfilled_qty BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (product_id, warehouse_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
    CONSTRAINT chk_inventory_available_nonnegative CHECK (available_qty >= 0),
    CONSTRAINT chk_inventory_reserved_nonnegative CHECK (reserved_qty >= 0),
    CONSTRAINT chk_inventory_fulfilled_nonnegative CHECK (fulfilled_qty >= 0)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_inventory_warehouse ON inventory (warehouse_id);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
