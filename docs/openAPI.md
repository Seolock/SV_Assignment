# SV Backend API 명세서

## 공통 사항

| 항목 | 내용 |
|---|---|
| Base URL | `http://localhost:8000/api/v1` |
| Content-Type | `application/json` |
| JSON 필드 명명 규칙 | snake_case |
| 시간 형식 | ISO-8601 UTC 문자열 (예: `2026-09-28T12:34:56.789Z`) |
| OpenAPI 문서 | `GET /v3/api-docs` (JSON), Swagger UI: `/swagger-ui/index.html` |

### 공통 에러 응답

| HTTP 상태 | 발생 조건 |
|---|---|
| `400 Bad Request` | 요청 값 검증 실패 |
| `404 Not Found` | 장치 또는 명령 없음 |
| `409 Conflict` | 이미 완료된 명령에 결과 보고 |

```json
{
  "timestamp": "2026-09-28T12:34:56.789+00:00",
  "status": 404,
  "error": "Not Found",
  "path": "/api/v1/devices/dev-1"
}
```

---

## API 목록

| # | Method | URL | 설명 | 호출 주체 | 성공 응답 |
|---|---|---|---|---|---|
| 1 | `POST` | `/devices` | 장치 등록 | 에이전트 | `201` / `200` |
| 2 | `GET` | `/devices` | 장치 목록 조회 | 콘솔 | `200` |
| 3 | `GET` | `/devices/{id}` | 장치 상세 조회 | 콘솔 | `200` |
| 4 | `POST` | `/devices/{id}/heartbeat` | 하트비트 전송 | 에이전트 | `204` |
| 5 | `POST` | `/devices/{id}/telemetry` | 텔레메트리 저장 | 에이전트 | `200` |
| 6 | `GET` | `/devices/{id}/telemetry` | 텔레메트리 이력 조회 | 콘솔 | `200` |
| 7 | `POST` | `/devices/{id}/commands` | 명령 생성 | 콘솔 | `201` |
| 8 | `GET` | `/devices/{id}/commands` | 명령 이력 조회 | 콘솔 | `200` |
| 9 | `GET` | `/devices/{id}/commands/pending` | 대기 명령 가져오기 | 에이전트 | `200` |
| 10 | `PATCH` | `/commands/{cmdId}` | 명령 실행 결과 보고 | 에이전트 | `200` |
| 11 | `GET` | `/commands/{cmdId}` | 명령 상세 조회 | 콘솔 | `200` |
| 12 | `GET` | `/devices/{id}/rule` | 규칙 조회 | 콘솔 | `200` |
| 13 | `PUT` | `/devices/{id}/rule` | 규칙 저장 | 콘솔 | `200` |

---

## 1. 장치 등록

`POST /api/v1/devices`

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `device_id` | string | O (공백 불가) | 장치 ID |
| `fw` | string | X | 펌웨어 버전 |

```json
{ "device_id": "dev-1", "fw": "1.0.0" }
```

**Response**

| 상태 | 바디 | 설명 |
|---|---|---|
| `201 Created` | 없음 | 신규 등록 |
| `200 OK` | 없음 | 이미 등록된 장치 (fw 갱신) |
| `400 Bad Request` | 에러 | `device_id` 누락/공백 |

---

## 2. 장치 목록 조회

`GET /api/v1/devices`

**Response `200 OK`** — 배열

| 필드 | 타입 | 설명 |
|---|---|---|
| `device_id` | string | 장치 ID |
| `status` | string | `ONLINE` / `OFFLINE` |
| `last_heartbeat_at` | string(datetime) \| null | 마지막 하트비트 시각 |
| `latest` | object \| null | 가장 최근 텔레메트리 (`seq`, `ts`, `temperature`, `humidity`) |

```json
[
  {
    "device_id": "dev-1",
    "status": "ONLINE",
    "last_heartbeat_at": "2026-09-28T12:34:50Z",
    "latest": { "seq": 42, "ts": "2026-09-28T12:34:45Z", "temperature": 26.3, "humidity": 51.2 }
  }
]
```

---

## 3. 장치 상세 조회

`GET /api/v1/devices/{id}`

**Path Parameter**

| 이름 | 타입 | 설명 |
|---|---|---|
| `id` | string | 장치 ID |

