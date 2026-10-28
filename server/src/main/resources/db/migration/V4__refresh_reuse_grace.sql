-- T33 리뷰: refresh 회전 응답 유실 대비 재사용 유예 (docs/03 §1.1, docs/04 '## V4'). V1~V3 는 이미 적용된 DB 가 있어 손대지 않고 덧붙인다.

-- 회전으로 폐기된 토큰이 가리키는 다음 토큰의 해시. 회전 폐기면 채워지고, 페어링 해제·소유자 이전(revokeAll)·유예 재발급으로 폐기된 것은 NULL.
-- 이전 토큰이 유예(60 s) 안에 다시 오고 다음 토큰이 아직 한 번도 쓰이지 않았으면(revoked_at IS NULL) 응답 유실 재시도로 보고 다시 회전한다.
ALTER TABLE refresh_token ADD COLUMN replaced_by TEXT;
COMMENT ON COLUMN refresh_token.replaced_by IS '회전으로 이 토큰을 대체한 다음 토큰의 SHA-256 (재사용 유예 판정용, docs/03 §1.1). 회전 외 폐기는 NULL';
