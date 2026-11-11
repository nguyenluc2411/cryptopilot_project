-- T-073: an answer names the question it answers (NSF-16, SRS 3.9.1).
--
-- A Trader may ask a question while an earlier one of the same conversation is still pending, and the two answers can
-- arrive in either order. The history sent with the next question follows the questions, each answer after its own,
-- so an answer has to say which question it belongs to; the order the rows were stored in does not tell.
--
-- The column is nullable: a question has none, and the answers stored before this migration keep their own time in
-- the history. A message is only ever removed with its conversation, which removes the question and its answer in the
-- same statement, so the key needs no cascade of its own.
ALTER TABLE ai_message ADD COLUMN question_id uuid;

ALTER TABLE ai_message
    ADD CONSTRAINT fk_ai_message_question
        FOREIGN KEY (question_id) REFERENCES ai_message (message_id),
    ADD CONSTRAINT ck_ai_message_question_of_answer
        CHECK (question_id IS NULL OR sender_role = 'ASSISTANT');

CREATE INDEX idx_ai_message_question ON ai_message (question_id);
