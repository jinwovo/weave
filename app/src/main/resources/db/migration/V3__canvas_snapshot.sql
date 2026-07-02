-- Snapshot-accelerated replay: bound cold-start recovery to the ops appended after the last
-- snapshot instead of the whole op-log.
--
-- seq is an insertion-order watermark: "seq <= upto_seq" names exactly the subset of ops a
-- snapshot has folded. Correctness does NOT depend on seq agreeing with HLC order — document
-- merge is a join (commutative, associative, idempotent), so the fold of any subset plus the
-- remaining ops rebuilds the exact full document. Existing rows are backfilled in arbitrary
-- order, which is equally fine.
alter table canvas_op add column seq bigserial;

create index ix_canvas_op_room_seq on canvas_op (room_id, seq);

-- One materialised fold per room: every shape's registers (value + HLC timestamp), tombstones
-- included — dropping tombstones here could resurrect deleted shapes if an older-stamped op
-- arrives later (e.g. an offline client flushing its outbox), so they stay.
create table canvas_snapshot (
    room_id     varchar(64)   primary key,
    upto_seq    bigint        not null,   -- watermark: ops with seq <= this are folded in
    state       jsonb         not null,   -- the serialised CanvasDoc
    shape_count integer       not null,
    op_count    bigint        not null,   -- total ops folded over this room's lifetime
    updated_at  timestamptz   not null default now()
);
