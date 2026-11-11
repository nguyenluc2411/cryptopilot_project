-- AI context v2 (BR-51, D-146): the context snapshot (version 3) now carries scoredSide and capReason next to the
-- setup score, so the assistant needs a prompt rule that explains them.
--
-- A configuration version is never edited (UC-50), so this adds a new version: a copy of the active one with one
-- extra rule, placed before the answer-format paragraph (or at the end when an administrator has replaced that
-- paragraph). It is then activated the way the admin console does it: the previous version is turned off first, so
-- uq_ai_configuration_active never sees two active rows. No row is added when no version is active.

INSERT INTO ai_configuration (config_id, updated_by, model_name, system_prompt, temperature, max_output_tokens,
                              is_active, disclaimer, created_at, updated_at, version)
SELECT '019b76da-a800-7002-8000-0000000a1002', NULL, active.model_name,
       -- The V62 prompt has CRLF or LF line ends depending on the checkout, so the match accepts both.
       CASE
           WHEN active.system_prompt ~ '\r?\n\r?\nCách trả lời:'
               THEN regexp_replace(active.system_prompt, '(\r?\n)(\r?\nCách trả lời:)', '\1' || rule.text || '\1\2')
           ELSE active.system_prompt || E'\n' || rule.text
       END,
       active.temperature, active.max_output_tokens, false, active.disclaimer, now(), now(), 0
FROM ai_configuration active
CROSS JOIN (VALUES (
$rule$8. Trong CONTEXT, scoredSide (LONG hoặc SHORT) là phía mà setup score được tính, không phải khuyến nghị giao dịch: hãy nói "điểm được tính cho phía của xu hướng", không bao giờ nói "nên vào LONG/SHORT". Spot luôn là LONG. Với Futures, dominantSide chỉ là thông tin thị trường về phía đang thắng thế của xu hướng; khi dominantSide là NEUTRAL, điểm được tính cho phía theo dấu của MACD histogram, nên scoredSide có thể khác dominantSide. Nếu capReason là LOW_ROOM, điểm đã bị giới hạn vì còn ít khoảng trống tới mức hỗ trợ/kháng cự kế tiếp theo phía được tính điểm; hãy nêu điều này khi giải thích điểm.$rule$
)) AS rule (text)
WHERE active.is_active
ON CONFLICT (config_id) DO NOTHING;

UPDATE ai_configuration
SET is_active = false, updated_at = now()
WHERE is_active
  AND config_id <> '019b76da-a800-7002-8000-0000000a1002'
  AND EXISTS (SELECT 1 FROM ai_configuration WHERE config_id = '019b76da-a800-7002-8000-0000000a1002');

UPDATE ai_configuration
SET is_active = true, updated_at = now()
WHERE config_id = '019b76da-a800-7002-8000-0000000a1002';
