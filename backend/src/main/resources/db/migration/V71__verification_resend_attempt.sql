-- T-105: the verification resends of BR-65 are counted per submitted address (D-57, A-36), whether or not an
-- account holds it. Only the SHA-256 of the trimmed, lower-cased address is stored, never the address itself.
-- One row per accepted resend; rows older than the 24 hour window are deleted by the daily token sweep.
-- V69 and V70 are taken by open pull requests.

CREATE TABLE verification_resend_attempt (
    attempt_id   uuid        NOT NULL,
    address_hash varchar(64) NOT NULL,
    requested_at timestamptz NOT NULL,
    CONSTRAINT pk_verification_resend_attempt PRIMARY KEY (attempt_id)
);

CREATE INDEX idx_verification_resend_attempt_address
    ON verification_resend_attempt (address_hash, requested_at);
