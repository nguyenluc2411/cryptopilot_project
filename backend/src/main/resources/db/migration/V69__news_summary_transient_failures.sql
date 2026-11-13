-- NSF-11: give back the summary attempts an article spent on a provider that was overloaded, timed out, rate limited
-- or could not be reached.
--
-- Until this version an overloaded provider (HTTP 503, code LLM_UNAVAILABLE) or one that timed out (LLM_TIMEOUT)
-- counted as a failure of the article, so an outage of a few minutes spent all three attempts of every article in its
-- runs and left them waiting for an administrator (D-128). The summary job now stops its run on those codes without
-- counting an attempt (a timeout still counts one, against the one article it stopped on, which an unusual article can
-- cause); this puts the articles that failed only for such a reason back in its queue. The job still takes only
-- articles collected within its max-age, so an older one keeps its excerpt.
--
-- summary_error holds "<code>: <message>"; only the last failure is kept, so an article whose last failure was one of
-- these codes is reset, whatever failed before. Hidden or summarized articles are left alone.

UPDATE news_article
   SET summary_attempts     = 0,
       summary_error        = NULL,
       summary_attempted_at = NULL,
       updated_at           = now(),
       version              = version + 1
 WHERE ai_summary IS NULL
   AND hidden_at IS NULL
   AND summary_attempts > 0
   AND (summary_error LIKE 'LLM\_UNAVAILABLE:%'
        OR summary_error LIKE 'LLM\_TIMEOUT:%'
        OR summary_error LIKE 'LLM\_RATE\_LIMITED:%'
        OR summary_error LIKE 'UNREACHABLE:%');
