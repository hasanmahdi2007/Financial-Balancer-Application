-- A rate the user typed is a fact about them in one country, so it is keyed by that country.
--
-- V11 keyed it on the user alone, on the reasoning that a move abroad is a change the user makes and
-- not one to infer. Plans now belong to a place (P11): moving means choosing or starting a plan for
-- the new place, and nothing from the old place may carry across. A Lebanese rate applied to a US
-- income would fund the tax reserve from the wrong bill, with nothing on screen saying why.
--
-- Numbered V33, not in this packet's V10-V19 range. Databases already stand at V32, and Flyway
-- refuses to apply a lower version after a higher one, so a V13 would stop the service starting.
-- From here on every migration takes the next number after the highest one merged.

ALTER TABLE user_tax_override
    ADD COLUMN country_code TEXT;

-- Existing rates belong to the country the user lives in now, which is the only country they can
-- have been read for: the resolver is always asked about the profile's own country.
UPDATE user_tax_override o
SET country_code = p.country_code
FROM planning_profile p
WHERE p.user_id = o.user_id;

-- A rate with no profile behind it was never read by anything, for the reason above, and there is no
-- country to file it under. Nothing in the app writes this table yet, so in practice there are none.
DELETE FROM user_tax_override
WHERE country_code IS NULL;

ALTER TABLE user_tax_override
    ALTER COLUMN country_code SET NOT NULL,
    DROP CONSTRAINT user_tax_override_pkey,
    ADD PRIMARY KEY (user_id, country_code),
    ADD CONSTRAINT user_tax_override_country_fk
        FOREIGN KEY (country_code) REFERENCES country (code);
