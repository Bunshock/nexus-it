-- APP_USER.sede_id becomes the same id space as a catalog Sede (a String) instead of a local INT
-- FK into SEDE. Decision: an admin's/technician's "home Sede" is now meant to hold a real GLPI
-- Location id once M3 rewires the catalog onto GLPI — there is no local mapping table, the id is
-- stored directly. Deliberately decoupled from SEDE(id): NOTE_REPORT.sede_id,
-- NOTE_REMITO.destination_sede_id and SEDE.id itself all stay local INTs for now (still read
-- through CatalogController's local mirror until M3), so this migration only touches APP_USER.

-- Drop the auto-named FK (APP_USER.sede_id -> SEDE.id) first — SQL Server refuses ALTER COLUMN on
-- a column still bound by a foreign key, and V1__init.sql never named this constraint explicitly.
DECLARE @fkName NVARCHAR(200);
SELECT @fkName = fk.name
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
JOIN sys.columns c ON c.object_id = fkc.parent_object_id AND c.column_id = fkc.parent_column_id
WHERE fk.parent_object_id = OBJECT_ID('dbo.APP_USER') AND c.name = 'sede_id';

IF @fkName IS NOT NULL
BEGIN
    DECLARE @sql NVARCHAR(400) = N'ALTER TABLE APP_USER DROP CONSTRAINT ' + QUOTENAME(@fkName);
    EXEC sp_executesql @sql;
END

ALTER TABLE APP_USER ALTER COLUMN sede_id NVARCHAR(255) NULL;
