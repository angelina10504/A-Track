-- Run once on the ATrack database before deploying register_fcm_token.php / fcm_revive.php.
--
--   mysql -u <user> -p ATrack < fcm_schema.sql

ALTER TABLE user_device_binding
    ADD COLUMN fcm_token        VARCHAR(1024) NULL,
    ADD COLUMN fcm_updated_on   DATETIME      NULL,
    ADD COLUMN fcm_last_push_on DATETIME      NULL;
