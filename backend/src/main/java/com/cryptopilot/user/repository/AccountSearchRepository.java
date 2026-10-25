package com.cryptopilot.user.repository;

import com.cryptopilot.user.model.AccountRow;
import com.cryptopilot.user.model.enums.AccountStatus;
import com.cryptopilot.user.model.enums.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Searches the accounts for SCR-34: by part of the email or display name, case ignored, and by role, status and a set
 * of accounts to keep or to leave out (the subscription filter, which {@code billing} answers). Newest accounts first.
 *
 * <p>Rule: SRS 3.11.2; UC-42.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountSearchRepository {

    private static final RowMapper<AccountRow> ROW = (row, index) -> new AccountRow(
            row.getObject("user_id", UUID.class),
            row.getString("email"),
            row.getString("display_name"),
            Role.valueOf(row.getString("role")),
            AccountStatus.valueOf(row.getString("account_status")),
            instant(row.getTimestamp("email_verified_at")),
            instant(row.getTimestamp("last_login_at")),
            instant(row.getTimestamp("created_at")));

    private final JdbcClient sql;

    /**
     * One page of accounts and the total.
     *
     * @param text part of the email or display name, or {@code null}
     * @param only when not {@code null}, only these accounts
     * @param except accounts to leave out; empty for none
     */
    public Page search(
            String text,
            Role role,
            AccountStatus status,
            Collection<UUID> only,
            Collection<UUID> except,
            Pageable page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        if (text != null) {
            where.append(" and (a.email ilike :text escape '\\' or p.display_name ilike :text escape '\\')");
            params.put(
                    "text", "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (role != null) {
            where.append(" and a.role = :role");
            params.put("role", role.name());
        }
        if (status != null) {
            where.append(" and a.account_status = :status");
            params.put("status", status.name());
        }
        if (only != null) {
            if (only.isEmpty()) {
                return new Page(List.of(), 0);
            }
            where.append(" and a.user_id = any(cast(:only as uuid[]))");
            params.put("only", uuidArray(only));
        }
        if (!except.isEmpty()) {
            where.append(" and a.user_id <> all(cast(:except as uuid[]))");
            params.put("except", uuidArray(except));
        }
        String from = " from user_account a left join user_profile p on p.user_id = a.user_id";
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<AccountRow> rows = sql.sql("select a.user_id, a.email, p.display_name, a.role, a.account_status,"
                        + " a.email_verified_at, a.last_login_at, a.created_at" + from + where
                        + " order by a.created_at desc, a.user_id desc limit :limit offset :offset")
                .params(paged)
                .query(ROW)
                .list();
        long total = sql.sql("select count(*)" + from + where)
                .params(params)
                .query(Long.class)
                .single();
        return new Page(rows, total);
    }

    /** The ACTIVE administrators other than this account, for BR-58's "last active Admin". */
    public long countOtherActiveAdmins(UUID userId) {
        return sql.sql("select count(*) from user_account where role = 'ADMIN' and account_status = 'ACTIVE'"
                        + " and user_id <> ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    /**
     * The ids as one PostgreSQL array literal, bound as a single parameter: an IN list binds one parameter per id and
     * the driver refuses more than 65 535, which every paid subscriber of a large plan would count against.
     */
    private static String uuidArray(Collection<UUID> ids) {
        StringJoiner literal = new StringJoiner(",", "{", "}");
        ids.forEach(id -> literal.add(id.toString()));
        return literal.toString();
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    /**
     * One page of a search.
     *
     * @param rows the accounts of the page
     * @param total the accounts of every page
     */
    public record Page(List<AccountRow> rows, long total) {}
}
