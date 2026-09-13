CREATE TABLE generic (
    id                INTEGER PRIMARY KEY,
    name_key          TEXT    NOT NULL UNIQUE,  -- англ. ключ с кулинарным состоянием
    name_ru           TEXT    NOT NULL,         -- то, что видит человек
    kcal100           INTEGER NOT NULL,
    prot100           INTEGER NOT NULL,
    fat100            INTEGER NOT NULL,
    carb100           INTEGER NOT NULL,
    default_portion_g INTEGER NOT NULL          -- типичная разовая порция, граммы
);

CREATE TABLE generic_alias (
    generic_id INTEGER NOT NULL REFERENCES generic(id),
    alias      TEXT    NOT NULL,   -- нормализовано: строчные, ё -> е
    lang       TEXT    NOT NULL,
    PRIMARY KEY (generic_id, alias, lang)
);

CREATE INDEX generic_alias_by_alias ON generic_alias(alias);

CREATE VIRTUAL TABLE generic_fts USING fts5(
    alias,
    generic_id UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE portion_unit (
    generic_id INTEGER NOT NULL REFERENCES generic(id),
    unit       TEXT    NOT NULL,
    grams      INTEGER NOT NULL,
    PRIMARY KEY (generic_id, unit)
);

CREATE TABLE product_seed (
    barcode    TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    brand      TEXT,
    kcal100    INTEGER NOT NULL,
    prot100    INTEGER NOT NULL,
    fat100     INTEGER NOT NULL,
    carb100    INTEGER NOT NULL,
    serving_g  INTEGER,
    popularity INTEGER NOT NULL DEFAULT 0
);

CREATE VIRTUAL TABLE product_seed_fts USING fts5(
    name,
    brand,
    barcode UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
