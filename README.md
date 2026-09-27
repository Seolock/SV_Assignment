# SV Assignment

현장 센서 노드를 원격에서 관측하고 제어하는 시스템.

**1단계 전체 + 심화 항목 C.**


## 구조

```bash
.
├── agent/
│   ├── svagent.py                     # 에이전트
│   └── config.yaml                    # 설정
├── backend/
│   ├── src/main/java/com/sv/backend/  # 백엔드
│   │   ├── BackendApplication.java
│   │   ├── BackendController.java
│   │   ├── BackendService.java
│   │   ├── Dto.java
│   │   ├── domain/
│   │   └── repository/
│   ├── src/main/resources/
│   │   └── application.yaml           # 백엔드 설정
│   ├── build.gradle                   # 의존성
│   └── Dockerfile                     # 빌드
├── console/
│   └── svctl.py                       # 운영자 CLI
├── tools/
│   └── device_sim.py                  # 제공된 시뮬레이터
├── scripts/
│   └── run_scenarios.sh               # 검증 시나리오
├── docs/
│   ├── DESIGN.md                      # 설계 설명
│   └── openAPI.md                     # API 명세
├── Q&A.md                             # 서술 문항 답변
├── requirements.txt                   # Python 의존성
└── docker-compose.yml                 # backend + db
```

## 요구 사항

- Docker / Docker Compose (Backend + DB)
- Python 3.9+ (agent, CLI, device simulator)


## 실행

```bash
pip install -r requirements.txt

docker compose up -d               # backend + database
python3 tools/device_sim.py        # device simulator
python3 agent/svagent.py           # agent

python3 console/svctl.py <명령>     # command line
```


## 운영자 CLI

```bash
svctl devices                      # 장치 목록
svctl status <id>                  # 장치 상세
svctl history <id>                 # 기록 --last 10m --limit 20
svctl rule <id> --threshold 30     # 규칙 설정
svctl rule <id>                    # 규칙 조회
svctl led <id> on                  # 명령 실행
svctl commands <id>                # 명령 이력
```

백엔드 주소는 `--url` 또는 환경변수 `SVCTL_URL` 로 바꾼다. 기본값은 `http://localhost:8000`.

## 시뮬레이터 제어

시나리오를 손으로 재현할 때 쓴다. 실행 중인 시뮬레이터에 명령을 보낸다.

```bash
python3 tools/device_sim.py --ctl STATUS      # 현재 상태
python3 tools/device_sim.py --ctl "SET t=35"  # 온도 고정
python3 tools/device_sim.py --ctl AUTO        # 고정 해제
python3 tools/device_sim.py --ctl PAUSE       # 보고 중단
python3 tools/device_sim.py --ctl RESUME      # 보고 재개
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

### 직접 재현하기

터미널 3개를 쓴다. **A** 시뮬레이터, **B** 에이전트, **C** 조작.

```bash
# C
docker compose up -d
# A
python3 tools/device_sim.py
# B
python3 agent/svagent.py
```

#### 1. 정상 수집

기동 후 10초쯤 기다렸다가 **C** 에서.

```bash
python3 console/svctl.py devices
python3 console/svctl.py history dev-001 --last 5m
```

`status` 가 `ONLINE`, `latest` 에 최신 온도가 있고, `history` 에 샘플 행이 쌓여 있으면 된다.

#### 2. 원격 제어

```bash
python3 console/svctl.py led dev-001 on
python3 tools/device_sim.py --ctl STATUS
```

`led` 가 `status: PENDING` 을 찍고 5초 안에 `ACKED` 를 더 찍는다.
이어서 `STATUS` 응답에 `led=1` 이 보인다.

#### 3. 자동 규칙

```bash
python3 console/svctl.py rule dev-001 --threshold 30
python3 tools/device_sim.py --ctl "SET t=35"

python3 console/svctl.py commands dev-001      # source=rule, value=on, ACKED 1건

python3 tools/device_sim.py --ctl "SET t=20"

python3 console/svctl.py commands dev-001      # value=off 1건 추가

python3 tools/device_sim.py --ctl AUTO         # 온도 고정 해제
```

35℃ 를 유지하는 동안 명령이 더 생기지 않는 것이 핵심이다.

#### 4. OFFLINE 감지·복구

**B** 에서 `Ctrl+C` 로 에이전트를 끈다. 40초쯤 기다렸다가 **C** 에서.

```bash
python3 console/svctl.py devices               # OFFLINE
```

**B** 에서 다시 띄우고 10초 안에 확인한다.

```bash
python3 agent/svagent.py                       # B
python3 console/svctl.py devices               # C. ONLINE 복귀
python3 console/svctl.py led dev-001 off       # C. ACKED
```

#### 5. 연결 끊김 내성

```bash
python3 tools/device_sim.py --ctl "DROP 15"
```

**B** 의 로그에 `connection error` 와 재연결 시도가 쌓이지만 프로세스는 살아 있다.
15초 뒤 자동으로 다시 붙는다. **C** 에서 수집이 이어지는지 본다.

```bash
python3 console/svctl.py history dev-001 --last 1m
```

끊긴 동안의 데이터는 유실된다. 프로세스가 죽지 않고 스스로 복구하는 것이 기준이다.


## 설계

상세한 설계 결정, 데이터 모델 등은 [docs/DESIGN.md](docs/DESIGN.md).

백엔드 API 명세는 [docs/openAPI.md](docs/openAPI.md).

AI활용: CLI 출력 형식, 시나리오 검증 스크립트, 백엔드 단위 테스트, 코드 디테일 검사
