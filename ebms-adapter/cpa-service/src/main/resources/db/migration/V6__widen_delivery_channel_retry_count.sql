-- retry_count was SMALLINT in V1, maar de JPA-entiteit gebruikt Integer (ddl-auto: validate).
ALTER TABLE cpa_delivery_channel
    ALTER COLUMN retry_count TYPE INTEGER;
