# CryptoPilot diagrams

Mermaid sources (`.mmd`) of the Review 2 diagrams, with a rendered `.png` next to each. Every diagram describes the
code of the commit it is in. A step, state, module or unit that has no code yet is drawn dashed and labelled
"planned". Each title gives the SDD figure that the diagram refreshes.

| Folder | Holds |
| --- | --- |
| `architecture/` | `ARC-01` system overview: deployable units, the backend modules in `backend/src/main/java/com/cryptopilot`, data stores and external systems (SDD Figure 1). |
| `erd/` | Entity relationship diagrams **generated** from the Flyway migrations (V1 to V47): `ERD-00` every table and foreign key without columns, then one page per area, `ERD-01` to `ERD-06` (SDD Figures 3 to 7). Do not edit these by hand. |
| `state/` | State machines `STM-01` to `STM-10`, taken from the status enums, their transition tables and the entity methods that change status. Each transition names its trigger and the UC/BR/NSF id the code cites. `STM-06`, `STM-07` and `STM-10` are drawn from the SRS and marked "planned - not implemented" (SDD Figures 29 to 39). |
| `activity/` | Activity diagrams `ACT-01` to `ACT-05` of workflows WF-01 to WF-05. They use swimlanes (one subgraph per actor and external system) and follow what the code does now (SDD Figures 40 to 44). |
| `tools/` | `erd_from_migrations.py`, the ERD generator. |

## Notes

- **ERD types.** Mermaid allows no comma in an attribute type, so `numeric(28,8)` is written `numeric(28-8)`.
- **ERD columns and keys.** PK, FK and UK mark primary key, foreign key and single-column unique columns. `"NOT NULL"` marks the other required columns.
- **ERD relationships.** Each relationship is labelled with its foreign key columns. `||` means the foreign key column is required and `|o` means it is optional. `o|` means at most one child row (the foreign key is the child's primary key or a unique key) and `o{` means many.
- **"Planned" ERD tables.** In each ERD page's title comment, "planned" lists the tables no Java string literal names, meaning no entity, repository or SQL in `backend/src/main/java` uses them yet.
- **Paper position tables.** SDD Figure 34 (PAPER_POSITION) has no diagram here: V47 dropped `paper_position`. A simulated Futures position is a trading plan and its journal record (`STM-01`, `STM-05`).

## Re-render

Needs Node.js. From `specs/diagrams`, render one file:

```sh
npx -y @mermaid-js/mermaid-cli -i state/STM-01_trading_plan.mmd -o state/STM-01_trading_plan.png -s 2 -b white
```

Render every file:

```sh
for f in $(find . -name '*.mmd'); do npx -y @mermaid-js/mermaid-cli -i "$f" -o "${f%.mmd}.png" -s 2 -b white; done
```

The first run downloads mermaid-cli and a headless Chromium.

## Regenerate the ERD

Needs Python 3.10 or later (standard library only). From the repository root:

```sh
python specs/diagrams/tools/erd_from_migrations.py          # rewrite erd/*.mmd
python specs/diagrams/tools/erd_from_migrations.py --list   # print the replayed schema, write nothing
```

Then re-render the `erd/` files.

The script replays every migration in version order. It stops without writing anything in these cases:
- it meets a statement it cannot replay;
- a table is missing from the `AREAS` map at the top of the script;
- a table is listed in two areas;
- the map names a table the migrations do not create.

When a new migration adds a table, add the table to one area in `AREAS`.

For V47 the replay was checked against a real database. All migrations were applied to `timescale/timescaledb:2.30.1-pg16`, and the replayed columns, types, nullability, primary keys and foreign keys matched `pg_catalog` exactly (864 of 864 lines).
