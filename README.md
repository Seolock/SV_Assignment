# SV Assignment

현장 센서 노드를 원격에서 관측하고 제어하는 시스템.

```
┌────────┐    ┌───────┐    ┌─────────┐    ┌──────────┐
│ device │◀──▶│ agent │◀──▶│ backend │◀──▶│ database │
└────────┘    └───────┘    └─────────┘    └──────────┘
                                ▲
                            ┌───┴───┐
                            │ svcli │
                            └───────┘
```

## 구조

```bash
.
├── agent/
│   ├── svagent.py                  # 에이전트
│   └── config.yaml                 # 에이전트 설정
├── backend/
│   ├── src/main/java/com/sv/backend/ # 백엔드
│   │   ├── BackendController.java
│   │   ├── BackendService.java
│   │   ├── Dto.java
│   │   ├── domain/
│   │   └── repository/
│   └── Dockerfile                  # 빌드
├── console/
│   └── svctl.py                    # 운영자 CLI
├── tools/
│   └── device_sim.py               # 제공된 시뮬레이터
├── scripts/
│   └── run_scenarios.sh            # 검증 시나리오
├── docs/
│   ├── DESIGN.md                   # 설계 설명
│   └── openapi.json                # API 명세
├── Q&A.md                          # 서술 문항 답변
├── requirements.txt                # Python 의존성
└── docker-compose.yml              # backend + db
```

## 요구 사항

- Docker / Docker Compose (Backend + DB)
- Python 3.9+ (agent, CLI, device simulator)


## 실행

```bash
pip install -r requirements.txt

docker compose up -d               # backend + DB
python3 tools/device_sim.py        # device simulator
python3 agent/svagent.py           # agent

python3 console/svctl.py <명령>
```


## 운영자 CLI

```bash
python3 console/svctl.py devices                            # 장치 목록
python3 console/svctl.py status dev-001                     # 상세 + 규칙 + 최근 명령
python3 console/svctl.py history dev-001 --last 10m --limit 20
python3 console/svctl.py rule dev-001 --threshold 30        # 규칙 설정
python3 console/svctl.py rule dev-001                       # 규칙 조회
python3 console/svctl.py led dev-001 on                     # 명령 생성 후 결과까지 추적
python3 console/svctl.py commands dev-001                   # 명령 이력
```

백엔드 주소는 `--url` 또는 환경변수 `SVCTL_URL` 로 바꾼다. 기본값은 `http://localhost:8000`.

## 시뮬레이터 제어

시나리오를 손으로 재현할 때 쓴다. 실행 중인 시뮬레이터에 명령을 보낸다.

```bash
python3 tools/device_sim.py --ctl STATUS      # 현재 상태
python3 tools/device_sim.py --ctl "SET t=35"  # 온도 고정
python3 tools/device_sim.py --ctl AUTO        # 고정 해제
python3 tools/device_sim.py --ctl PAUSE       # 보고 중단
python3 tools/device_sim.py --ctl RESUME
python3 tools/device_sim.py --ctl "DROP 10"   # 10초간 연결 거부
```

## 검증

```bash
scripts/run_scenarios.sh          # 시나리오 1~5 전부
scripts/run_scenarios.sh 3 5      # 지정한 것만
```

| # | 시나리오          | 조작                                            | 기대                                                                   |
|---|-------------------|-------------------------------------------------|------------------------------------------------------------------------|
| 1 | 정상 수집         | 시뮬레이터 → 에이전트 기동                      | 10초 안에 `ONLINE` + 최신 온도, `history --last 5m` 에 샘플            |
| 2 | 원격 제어         | `svctl led dev-001 on`                          | 5초 안에 `PENDING` → `ACKED`, 시뮬레이터 `led=1`                       |
| 3 | 자동 규칙         | `rule --threshold 30` → `SET t=35` → `SET t=20` | 15초 안에 `source=rule` on 1건 `ACKED`, 35℃ 유지 시 추가 없음, off 1건 |
| 4 | OFFLINE 감지·복구 | 에이전트 종료 → 재기동                          | `OFFLINE` 전환, 10초 안에 `ONLINE` 복귀, 이후 `svctl led` 정상         |
| 5 | 연결 끊김 내성    | `DROP 15`                                       | 프로세스 생존 + 재연결 로그, 15초 뒤 수집 재개                         |

각 항목을 `PASS` / `FAIL` 로 찍고 마지막에 합계를 낸다.

## 설계

데이터 모델, 설계 결정 등은 [docs/DESIGN.md](docs/DESIGN.md).
API 명세는 [docs/openapi.json](docs/openapi.json).
