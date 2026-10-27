-- T10 identity. V1 은 이미 적용돼 있어(Flyway 체크섬) 손대지 않고 여기서 덧붙인다 (docs/04 '## V2').

-- 2026-10-27 재설계: 제공자는 dev + 공식 앱 4종. V1 인라인 주석(dev | pleos | kakao | google)은 이 COMMENT 로 대체한다. CHECK 제약은 두지 않는다(제공자 추가 예정).
COMMENT ON COLUMN app_user.provider IS 'dev | bluelink | myhyundai | kia_connect | genesis_connected';
COMMENT ON COLUMN app_user.provider_sub IS 'IdP subject (ExternalIdentity.sub). dev 는 소문자 이메일';

-- 조회 경로: GET /me/vehicles(owner_id), 만료 코드 정리(expires_at), 주체별 refresh 토큰 폐기(subject)
CREATE INDEX idx_vehicle_owner_id ON vehicle (owner_id);
CREATE INDEX idx_device_authorization_expires_at ON device_authorization (expires_at);
CREATE INDEX idx_refresh_token_subject ON refresh_token (subject);

-- 사용자당 활성(해제되지 않은) 대표 차량은 하나 — PATCH /me/vehicles {isPrimary:true} 와 페어링 승인(첫 차량) 이 지킨다
CREATE UNIQUE INDEX uq_vehicle_owner_primary ON vehicle (owner_id) WHERE is_primary AND revoked_at IS NULL;
