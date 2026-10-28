-- F16 무전(녹음형 PTT) 저장 (docs/03 §2.6, docs/04 '## V6'). V1~V5 는 이미 적용된 DB 가 있어 손대지 않고 덧붙인다.
-- 보관: 업로드 + 24시간(expires_at). 그룹이 끝나면(GROUP_ENDED) 그 그룹 무전을 바로 지운다(voice.VoiceRetention). 서버는 오디오 내용을 해석하지 않는다.
CREATE TABLE voice_message (
  voice_id          TEXT PRIMARY KEY,                                     -- vce_ + ULID
  group_id          TEXT NOT NULL REFERENCES travel_group(group_id),
  sender_vehicle_id TEXT NOT NULL REFERENCES vehicle(vehicle_id),
  event_id          TEXT NOT NULL,                                        -- 이 무전을 알린 VOICE_MESSAGE 그룹 이벤트(group_event.event_id)
  mime              TEXT NOT NULL,                                        -- audio/mp4 | audio/aac | audio/wav
  duration_ms       INT NOT NULL CHECK (duration_ms > 0),                 -- 상한 16 000 은 코드(VoiceRules)가 지킨다
  size_bytes        INT NOT NULL CHECK (size_bytes > 0),                  -- 상한 512 KB 는 코드·Spring multipart 가 지킨다
  audio             BYTEA NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  expires_at        TIMESTAMPTZ NOT NULL
);
-- AAC 는 이미 압축돼 있어 TOAST 압축 시도가 헛수고 — 압축 없이 바깥(out-of-line) 저장
ALTER TABLE voice_message ALTER COLUMN audio SET STORAGE EXTERNAL;

COMMENT ON TABLE voice_message IS 'F16 무전 녹음 (docs/03 §2.6). 업로드 + 24시간 보관, 그룹 종료 시 즉시 삭제';
COMMENT ON COLUMN voice_message.audio IS '올린 오디오 원본 바이트(최대 512 KB). 서버는 해석·변환하지 않는다';

-- 조회 경로: 그룹 종료 시 삭제, 만료 정리, 같은 차 빈도 제한(마지막 업로드 시각)
CREATE INDEX idx_voice_message_group_id ON voice_message (group_id);
CREATE INDEX idx_voice_message_expires_at ON voice_message (expires_at);
CREATE INDEX idx_voice_message_sender_created ON voice_message (sender_vehicle_id, created_at DESC);
