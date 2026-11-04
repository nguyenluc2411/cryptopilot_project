package com.cryptopilot.user.repository;

import com.cryptopilot.admin.ActorDirectory;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The accounts behind the audit trail of SCR-44: names the actors of a page of entries and finds the accounts an actor
 * filter means, by part of the email or display name as SCR-34 searches them.
 *
 * <p>Rule: SRS 3.11.9; UC-52.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountActorDirectory implements ActorDirectory {

    private final JdbcClient sql;

    @Override
    public Map<UUID, ActorSummary> describe(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return sql
                .sql("""
                        select a.user_id, a.email, p.display_name, a.role
                          from user_account a left join user_profile p on p.user_id = a.user_id
                         where a.user_id = any(cast(? as uuid[]))""")
                .param(uuidArray(accountIds))
                .query((row, index) -> new ActorSummary(
                        row.getObject("user_id", UUID.class),
                        row.getString("email"),
                        row.getString("display_name"),
                        row.getString("role")))
                .list()
                .stream()
                .collect(Collectors.toMap(ActorSummary::accountId, Function.identity()));
    }

    @Override
    public List<UUID> matching(String text, int limit) {
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return sql.sql("""
                        select a.user_id
                          from user_account a left join user_profile p on p.user_id = a.user_id
                         where a.email ilike ? escape '\\' or p.display_name ilike ? escape '\\'
                         order by a.created_at desc, a.user_id desc
                         limit ?""").params(pattern, pattern, limit).query(UUID.class).list();
    }

    private static String uuidArray(Collection<UUID> ids) {
        StringJoiner literal = new StringJoiner(",", "{", "}");
        ids.forEach(id -> literal.add(id.toString()));
        return literal.toString();
    }
}