**Response `200 OK`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `device_id` | string | 장치 ID |
| `fw` | string \| null | 펌웨어 버전 |
| `status` | string | `ONLINE` / `OFFLINE` |
| `last_heartbeat_at` | string(datetime) \| null | 마지막 하트비트 시각 |
| `latest` | object \| null | 가장 최근 텔레메트리 (`seq`, `ts`, `temperature`, `humidity`) |
| `rule` | object \| null | 설정된 규칙 (`metric`, `threshold`, `last_action`) |
| `recent_commands` | string[] | 최근 명령 ID 최대 3개 (최신순) |

```json
{
  "device_id": "dev-1",
  "fw": "1.0.0",
  "status": "ONLINE",
  "last_heartbeat_at": "2026-09-28T12:34:50Z",
  "latest": { "seq": 42, "ts": "2026-09-28T12:34:45Z", "temperature": 26.3, "humidity": 51.2 },
  "rule": { "metric": "temperature", "threshold": 25.0, "last_action": "on" },
  "recent_commands": ["cmd-a1b2", "cmd-c3d4", "cmd-e5f6"]
}
```

**Error**: `404` 장치 없음

---

## 4. 하트비트 전송

`POST /api/v1/devices/{id}/heartbeat`

| 구분 | 내용 |
|---|---|
| Path | `id`: 장치 ID |
| Request Body | 없음 |
| Response | `204 No Content` (바디 없음) |
| Error | `404` 장치 없음 |

---

## 5. 텔레메트리 저장

`POST /api/v1/devices/{id}/telemetry`

**Path Parameter**: `id` — 장치 ID

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `seq` | integer(int64) | O | 시퀀스 번호 |
| `ts` | string(datetime) | O | 측정 시각 |
| `temperature` | number | O | 온도 |
| `humidity` | number | O | 습도 |

```json
{ "seq": 42, "ts": "2026-09-28T12:34:45Z", "temperature": 26.3, "humidity": 51.2 }
```

**Response**

| 상태 | 바디 |
|---|---|
| `200 OK` | 없음 |
| `400 Bad Request` | 필수 필드 누락 |
| `404 Not Found` | 장치 없음 |

---

## 6. 텔레메트리 이력 조회

`GET /api/v1/devices/{id}/telemetry`

**Parameters**

| 이름 | 위치 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|---|
| `id` | path | string | O | - | 장치 ID |
| `last` | query | string | X | `1h` | 조회 기간 (`s`, `m`, `h` 단위, 예: `30m`, `2h`, `1h30m`) |
| `limit` | query | integer | X | `50` | 최대 개수 (≥ 1) |

**Response `200 OK`** — 배열 (시간 오름차순)

| 필드 | 타입 | 설명 |
|---|---|---|
| `seq` | integer(int64) | 시퀀스 번호 |
| `ts` | string(datetime) | 측정 시각 |
| `temperature` | number | 온도 |
| `humidity` | number | 습도 |

```json
[
  { "seq": 41, "ts": "2026-09-28T12:34:40Z", "temperature": 26.1, "humidity": 51.0 },
  { "seq": 42, "ts": "2026-09-28T12:34:45Z", "temperature": 26.3, "humidity": 51.2 }
]
```

**Error**: `400` 잘못된 `last` / `limit < 1`, `404` 장치 없음

---

## 7. 명령 생성

`POST /api/v1/devices/{id}/commands`

**Path Parameter**: `id` — 장치 ID

**Request Body**

| 필드 | 타입 | 필수 | 허용 값 | 설명 |
|---|---|---|---|---|
| `type` | string | O | `SET_LED` | 명령 종류 |
| `value` | string | O | `on`, `off` | 명령 값 |
| `source` | string | O | - | 명령 출처 (예: `console`) |

```json
{ "type": "SET_LED", "value": "on", "source": "console" }
```

**Response `201 Created`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `command_id` | string | 생성된 명령 ID |
| `status` | string | `PENDING` |
| `created_at` | string(datetime) | 생성 시각 |

```json
{ "command_id": "cmd-a1b2", "status": "PENDING", "created_at": "2026-09-28T12:35:00Z" }
```

**Error**: `400` 검증 실패, `404` 장치 없음

---

## 8. 명령 이력 조회

`GET /api/v1/devices/{id}/commands`

**Parameters**

| 이름 | 위치 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|---|
| `id` | path | string | O | - | 장치 ID |
| `limit` | query | integer | X | `20` | 최대 개수 (≥ 1) |

**Response `200 OK`** — 배열 (생성 시각 오름차순)

