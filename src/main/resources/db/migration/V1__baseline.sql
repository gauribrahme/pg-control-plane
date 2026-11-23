-- Portable SQL: runs unchanged on PostgreSQL and on H2 in PostgreSQL mode (tests).

create table tenant (
    id            varchar(64) primary key,
    max_instances int not null,
    created_at    timestamp with time zone not null
);

create table instance (
    id             varchar(36) primary key,
    tenant_id      varchar(64) not null,
    name           varchar(63) not null,
    plan           varchar(16) not null,
    storage_gb     int not null,
    pg_version     varchar(8) not null,
    state          varchar(16) not null,
    endpoint       varchar(255),
    failure_reason varchar(1000),
    drift_detected boolean not null default false,
    version        bigint not null default 0,
    created_at     timestamp with time zone not null,
    updated_at     timestamp with time zone not null
);
create index idx_instance_tenant_state on instance (tenant_id, state);
create index idx_instance_state on instance (state);

create table idempotency_record (
    id           varchar(36) primary key,
    tenant_id    varchar(64) not null,
    idem_key     varchar(128) not null,
    request_hash varchar(64) not null,
    instance_id  varchar(36) not null,
    created_at   timestamp with time zone not null,
    constraint uq_idem unique (tenant_id, idem_key)
);

create table backup (
    id          varchar(36) primary key,
    instance_id varchar(36) not null,
    tenant_id   varchar(64) not null,
    status      varchar(16) not null,
    location    varchar(255),
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);
create index idx_backup_instance on backup (instance_id);

-- Outbox / work queue: written in the same transaction as the instance change.
create table work_item (
    id              varchar(36) primary key,
    instance_id     varchar(36) not null,
    type            varchar(16) not null,
    payload         varchar(255),
    status          varchar(16) not null,
    attempts        int not null default 0,
    max_attempts    int not null,
    next_attempt_at timestamp with time zone not null,
    locked_by       varchar(64),
    locked_until    timestamp with time zone,
    last_error      varchar(2000),
    created_at      timestamp with time zone not null,
    updated_at      timestamp with time zone not null
);
create index idx_work_item_claim on work_item (status, next_attempt_at);
