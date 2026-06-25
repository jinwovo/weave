-- Append-only CRDT op-log. The board state is the fold (merge) over these rows; delivery
-- order does NOT affect the converged result. The unique (room, actor, hlc) constraint makes
-- ingestion idempotent: a client that retries an op after reconnecting cannot double-apply it.
create table canvas_op (
    id          uuid          primary key,
    room_id     varchar(64)   not null,
    shape_id    uuid          not null,
    op_type     varchar(16)   not null,   -- CREATE | SET | DELETE
    field       varchar(16),              -- POSITION | SIZE | COLOR | TEXT | Z  (SET only)
    payload     jsonb         not null,   -- op value(s): shape props for CREATE, the single field for SET
    hlc_l       bigint        not null,   -- Hybrid Logical Clock physical component
    hlc_c       integer       not null,   -- HLC logical counter
    actor_id    varchar(64)   not null,   -- LWW tie-breaker; (hlc_l, hlc_c, actor_id) is a global total order
    created_at  timestamptz   not null default now(),
    constraint uq_canvas_op_ts unique (room_id, actor_id, hlc_l, hlc_c)
);

-- Replay a room in timestamp order (any order would converge; this one is human-legible).
create index ix_canvas_op_room_ts on canvas_op (room_id, hlc_l, hlc_c, actor_id);
