-- 급제동 수신 대상 (docs/03 §5.4 GroupEvent.audience, docs/04 '## V4'). V1~V3 은 이미 적용된 DB 가 있어 손대지 않고 덧붙인다.
-- NULL = 그룹 전체(지금까지의 모든 이벤트), JSON 문자열 배열 = 그 vehicleId 들만, [] = 아무도 알리지 않음(기록·피드용).
ALTER TABLE group_event ADD COLUMN audience JSONB;
COMMENT ON COLUMN group_event.audience IS '알릴 차량 vehicleId JSON 배열 (docs/03 §5.4). NULL = 그룹 전체. HARD_BRAKE 는 출처보다 뒤에 있는 차';
