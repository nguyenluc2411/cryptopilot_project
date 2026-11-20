-- D-99 (CoinMarketCap Community member page): what a member shows of themselves on their public page besides the
-- name, kept on the profile they already have rather than in a table of its own.
--   bio                a short introduction, plain text
--   website_url        one https link, e.g. an X account (the scheme is checked by the request)
--   banner_url         the cover image above the avatar, served by the media storage
--   banner_public_id   the key the banner is stored under, so a replaced banner is deleted from the storage
--   avatar_public_id   the same for the avatar; avatar_url (V1) stays where it is served from
-- All of them are optional: a profile that never said anything shows the default banner and no introduction.
ALTER TABLE user_profile
    ADD COLUMN bio              varchar(300),
    ADD COLUMN website_url      varchar(200),
    ADD COLUMN banner_url       varchar(500),
    ADD COLUMN banner_public_id varchar(255),
    ADD COLUMN avatar_public_id varchar(255);
