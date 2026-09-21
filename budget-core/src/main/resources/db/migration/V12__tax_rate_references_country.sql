-- The foreign key V11 deliberately left off, now that the table it points at exists and is seeded.
--
-- V11 shipped `country_code` unconstrained because `country` is created by V1 and populated by V4,
-- both outside this packet's migration range. While those had not landed, a reference here would
-- have made V10-V11 unrunnable on their own - and CI verifies every branch independently, so that
-- would have failed the build for a reason unrelated to the change under review.
--
-- **If this migration ever fails on apply, the cause is V4 not having run, not `country` being
-- empty by design.** V4 is a Java migration (`BaseJavaMigration`) that loads the committed CSVs, so
-- Flyway only finds it while compiled classes under `db/migration` are on the classpath. A
-- packaging or build change that drops them presents exactly here, as this constraint failing on a
-- table that looks like it was never seeded. Looking for missing INSERT statements in the .sql
-- files will not find it, because there are none to find.

-- TEXT to match `country.code`, and to match the convention every other table here follows. V11
-- used CHAR(2), which pads to a fixed width and would compare against a TEXT key through an
-- implicit cast - a difference that costs nothing today and surprises somebody eventually.
ALTER TABLE country_tax_rate
    ALTER COLUMN country_code TYPE TEXT;

ALTER TABLE country_tax_rate
    ADD CONSTRAINT country_tax_rate_country_fk
        FOREIGN KEY (country_code) REFERENCES country (code);

-- Note on what is deliberately NOT changed here. ARCHITECTURE describes this table as carrying a
-- `source_id` referencing `data_source`; it carries `source_name` as free text instead. Converting
-- would mean inserting rows into `data_source`, which belongs to the cost-of-living packet, and the
-- benefit is small: the domain type carries a human-readable name rather than an id, so every read
-- would gain a join to recover the string it already had. Stated here as a decision rather than
-- left to look like an oversight.
