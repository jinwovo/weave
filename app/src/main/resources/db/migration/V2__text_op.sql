-- Append-only log of RGA text operations for collaboratively-edited shape bodies (sticky / text).
-- The server treats these opaquely — it persists and relays them; the RGA convergence runs on the
-- clients. (room, shape, op_type, op_key) is unique so a retried/echoed op is stored once: op_key is
-- the inserted element's id for INSERT, or the target element's id for DELETE.
create table text_op (
    id          uuid          primary key,
    room_id     varchar(64)   not null,
    shape_id    uuid          not null,
    op_type     varchar(8)    not null,   -- INSERT | DELETE
    op_key      varchar(96)   not null,   -- "l:c:actor" of the element (INSERT) or target (DELETE)
    payload     jsonb         not null,   -- the wire TextOp
    created_at  timestamptz   not null default now(),
    constraint uq_text_op unique (room_id, shape_id, op_type, op_key)
);

create index ix_text_op_room on text_op (room_id, created_at);
