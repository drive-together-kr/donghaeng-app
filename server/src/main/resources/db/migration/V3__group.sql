-- T14 group. V1·V2 는 이미 적용된 DB 가 있어 손대지 않고 여기서 덧붙인다 (docs/04 '## V3').

-- 그룹 이벤트의 음성 문구 (docs/03 §5.4 GroupEvent.speech). 음성 생략(중복 억제) 시 NULL.
ALTER TABLE group_event ADD COLUMN speech TEXT;
COMMENT ON COLUMN group_event.speech IS '서버가 만든 한국어 음성 문구 (docs/03 §5.4). 음성 생략 시 NULL';
COMMENT ON COLUMN group_invite.token_hash IS 'SHA-256(초대 토큰 원문 inv_…) hex — 원문은 저장하지 않는다';

-- 조회 경로: 차량의 현재 그룹(합류·생성 시 "활성 그룹 하나" 규칙, T15 ACL), 그룹의 초대 재발급(이전 초대 만료), 내 그룹 목록
CREATE INDEX idx_group_member_vehicle_active ON group_member (vehicle_id) WHERE left_at IS NULL;
CREATE INDEX idx_group_invite_group_id ON group_invite (group_id);
CREATE INDEX idx_travel_group_status_created ON travel_group (status, created_at DESC);
