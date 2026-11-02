-- Active category names are unique per user, ignoring case INCLUDING the Turkish letters
-- (docs/database-design.md, categories). V3 used lower(name), which maps 'I' to 'i' and keeps
-- 'ı', so 'ULAŞIM' and 'Ulaşım' were treated as different names.
--
-- translate() first maps the Turkish capitals and both dotted/dotless i to their lowercase
-- form (İ, I, ı -> i), then lower() handles the rest. The Turkish letters are mapped
-- explicitly so the result does not depend on the database locale (lc_ctype).
-- The index keeps its name: the application maps it to 409 CATEGORY_NAME_TAKEN.
--
-- Fails (and rolls back) if a user already has two active names that are equal under the new
-- rule; there is no production data yet.

DROP INDEX uk_categories_user_active_name;

CREATE UNIQUE INDEX uk_categories_user_active_name
    ON categories (user_id, lower(translate(name, 'ÇĞİIıÖŞÜ', 'çğiiiöşü')))
    WHERE archived_at IS NULL;
