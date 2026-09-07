-- 공급사 코드 <-> 내부 식별자 매핑.
--
-- 테이블 2 + 상태 1 이다. canonical/mapping 4테이블로 나누지 않는다.
-- 공급사 간 중복 병합을 하지 않으므로 내부 숙소와 공급사 숙소가 엄격히 1:1 이고,
-- 나눠도 새로운 불변식을 보호하지 않는다.
--
-- ddl-auto 를 쓰지 않는다. 제약 조건이 곧 불변식이므로 스키마 권한은 여기 하나다.

CREATE TABLE supplier_property (
    id                     UUID         PRIMARY KEY,
    supplier_id            VARCHAR(32)  NOT NULL,
    supplier_property_code VARCHAR(128) NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 어느 스냅샷에서 관측됐는지 모르는 행은 존재할 수 없다.
    last_seen_run          UUID         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_supplier_property UNIQUE (supplier_id, supplier_property_code)
);

CREATE TABLE supplier_room_type (
    id                      UUID         PRIMARY KEY,
    -- ON DELETE CASCADE 없음. 하드 삭제를 하지 않으므로 정상 흐름에서 발동하지 않고,
    -- 발동한다면 그건 막고 싶은 실수다. restrict-by-default.
    property_id             UUID         NOT NULL REFERENCES supplier_property(id),
    supplier_room_type_code VARCHAR(128) NOT NULL,
    name                    VARCHAR(255) NOT NULL,
    max_occupancy           INTEGER      NOT NULL,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    last_seen_run           UUID         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 객실 타입 코드는 해당 숙소 안에서만 유일하다.
    -- 첫 컬럼이 property_id 인 것이 그 유일성 범위의 표현이다.
    CONSTRAINT uk_supplier_room_type UNIQUE (property_id, supplier_room_type_code),
    CONSTRAINT ck_room_type_max_occupancy CHECK (max_occupancy > 0)
);

-- 3컬럼. property_count 같은 파생 상태는 두지 않는다.
-- 현재 개수는 매핑 테이블에서 세면 나오고, 변화는 텔레메트리의 몫이다.
-- 파생 상태를 저장하면 "2000 -> 0 이 일어났다" 는 사실을 다음 동기화가 덮어버린다.
CREATE TABLE supplier_catalog_state (
    supplier_id      VARCHAR(32) PRIMARY KEY,
    last_success_run UUID        NOT NULL,
    last_success_at  TIMESTAMPTZ NOT NULL
);

-- 검색 경로는 활성 행만 읽는다. 논리 삭제 결정이 인덱스에 반영된 것이다.
CREATE INDEX ix_supplier_property_active  ON supplier_property (supplier_id) WHERE active;
CREATE INDEX ix_supplier_room_type_active ON supplier_room_type (property_id) WHERE active;
