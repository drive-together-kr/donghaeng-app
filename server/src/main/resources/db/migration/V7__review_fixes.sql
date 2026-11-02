-- 2026-11 전체 점검 반영 (docs/04 '## V7'). V1~V6 은 이미 적용된 DB 가 있어 손대지 않고 덧붙인다.

-- C032: 선두 ROUTE_CHANGED 가 그룹 경로를 갱신한다 — 목적지는 기존 dest_* 를 바꾸고, 경유지는 여기에(Place JSON 배열, 없으면 NULL).
--       늦게 합류한 차의 GROUP_JOINED·초대 미리보기·스냅샷 ARRIVED 판정이 최신 값을 쓴다. 그룹이 끝나면 경유지는 지운다.
ALTER TABLE travel_group ADD COLUMN route_waypoints JSONB;
ALTER TABLE travel_group ADD COLUMN route_updated_at TIMESTAMPTZ;
COMMENT ON COLUMN travel_group.route_waypoints IS '선두의 마지막 경로 경유지 (docs/03 §5.2 ROUTE_CHANGED.waypoints). 그룹 종료 시 NULL';

-- C033·C091: group_event 24시간 보관 뒤 배치 삭제(EventRetention) — occurred_at 단독 조건용 인덱스
CREATE INDEX idx_group_event_occurred_at ON group_event (occurred_at);

-- C033: 만료 초대·refresh 정리(GroupRetention·IdentityRetention)
CREATE INDEX idx_group_invite_expires_at ON group_invite (expires_at);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);

-- C112: 페어링 마지막 폴링 응답이 유실되면 60 s 안의 재폴링에 새 토큰 쌍을 다시 준다 — 처음 발급 시각과 마지막으로 내준 refresh 해시
ALTER TABLE device_authorization ADD COLUMN consumed_at TIMESTAMPTZ;
ALTER TABLE device_authorization ADD COLUMN issued_refresh_hash TEXT;

-- 계약 3: Idempotency-Key — (호출자, 경로, 키) → 첫 성공 응답, 24시간 (docs/03 §2.3·§2.6)
CREATE TABLE idempotency_record (
  caller_id     TEXT NOT NULL,                 -- usr_… (동승자 메시지) / veh_… (무전)
  request_path  TEXT NOT NULL,                 -- 예: /v1/groups/grp_…/messages
  idem_key      TEXT NOT NULL,                 -- [A-Za-z0-9_-]{8,64}
  status        INT NOT NULL,                  -- 첫 응답 상태 코드(2xx)
  body          TEXT NOT NULL,                 -- 첫 응답 본문(JSON)
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,          -- created_at + 24h
  PRIMARY KEY (caller_id, request_path, idem_key)
);
CREATE INDEX idx_idempotency_record_expires_at ON idempotency_record (expires_at);
COMMENT ON TABLE idempotency_record IS 'Idempotency-Key 재시도 응답 (docs/03 §2.3·§2.6). 24시간 뒤 IdempotencyRetention 이 지운다';
