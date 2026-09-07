-- F1 (GLPI adapter): each technician stores their own GLPI user_token, AES-encrypted.
-- Set via PUT /api/v1/me/glpi-token; read by the adapter's GlpiUserTokenResolver.
IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE lower(table_name) = 'app_user' AND lower(column_name) = 'glpi_token_encrypted'
)
    ALTER TABLE APP_USER ADD glpi_token_encrypted NVARCHAR(500) NULL;
