-- AI context v4 (T-074, BR-51): the context snapshot (version 4) now carries the recent news about the pair's coin and
-- the market, numbered for citations, and the Trader's trading style and risk profile.
--
-- 1. An answer keeps the articles it cited: ai_message.sources, a JSON array of {ref, origin, headline, outlet,
--    publishedAt, url}, so the conversation shows the links under the answer. NULL on questions, on answers that cite
--    nothing and on answers stored before this version. origin is SYSTEM for the articles the news module collected.
--
-- 2. A configuration version is never edited (UC-50), so, as V66 did, this adds a new version: a copy of the active one
--    with two more rules before the answer-format paragraph (or at the end when an administrator replaced it), then
--    activates it the way the admin console does, turning the previous version off first so
--    uq_ai_configuration_active never sees two active rows. No row is added when no version is active.

ALTER TABLE ai_message
    ADD COLUMN sources jsonb;

INSERT INTO ai_configuration (config_id, updated_by, model_name, system_prompt, temperature, max_output_tokens,
                              is_active, disclaimer, created_at, updated_at, version)
SELECT '019b76da-a800-7002-8000-0000000a1003', NULL, active.model_name,
       -- The earlier prompts have CRLF or LF line ends depending on the checkout, so the match accepts both.
       CASE
           WHEN active.system_prompt ~ '\r?\n\r?\nCách trả lời:'
               THEN regexp_replace(active.system_prompt, '(\r?\n)(\r?\nCách trả lời:)', '\1' || rule.text || '\1\2')
           ELSE active.system_prompt || E'\n' || rule.text
       END,
       active.temperature, active.max_output_tokens, false, active.disclaimer, now(), now(), 0
FROM ai_configuration active
CROSS JOIN (VALUES (
$rule$9. CONTEXT có thể có news gồm coin, checkedUpTo (lúc hệ thống đọc các trang tin gần nhất, giờ UTC) và articles (ref, about, headline, outlet, publishedAt, summary, sentiment, url); about là COIN khi tin nói về coin được hỏi, MARKET khi là tin chung của thị trường. Khi câu hỏi liên quan đến diễn biến, xu hướng, rủi ro hay kế hoạch, hãy đối chiếu các tin này với dữ liệu kỹ thuật, nêu ngắn gọn tin nào đáng chú ý và tác động có thể có ở cả hai phía, rồi ghi số nguồn trong ngoặc vuông như [1] hoặc [2] ngay sau ý dùng tin đó. Chỉ dùng các số ref có trong articles và không tự viết đường link: hệ thống hiển thị link theo số. Nói rõ tin được cập nhật đến lúc nào (đổi checkedUpTo sang giờ Việt Nam, UTC+7) và nhắc người dùng mở nguồn để tự kiểm chứng. Nếu articles rỗng, nói rằng hệ thống chưa có tin mới về coin này và không suy đoán tin tức. Tin tức không phải khuyến nghị: vẫn tuân thủ quy tắc 2 và 3.
10. CONTEXT có thể có trader gồm tradingStyle (SCALPING: giữ lệnh vài phút; DAY: đóng trong ngày; SWING: vài ngày đến vài tuần; POSITION: vài tuần đến vài tháng; null: người dùng chưa chọn) và riskProfile (CONSERVATIVE, BALANCED hoặc AGGRESSIVE). Hãy giải thích theo phong cách đó, ví dụ nhấn mạnh khung thời gian và mức biến động phù hợp, và nhắc rủi ro theo khẩu vị rủi ro của họ; không dùng phong cách để khuyên vào lệnh, chọn đòn bẩy hay khối lượng. Nếu tradingStyle là null, đừng giả định; có thể gợi ý người dùng chọn phong cách giao dịch trong hồ sơ để nhận giải thích sát hơn.$rule$
)) AS rule (text)
WHERE active.is_active
ON CONFLICT (config_id) DO NOTHING;

UPDATE ai_configuration
SET is_active = false, updated_at = now()
WHERE is_active
  AND config_id <> '019b76da-a800-7002-8000-0000000a1003'
  AND EXISTS (SELECT 1 FROM ai_configuration WHERE config_id = '019b76da-a800-7002-8000-0000000a1003');

UPDATE ai_configuration
SET is_active = true, updated_at = now()
WHERE config_id = '019b76da-a800-7002-8000-0000000a1003';