| 필드 | 타입 | 설명 |
|---|---|---|
| `command_id` | string | 명령 ID |
| `type` | string | `SET_LED` |
| `value` | string | `on` / `off` |
| `status` | string | `PENDING` / `SENT` / `ACKED` / `FAILED` / `TIMEOUT` |
| `reason` | string \| null | 실패 사유 |
| `source` | string | 명령 출처 (`console`, `rule` 등) |
| `created_at` | string(datetime) | 생성 시각 |

```json
[
  {
    "command_id": "cmd-a1b2",
    "type": "SET_LED",
    "value": "on",
    "status": "ACKED",
    "reason": null,
    "source": "console",
    "created_at": "2026-09-28T12:35:00Z"
  }
]
```

**Error**: `400` `limit < 1`, `404` 장치 없음

---

## 9. 대기 명령 가져오기

`GET /api/v1/devices/{id}/commands/pending`

**Path Parameter**: `id` — 장치 ID

**Response `200 OK`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `command_id` | string | 명령 ID |
| `type` | string | 명령 종류 |
| `value` | string | 명령 값 |

```json
{ "command_id": "cmd-a1b2", "type": "SET_LED", "value": "on" }
```

> 대기 명령이 없으면 `200 OK`와 빈 바디를 반환합니다.

**Error**: `404` 장치 없음

---

## 10. 명령 실행 결과 보고

`PATCH /api/v1/commands/{cmdId}`

**Path Parameter**: `cmdId` — 명령 ID

**Request Body**

| 필드 | 타입 | 필수 | 허용 값 | 설명 |
|---|---|---|---|---|
| `status` | string | O | `ACKED`, `FAILED` | 실행 결과 |
| `reason` | string | X | - | 실패 사유 등 |

```json
{ "status": "FAILED", "reason": "gpio error" }
```

**Response**

| 상태 | 바디 | 설명 |
|---|---|---|
| `200 OK` | 없음 | 반영 완료 |
| `400 Bad Request` | 에러 | 검증 실패 |
| `404 Not Found` | 에러 | 명령 없음 |
| `409 Conflict` | 에러 | 이미 완료된 명령 |

---

## 11. 명령 상세 조회

`GET /api/v1/commands/{cmdId}`

**Path Parameter**: `cmdId` — 명령 ID

**Response `200 OK`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `command_id` | string | 명령 ID |
| `type` | string | `SET_LED` |
| `value` | string | `on` / `off` |
| `status` | string | `PENDING` / `SENT` / `ACKED` / `FAILED` / `TIMEOUT` |
| `reason` | string \| null | 실패 사유 |
| `source` | string | 명령 출처 (`console`, `rule` 등) |
| `created_at` | string(datetime) | 생성 시각 |

```json
{
  "command_id": "cmd-a1b2",
  "type": "SET_LED",
  "value": "on",
  "status": "TIMEOUT",
  "reason": null,
  "source": "console",
  "created_at": "2026-09-28T12:35:00Z"
}
```

**Error**: `404` 명령 없음

---

## 12. 규칙 조회

`GET /api/v1/devices/{id}/rule`

**Path Parameter**: `id` — 장치 ID

**Response `200 OK`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `metric` | string | `temperature` |
| `threshold` | number | 임계값 |
| `last_action` | string \| null | 규칙에 의해 마지막으로 실행된 값 (`on` / `off`) |

```json
{ "metric": "temperature", "threshold": 25.0, "last_action": "on" }
```

> 규칙이 없으면 `200 OK`와 빈 바디를 반환합니다.

**Error**: `404` 장치 없음

---

## 13. 규칙 저장

`PUT /api/v1/devices/{id}/rule`

**Path Parameter**: `id` — 장치 ID

**Request Body**

| 필드 | 타입 | 필수 | 허용 값 | 설명 |
|---|---|---|---|---|
| `metric` | string | O | `temperature` | 비교 대상 지표 |
| `threshold` | number | O | - | 임계값 |

```json
{ "metric": "temperature", "threshold": 25.0 }
```

**Response `200 OK`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `metric` | string | `temperature` |
| `threshold` | number | 임계값 |
| `last_action` | string \| null | 규칙에 의해 마지막으로 실행된 값 (`on` / `off`) |

```json
{ "metric": "temperature", "threshold": 25.0, "last_action": null }
```

**Error**: `400` 검증 실패, `404` 장치 없음
