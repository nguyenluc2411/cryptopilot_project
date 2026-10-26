#!/usr/bin/env python3
"""Generate the Mermaid ERD pages of specs/diagrams/erd from the Flyway migrations.

The schema is rebuilt by replaying every migration of backend/src/main/resources/db/migration in version order:
CREATE TABLE (columns, types, NOT NULL, primary key, unique and foreign key constraints, also multi-line ones),
ALTER TABLE (ADD/DROP/RENAME COLUMN, ALTER COLUMN TYPE and NOT NULL, ADD/DROP/RENAME CONSTRAINT, RENAME TO),
CREATE UNIQUE INDEX and DROP TABLE. A DO block is not executed: the plain ALTER TABLE statements in its body are
replayed, and a loop that renames tables from a VALUES list of (old, new) names with EXECUTE format('ALTER TABLE %I
RENAME TO %I', ...) is replayed as those renames. Temporary tables are ignored. Inserts, updates, comments and the
TimescaleDB calls do not change the shape of the schema and are skipped.

The script fails, writing nothing, when an ALTER TABLE action is not understood, when a table is missing from the
AREAS map below or listed in two areas, or when the map names a table the migrations do not create.

Usage (from the repository root):
    python specs/diagrams/tools/erd_from_migrations.py           # write the ERD pages
    python specs/diagrams/tools/erd_from_migrations.py --list    # print the tables and their columns, write nothing

Only the Python standard library is used.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
JAVA_SOURCES = ROOT / "backend" / "src" / "main" / "java"
OUTPUT = ROOT / "specs" / "diagrams" / "erd"

# One page per area: figure id -> (title, the SDD figure it refreshes, tables). Every table of the final schema must
# appear in exactly one page.
AREAS: dict[str, tuple[str, str, list[str]]] = {
    "ERD-01": (
        "identity and platform",
        "SDD Figure 3 (identity, configuration and audit part)",
        ["user_account", "user_profile", "user_token", "user_device", "audit_log", "system_setting",
         "event_publication"],
    ),
    "ERD-02": (
        "market data",
        "SDD Figure 4",
        ["coin", "crypto_pair", "ohlcv", "technical_indicator", "spot_market_data", "futures_market_data",
         "funding_rate_history", "leverage_bracket", "binance_ban", "dropped_backfill_gap"],
    ),
    "ERD-03": (
        "watchlist, alerts, trading plans, journal and notifications",
        "SDD Figure 5",
        ["watchlist", "alert", "notification", "trading_plan", "trading_plan_warning", "matching_watermark",
         "trading_journal", "trading_journal_level_change", "trading_strategy"],
    ),
    "ERD-04": (
        "demo (paper) trading",
        "SDD Figure 6",
        ["wallet_balance", "wallet_ledger_entry", "order_list", "trade_order", "order_fill", "equity_snapshot",
         "order_matching_watermark", "legacy_paper_account", "legacy_paper_transfer"],
    ),
    "ERD-05": (
        "community, news and academy",
        "SDD Figure 7 (community and news part); the Academy tables are new since the SDD",
        ["forum_post", "forum_comment", "post_media", "post_coin", "post_report", "engagement", "news_source",
         "news_article", "news_article_coin", "news_article_tag", "news_tag", "news_crawl_log", "academy_topic",
         "academy_article", "academy_term", "academy_progress"],
    ),
    "ERD-06": (
        "AI and billing",
        "SDD Figure 3 (subscription part) and Figure 7 (AI Assistant part)",
        ["subscription_package", "subscription_order", "user_subscription", "ai_configuration", "ai_conversation",
         "ai_message"],
    ),
}


# --------------------------------------------------------------------------------------------- model


@dataclass
class Column:
    name: str
    type: str
    not_null: bool


@dataclass
class ForeignKey:
    name: str
    table: str
    columns: list[str]
    ref_table: str
    ref_columns: list[str]


@dataclass
class Table:
    name: str
    columns: dict[str, Column] = field(default_factory=dict)
    primary_key: list[str] = field(default_factory=list)
    uniques: dict[str, list[str]] = field(default_factory=dict)


class Schema:
    def __init__(self) -> None:
        self.tables: dict[str, Table] = {}
        self.foreign_keys: dict[str, ForeignKey] = {}
        self._anonymous = 0

    def table(self, name: str, where: str) -> Table:
        if name not in self.tables:
            raise SystemExit(f"{where}: table {name} does not exist at this point")
        return self.tables[name]

    def anonymous_name(self, prefix: str) -> str:
        self._anonymous += 1
        return f"{prefix}_unnamed_{self._anonymous}"

    def add_foreign_key(self, fk: ForeignKey) -> None:
        self.foreign_keys[fk.name] = fk

    def drop_constraint(self, table: Table, name: str) -> None:
        if name in self.foreign_keys and self.foreign_keys[name].table == table.name:
            del self.foreign_keys[name]
        table.uniques.pop(name, None)
        if name == f"pk_{table.name}" or name == "__pk__":
            table.primary_key = []

    def rename_constraint(self, table: Table, old: str, new: str) -> None:
        if old in self.foreign_keys:
            fk = self.foreign_keys.pop(old)
            fk.name = new
            self.foreign_keys[new] = fk
        if old in table.uniques:
            table.uniques[new] = table.uniques.pop(old)

    def drop_column(self, table: Table, column: str) -> None:
        table.columns.pop(column, None)
        # PostgreSQL drops every constraint built on the column.
        for name, fk in list(self.foreign_keys.items()):
            if fk.table == table.name and column in fk.columns:
                del self.foreign_keys[name]
            elif fk.ref_table == table.name and column in fk.ref_columns:
                del self.foreign_keys[name]
        if column in table.primary_key:
            table.primary_key = []
        for name, columns in list(table.uniques.items()):
            if column in columns:
                del table.uniques[name]

    def rename_column(self, table: Table, old: str, new: str) -> None:
        column = table.columns.pop(old)
        column.name = new
        table.columns[new] = column
        table.primary_key = [new if c == old else c for c in table.primary_key]
        for name, columns in table.uniques.items():
            table.uniques[name] = [new if c == old else c for c in columns]
        for fk in self.foreign_keys.values():
            if fk.table == table.name:
                fk.columns = [new if c == old else c for c in fk.columns]
            if fk.ref_table == table.name:
                fk.ref_columns = [new if c == old else c for c in fk.ref_columns]

    def rename_table(self, old: str, new: str) -> None:
        table = self.tables.pop(old)
        table.name = new
        self.tables[new] = table
        for fk in self.foreign_keys.values():
            if fk.table == old:
                fk.table = new
            if fk.ref_table == old:
                fk.ref_table = new

    def drop_table(self, name: str, cascade: bool, where: str) -> None:
        self.table(name, where)
        for fk_name, fk in list(self.foreign_keys.items()):
            if fk.ref_table == name and fk.table != name and not cascade:
                raise SystemExit(f"{where}: DROP TABLE {name} while {fk.table} still references it ({fk_name})")
            if fk.table == name or fk.ref_table == name:
                del self.foreign_keys[fk_name]
        del self.tables[name]


# --------------------------------------------------------------------------------------------- lexing


def strip_comments(sql: str) -> str:
    """Removes -- and /* */ comments outside quoted text and dollar-quoted bodies."""
    out: list[str] = []
    i, n = 0, len(sql)
    while i < n:
        c = sql[i]
        if c == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'" and j + 1 < n and sql[j + 1] == "'":
                    j += 2
                    continue
                if sql[j] == "'":
                    break
                j += 1
            out.append(sql[i : j + 1])
            i = j + 1
        elif c == "$":
            m = re.match(r"\$[A-Za-z_]*\$", sql[i:])
            if m:
                tag = m.group(0)
                end = sql.find(tag, i + len(tag))
                end = n if end < 0 else end + len(tag)
                out.append(strip_comments_in_body(sql[i:end], tag))
                i = end
            else:
                out.append(c)
                i += 1
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_comments_in_body(body: str, tag: str) -> str:
    inner = body[len(tag) : -len(tag)] if body.endswith(tag) else body[len(tag) :]
    return tag + strip_comments(inner) + tag


def split_statements(sql: str) -> list[str]:
    """Splits on semicolons outside quoted text, dollar-quoted bodies and parentheses."""
    statements: list[str] = []
    current: list[str] = []
    depth = 0
    i, n = 0, len(sql)
    while i < n:
        c = sql[i]
        if c == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'" and j + 1 < n and sql[j + 1] == "'":
                    j += 2
                    continue
                if sql[j] == "'":
                    break
                j += 1
            current.append(sql[i : j + 1])
            i = j + 1
            continue
        if c == "$":
            m = re.match(r"\$[A-Za-z_]*\$", sql[i:])
            if m:
                tag = m.group(0)
                end = sql.find(tag, i + len(tag))
                end = n if end < 0 else end + len(tag)
                current.append(sql[i:end])
                i = end
                continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if c == ";" and depth == 0:
            text = "".join(current).strip()
            if text:
                statements.append(text)
            current = []
        else:
            current.append(c)
        i += 1
    text = "".join(current).strip()
    if text:
        statements.append(text)
    return statements


def split_top_level(text: str, sep: str = ",") -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    quoted = False
    for c in text:
        if c == "'":
            quoted = not quoted
        elif not quoted and c == "(":
            depth += 1
        elif not quoted and c == ")":
            depth -= 1
        if c == sep and depth == 0 and not quoted:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(c)
    if "".join(current).strip():
        parts.append("".join(current).strip())
    return parts


def ident(token: str) -> str:
    token = token.strip()
    if token.startswith('"') and token.endswith('"'):
        return token[1:-1]
    name = token.lower()
    return name.split(".")[-1] if name.startswith("public.") else name


def column_list(text: str) -> list[str]:
    return [ident(c.split()[0]) for c in split_top_level(text)]


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


# --------------------------------------------------------------------------------------------- parsing

COLUMN_STOP = re.compile(
    r"\b(NOT\s+NULL|NULL|DEFAULT|PRIMARY\s+KEY|REFERENCES|UNIQUE|CHECK|CONSTRAINT|GENERATED|COLLATE)\b", re.I
)
CONSTRAINT_START = re.compile(r"^(CONSTRAINT\s+\S+\s+)?(PRIMARY\s+KEY|FOREIGN\s+KEY|UNIQUE|CHECK|EXCLUDE)\b", re.I)


def parse_column(schema: Schema, table: Table, definition: str, where: str) -> None:
    definition = normalize(definition)
    name, rest = definition.split(" ", 1)
    name = ident(name)
    stop = COLUMN_STOP.search(rest)
    col_type = (rest[: stop.start()] if stop else rest).strip().lower()
    tail = rest[stop.start() :] if stop else ""
    not_null = bool(re.search(r"\bNOT\s+NULL\b", tail, re.I)) or bool(re.search(r"\bPRIMARY\s+KEY\b", tail, re.I))
    table.columns[name] = Column(name, col_type, not_null)
    if re.search(r"\bPRIMARY\s+KEY\b", tail, re.I):
        table.primary_key = [name]
    if re.search(r"\bUNIQUE\b", tail, re.I):
        table.uniques[schema.anonymous_name(f"uq_{table.name}_{name}")] = [name]
    ref = re.search(r"\bREFERENCES\s+(\S+?)\s*(?:\(([^)]*)\))?(?:\s|$)", tail, re.I)
    if ref:
        named = re.search(r"\bCONSTRAINT\s+(\S+)\s+REFERENCES", tail, re.I)
        fk_name = ident(named.group(1)) if named else schema.anonymous_name(f"fk_{table.name}_{name}")
        ref_table = ident(ref.group(1))
        ref_columns = column_list(ref.group(2)) if ref.group(2) else None
        schema.add_foreign_key(
            ForeignKey(fk_name, table.name, [name], ref_table, ref_columns or schema.table(ref_table, where).primary_key)
        )


def parse_constraint(schema: Schema, table: Table, definition: str, where: str) -> None:
    definition = normalize(definition)
    named = re.match(r"^CONSTRAINT\s+(\S+)\s+(.*)$", definition, re.I)
    name = ident(named.group(1)) if named else None
    body = named.group(2) if named else definition
    if m := re.match(r"^PRIMARY\s+KEY\s*\(([^)]*)\)", body, re.I):
        table.primary_key = column_list(m.group(1))
    elif m := re.match(r"^UNIQUE\s*(?:NULLS\s+NOT\s+DISTINCT\s*)?\(([^)]*)\)", body, re.I):
        table.uniques[name or schema.anonymous_name(f"uq_{table.name}")] = column_list(m.group(1))
    elif m := re.match(r"^FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+(\S+?)\s*(?:\(([^)]*)\))?(?:\s|$)", body, re.I):
        ref_table = ident(m.group(2))
        ref_columns = column_list(m.group(3)) if m.group(3) else schema.table(ref_table, where).primary_key
        schema.add_foreign_key(
            ForeignKey(
                name or schema.anonymous_name(f"fk_{table.name}"),
                table.name,
                column_list(m.group(1)),
                ref_table,
                ref_columns,
            )
        )
    elif re.match(r"^(CHECK|EXCLUDE)\b", body, re.I):
        pass
    else:
        raise SystemExit(f"{where}: constraint not understood on {table.name}: {definition[:120]}")


def create_table(schema: Schema, statement: str, where: str) -> None:
    m = re.match(
        r"^CREATE\s+(?:(?:GLOBAL|LOCAL)\s+)?(TEMP|TEMPORARY|UNLOGGED)?\s*TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\S+)\s*(.*)$",
        statement,
        re.I | re.S,
    )
    if not m:
        raise SystemExit(f"{where}: CREATE TABLE not understood: {statement[:120]}")
    if m.group(1) and m.group(1).upper().startswith("TEMP"):
        return
    name = ident(m.group(2))
    rest = m.group(3).strip()
    if not rest.startswith("("):
        raise SystemExit(f"{where}: CREATE TABLE {name} without a column list is not supported")
    depth, end = 0, 0
    for i, c in enumerate(rest):
        depth += c == "("
        depth -= c == ")"
        if depth == 0:
            end = i
            break
    table = Table(name)
    schema.tables[name] = table
    for item in split_top_level(rest[1:end]):
        if CONSTRAINT_START.match(normalize(item)):
            parse_constraint(schema, table, item, where)
        else:
            parse_column(schema, table, item, where)


ALTER_ACTION_IGNORED = re.compile(
    r"^(SET\s*\(|RESET\s*\(|SET\s+(LOGGED|UNLOGGED|TABLESPACE|ACCESS\s+METHOD)|OWNER\s+TO|ENABLE\b|DISABLE\b|"
    r"VALIDATE\s+CONSTRAINT|CLUSTER\b|REPLICA\s+IDENTITY|ALTER\s+CONSTRAINT)",
    re.I,
)


def alter_table(schema: Schema, statement: str, where: str) -> None:
    m = re.match(r"^ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(\S+)\s+(.*)$", statement, re.I | re.S)
    if not m:
        raise SystemExit(f"{where}: ALTER TABLE not understood: {statement[:120]}")
    name = ident(m.group(1))
    actions = m.group(2)
    if r := re.match(r"^RENAME\s+TO\s+(\S+)$", normalize(actions), re.I):
        schema.table(name, where)
        schema.rename_table(name, ident(r.group(1)))
        return
    table = schema.table(name, where)
    for action in split_top_level(actions):
        apply_action(schema, table, normalize(action), where)


def apply_action(schema: Schema, table: Table, action: str, where: str) -> None:
    if m := re.match(r"^ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(.*)$", action, re.I):
        column = ident(m.group(1).split()[0])
        if column in table.columns and re.search(r"IF\s+NOT\s+EXISTS", action, re.I):
            return
        parse_column(schema, table, m.group(1), where)
    elif re.match(r"^ADD\s+", action, re.I) and CONSTRAINT_START.match(action[4:].strip()):
        parse_constraint(schema, table, action[4:].strip(), where)
    elif m := re.match(r"^ADD\s+(?!COLUMN\b|CONSTRAINT\b)(.*)$", action, re.I):
        parse_column(schema, table, m.group(1), where)
    elif m := re.match(r"^DROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?(\S+)", action, re.I):
        schema.drop_column(table, ident(m.group(1)))
    elif m := re.match(r"^DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?(\S+)", action, re.I):
        schema.drop_constraint(table, ident(m.group(1)))
    elif m := re.match(r"^RENAME\s+COLUMN\s+(\S+)\s+TO\s+(\S+)$", action, re.I):
        schema.rename_column(table, ident(m.group(1)), ident(m.group(2)))
    elif m := re.match(r"^RENAME\s+CONSTRAINT\s+(\S+)\s+TO\s+(\S+)$", action, re.I):
        schema.rename_constraint(table, ident(m.group(1)), ident(m.group(2)))
    elif m := re.match(r"^ALTER\s+(?:COLUMN\s+)?(\S+)\s+(.*)$", action, re.I):
        column = table.columns.get(ident(m.group(1)))
        if column is None:
            raise SystemExit(f"{where}: ALTER COLUMN of a missing column {table.name}.{m.group(1)}")
        change = m.group(2)
        if t := re.match(r"^(?:SET\s+DATA\s+)?TYPE\s+(.*?)(?:\s+USING\b.*|\s+COLLATE\b.*)?$", change, re.I):
            column.type = t.group(1).strip().lower()
        elif re.match(r"^SET\s+NOT\s+NULL$", change, re.I):
            column.not_null = True
        elif re.match(r"^DROP\s+NOT\s+NULL$", change, re.I):
            column.not_null = False
        elif re.match(r"^(SET|DROP)\s+(DEFAULT|STATISTICS|STORAGE|EXPRESSION|IDENTITY)\b", change, re.I):
            pass
        else:
            raise SystemExit(f"{where}: ALTER COLUMN not understood on {table.name}: {action[:120]}")
    elif ALTER_ACTION_IGNORED.match(action):
        pass
    else:
        raise SystemExit(f"{where}: ALTER TABLE action not understood on {table.name}: {action[:120]}")


def create_unique_index(schema: Schema, statement: str, where: str) -> None:
    m = re.match(
        r"^CREATE\s+UNIQUE\s+INDEX\s+(?:CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?(\S+)\s+ON\s+(?:ONLY\s+)?(\S+)"
        r"(?:\s+USING\s+\S+)?\s*\((.*)\)\s*(.*)$",
        normalize(statement),
        re.I,
    )
    if not m:
        raise SystemExit(f"{where}: CREATE UNIQUE INDEX not understood: {statement[:120]}")
    table = schema.table(ident(m.group(2)), where)
    columns = split_top_level(m.group(3))
    partial = re.search(r"\bWHERE\b", m.group(4), re.I)
    # An index on an expression or on part of the rows does not make the columns unique for every row.
    if partial or any(not re.fullmatch(r'"?[A-Za-z_][A-Za-z0-9_]*"?(\s+(ASC|DESC))?', c.strip(), re.I) for c in columns):
        return
    table.uniques[ident(m.group(1))] = [ident(c.split()[0]) for c in columns]


def do_block(schema: Schema, statement: str, where: str) -> None:
    body_match = re.search(r"\$([A-Za-z_]*)\$(.*)\$\1\$", statement, re.S)
    if not body_match:
        raise SystemExit(f"{where}: DO block without a dollar-quoted body")
    body = body_match.group(2)
    if re.search(r"EXECUTE\s+format\(\s*'ALTER\s+TABLE\s+%I\s+RENAME\s+TO\s+%I'", body, re.I):
        values = re.search(r"\(\s*VALUES\s*(.*?)\)\s*AS\s+\w+\s*\(", body, re.I | re.S)
        if not values:
            raise SystemExit(f"{where}: a DO block renames tables from a list this script cannot read")
        renames_constraints = re.search(r"RENAME\s+CONSTRAINT\s+%I\s+TO\s+%I", body, re.I)
        for old, new in re.findall(r"\(\s*'([^']+)'\s*,\s*'([^']+)'\s*\)", values.group(1)):
            schema.table(old, where)
            schema.rename_table(old, new)
            if renames_constraints:
                # The loop also renames the constraints whose name contains the old table name.
                table = schema.tables[new]
                names = [f.name for f in schema.foreign_keys.values() if f.table == new] + list(table.uniques)
                for name in names:
                    if old in name:
                        schema.rename_constraint(table, name, name.replace(old, new))
    elif re.search(r"EXECUTE\b", body, re.I) and re.search(r"\b(TABLE|COLUMN)\b", body, re.I):
        # Dynamic DDL other than the rename loop above cannot be replayed statically.
        if re.search(r"EXECUTE\s+format\(\s*'(ALTER|CREATE|DROP)\s+TABLE", body, re.I):
            raise SystemExit(f"{where}: a DO block runs dynamic table DDL this script cannot replay")
    # The static ALTER TABLE statements of the body, wherever they sit in IF branches.
    for inner in split_statements(body):
        inner = re.sub(r"^(?:BEGIN|DECLARE\b.*?BEGIN|IF\b.*?\bTHEN|ELSE|LOOP)\s+", "", inner.strip(), flags=re.I | re.S)
        if re.match(r"^ALTER\s+TABLE\b", inner, re.I):
            alter_table(schema, inner, where)


def apply(schema: Schema, statement: str, where: str) -> None:
    head = normalize(statement[:200]).upper()
    if head.startswith("CREATE TABLE") or re.match(r"^CREATE\s+(GLOBAL\s+|LOCAL\s+)?(TEMP|TEMPORARY|UNLOGGED)\s+TABLE", head):
        create_table(schema, statement, where)
    elif head.startswith("ALTER TABLE"):
        alter_table(schema, statement, where)
    elif head.startswith("CREATE UNIQUE INDEX"):
        create_unique_index(schema, statement, where)
    elif m := re.match(r"^DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(.*?)(\s+CASCADE|\s+RESTRICT)?$", normalize(statement), re.I):
        for name in split_top_level(m.group(1)):
            if ident(name) in schema.tables or not re.search(r"IF\s+EXISTS", statement, re.I):
                schema.drop_table(ident(name), bool(m.group(2) and "CASCADE" in m.group(2).upper()), where)
    elif head.startswith("DO "):
        do_block(schema, statement, where)
    elif re.match(r"^(ALTER\s+INDEX|ALTER\s+SEQUENCE|CREATE\s+(OR\s+REPLACE\s+)?(VIEW|MATERIALIZED\s+VIEW|TYPE|DOMAIN))", head):
        raise SystemExit(f"{where}: statement kind not supported by this script: {head[:80]}")


def version_of(path: Path) -> int:
    m = re.match(r"^V(\d+)__", path.name)
    if not m:
        raise SystemExit(f"{path.name}: not a versioned migration")
    return int(m.group(1))


def build_schema() -> tuple[Schema, int]:
    files = sorted(MIGRATIONS.glob("V*__*.sql"), key=version_of)
    versions = [version_of(f) for f in files]
    if versions != list(range(1, len(versions) + 1)):
        raise SystemExit(f"migration versions are not 1..{len(versions)} without gaps: {versions}")
    schema = Schema()
    for path in files:
        sql = strip_comments(path.read_text(encoding="utf-8"))
        for index, statement in enumerate(split_statements(sql), start=1):
            apply(schema, statement, f"{path.name} statement {index}")
    return schema, versions[-1]


# --------------------------------------------------------------------------------------------- output


def java_string_literals() -> str:
    """Every string literal and text block of the main Java sources, joined, to look table names up in."""
    chunks: list[str] = []
    for path in JAVA_SOURCES.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        chunks.extend(re.findall(r'"""(.*?)"""', text, re.S))
        text = re.sub(r'""".*?"""', "", text, flags=re.S)
        chunks.extend(re.findall(r'"((?:[^"\\\n]|\\.)*)"', text))
    return "\n".join(chunks)


def mermaid_type(column: Column) -> str:
    # Mermaid attribute types allow letters, digits, _ - ( ) [ ]; a comma or space is not allowed.
    return re.sub(r"[^A-Za-z0-9_()\[\]-]", "_", column.type.replace(", ", "-").replace(",", "-"))


def cardinality(schema: Schema, fk: ForeignKey) -> str:
    child = schema.tables[fk.table]
    parent_side = "||" if all(child.columns[c].not_null for c in fk.columns) else "|o"
    one_to_one = sorted(fk.columns) == sorted(child.primary_key) or any(
        sorted(fk.columns) == sorted(u) for u in child.uniques.values()
    )
    return f"{parent_side}--{'o|' if one_to_one else 'o{'}"


def relationship_lines(schema: Schema, tables: set[str]) -> list[str]:
    lines = []
    for fk in sorted(schema.foreign_keys.values(), key=lambda f: (f.ref_table, f.table, f.name)):
        if fk.table in tables or fk.ref_table in tables:
            lines.append(f'    {fk.ref_table} {cardinality(schema, fk)} {fk.table} : "{",".join(fk.columns)}"')
    return lines


def entity_lines(schema: Schema, name: str) -> list[str]:
    table = schema.tables[name]
    fk_columns = {c for fk in schema.foreign_keys.values() if fk.table == name for c in fk.columns}
    unique_columns = {c for u in table.uniques.values() if len(u) == 1 for c in u}
    lines = [f"    {name} {{"]
    for column in table.columns.values():
        keys = [k for k, hit in (("PK", column.name in table.primary_key), ("FK", column.name in fk_columns),
                                 ("UK", column.name in unique_columns)) if hit]
        key = (" " + ", ".join(keys)) if keys else ""
        note = ' "NOT NULL"' if column.not_null and column.name not in table.primary_key else ""
        lines.append(f"        {mermaid_type(column)} {column.name}{key}{note}")
    lines.append("    }")
    return lines


def planned_tables(names: list[str], literals: str) -> list[str]:
    return [n for n in names if not re.search(rf"(?<![A-Za-z0-9_]){re.escape(n)}(?![A-Za-z0-9_])", literals)]


def header(figure: str, title: str, sdd: str, version: int, planned: list[str]) -> list[str]:
    return [
        f"%% {figure} {title} ({sdd})",
        f"%% Generated by specs/diagrams/tools/erd_from_migrations.py from Flyway V1..V{version}; do not edit by hand.",
        f"%% planned (no Java code uses the table yet): {', '.join(planned) if planned else 'none'}",
    ]


def check_areas(schema: Schema) -> None:
    seen: dict[str, str] = {}
    for figure, (_, _, names) in AREAS.items():
        for name in names:
            if name in seen:
                raise SystemExit(f"table {name} is listed in both {seen[name]} and {figure}")
            if name not in schema.tables:
                raise SystemExit(f"{figure} lists {name}, which the migrations do not create (or dropped)")
            seen[name] = figure
    missing = sorted(set(schema.tables) - set(seen))
    if missing:
        raise SystemExit(f"tables missing from every area page: {', '.join(missing)}")


def write_pages(schema: Schema, version: int) -> list[Path]:
    check_areas(schema)
    literals = java_string_literals()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    written = []
    all_names = sorted(schema.tables)
    overview = header("ERD-00", "overview: every table and foreign key, no columns", "SDD Figures 3-7", version,
                      planned_tables(all_names, literals))
    overview.append("erDiagram")
    overview += [f"    {name}" for name in all_names]
    overview += relationship_lines(schema, set(all_names))
    written.append(write(OUTPUT / "ERD-00_overview.mmd", overview))
    for figure, (title, sdd, names) in AREAS.items():
        page = header(figure, title, sdd, version, planned_tables(names, literals))
        page.append("erDiagram")
        for name in names:
            page += entity_lines(schema, name)
        # Tables of other areas appear by name only, where a foreign key crosses the page border.
        page += relationship_lines(schema, set(names))
        slug = re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")
        written.append(write(OUTPUT / f"{figure}_{slug}.mmd", page))
    return written


def write(path: Path, lines: list[str]) -> Path:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--list", action="store_true", help="print the final schema and write nothing")
    args = parser.parse_args()
    schema, version = build_schema()
    if args.list:
        print(f"V{version}: {len(schema.tables)} tables, {len(schema.foreign_keys)} foreign keys")
        for name in sorted(schema.tables):
            table = schema.tables[name]
            print(f"{name}  pk={table.primary_key}  columns={len(table.columns)}")
        for fk in sorted(schema.foreign_keys.values(), key=lambda f: f.name):
            print(f"  {fk.name}: {fk.table}({','.join(fk.columns)}) -> {fk.ref_table}({','.join(fk.ref_columns)})")
        return 0
    for path in write_pages(schema, version):
        print(path.relative_to(ROOT).as_posix())
    return 0


if __name__ == "__main__":
    sys.exit(main())
