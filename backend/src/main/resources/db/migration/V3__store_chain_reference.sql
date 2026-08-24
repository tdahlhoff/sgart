-- Store chain reference data (Store Reference context, Story 1.8, AC2): the global, read-only list
-- the client caches for 100% client-side, offline-after-first-load chain matching. No personal
-- data — chain brand names, not people. Seeded with the MVP single-market (German) chains; the ids
-- are FIXED literal UUIDs so a store's stored chain_id stays resolvable across environments
-- (dev/CI/prod). Multi-market / additional-language data is explicitly out of MVP scope.
CREATE TABLE store_chain_reference (
    chain_id UUID         PRIMARY KEY,
    name     VARCHAR(120) NOT NULL
);

INSERT INTO store_chain_reference (chain_id, name) VALUES
    ('a1b2c3d4-0001-4000-8000-000000000001', 'Edeka'),
    ('a1b2c3d4-0002-4000-8000-000000000002', 'Rewe'),
    ('a1b2c3d4-0003-4000-8000-000000000003', 'Aldi'),
    ('a1b2c3d4-0004-4000-8000-000000000004', 'Lidl'),
    ('a1b2c3d4-0005-4000-8000-000000000005', 'Netto'),
    ('a1b2c3d4-0006-4000-8000-000000000006', 'Penny'),
    ('a1b2c3d4-0007-4000-8000-000000000007', 'Kaufland'),
    ('a1b2c3d4-0008-4000-8000-000000000008', 'dm'),
    ('a1b2c3d4-0009-4000-8000-000000000009', 'Rossmann'),
    ('a1b2c3d4-0010-4000-8000-000000000010', 'Norma'),
    ('a1b2c3d4-0011-4000-8000-000000000011', 'Globus'),
    ('a1b2c3d4-0012-4000-8000-000000000012', 'tegut'),
    ('a1b2c3d4-0013-4000-8000-000000000013', 'Müller');
