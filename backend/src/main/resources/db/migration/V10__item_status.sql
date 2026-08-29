-- Story 3.3: add in-trip item status; default OPEN mirrors ItemStatus.OPEN birth state (aggregate fold).
ALTER TABLE item_read_model ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
