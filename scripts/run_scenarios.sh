#!/usr/bin/env bash
# 검증 시나리오 1~5 일괄 실행.
#
#   scripts/run_scenarios.sh          # 1~5 전부
#   scripts/run_scenarios.sh 3 5      # 지정한 것만
#
# 백엔드+DB 는 docker compose 로, 시뮬레이터와 에이전트는 이 스크립트가 직접 띄운다.
# (시나리오 4 가 에이전트를 죽였다 살려야 하므로 프로세스를 스크립트가 소유한다)
# 로그: /tmp/sv-scenarios/
set -uo pipefail
set -m          # 잡 컨트롤: 이게 없으면 배경으로 띄운 에이전트가 SIGINT 를 무시한다
cd "$(dirname "$0")/.."

CONFIG=agent/config.yaml
ID="$(awk '/^device_id:/{print $2}' "$CONFIG")"
BASE="$(awk '/^backend_url:/{print $2}' "$CONFIG")/api/v1"
LOGDIR=/tmp/sv-scenarios
AGENT_LOG="$LOGDIR/agent.log"
SIM_LOG="$LOGDIR/sim.log"
PASS=0
FAIL=0

mkdir -p "$LOGDIR"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
ng()   { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
info() { printf '       %s\n' "$*"; }

# check <설명> <판정명령...>
check() { local what=$1; shift; if "$@"; then ok "$what"; else ng "$what"; fi; }
# retry <최대초> <판정명령...>  : 성공까지 걸린 초를 ELAPSED 에 남긴다
retry() {
    local limit=$1; shift
    for ELAPSED in $(seq 0 "$limit"); do
        "$@" && return 0
        sleep 1
    done
    return 1
}
not() { ! "$@"; }

sim()   { python3 tools/device_sim.py --ctl "$*"; }
svctl() { python3 console/svctl.py "$@"; }
q()     { curl -s "$BASE$1"; }
jqv()   { q "$1" | jq -r "$2"; }

backend_up()    { [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/devices")" = 200 ]; }
device_status() { jqv "/devices/$ID" '.status'; }
is_online()     { [ "$(device_status)" = ONLINE ]; }
is_offline()    { [ "$(device_status)" = OFFLINE ]; }
latest_seq()    { jqv "/devices/$ID" '.latest.seq // 0'; }
seq_above()     { [ "$(latest_seq)" -gt "$1" ] 2>/dev/null; }
sim_led()       { sim STATUS | sed -n 's/.*led=\([01]\).*/\1/p'; }

# cmd_ids <jq 조건> : 조건에 맞는 명령 id 목록 (정렬)
cmd_ids()   { q "/devices/$ID/commands?limit=200" | jq -r ".[] | select($1) | .command_id" | sort; }
snapshot()  { cmd_ids "$1" >"$LOGDIR/before.txt"; }
new_ids()   { comm -13 "$LOGDIR/before.txt" <(cmd_ids "$1"); }
new_count() { new_ids "$1" | grep -c . ; }
has_new()   { [ "$(new_count "$1")" -ge 1 ]; }
acked()     { [ "$(jqv "/commands/$1" '.status')" = ACKED ]; }

RULE_ON='.source == "rule" and .value == "on"'
RULE_OFF='.source == "rule" and .value == "off"'

sim_pid=""
agent_pid=""

start_sim() {
    pkill -f 'device_sim\.py$' 2>/dev/null
    python3 tools/device_sim.py >"$SIM_LOG" 2>&1 &
    sim_pid=$!
    retry 10 sim STATUS >/dev/null || { echo "시뮬레이터 기동 실패 ($SIM_LOG)"; exit 1; }
}

start_agent() {
    python3 agent/svagent.py >>"$AGENT_LOG" 2>&1 &
    agent_pid=$!
}

stop_agent() {                      # Ctrl+C 와 같은 신호
    [ -n "$agent_pid" ] || return 0
    kill -INT "$agent_pid" 2>/dev/null
    local i
    for i in $(seq 10); do
        kill -0 "$agent_pid" 2>/dev/null || break
        sleep 1
    done
    kill -KILL "$agent_pid" 2>/dev/null      # 안 죽으면 강제 종료 (무한 대기 방지)
    wait "$agent_pid" 2>/dev/null
    agent_pid=""
}

cleanup() {
    [ -n "$agent_pid" ] && kill "$agent_pid" 2>/dev/null
    [ -n "$sim_pid" ]   && kill "$sim_pid"   2>/dev/null
    return 0
}
trap cleanup EXIT

setup() {
    say "스택 기동"
    : >"$AGENT_LOG"
    pkill -f 'svagent\.py$' 2>/dev/null     # 시나리오 4 를 위해 에이전트는 이 스크립트가 소유한다
    docker compose up -d >"$LOGDIR/compose.log" 2>&1 \
        || { echo "docker compose 실패 ($LOGDIR/compose.log)"; exit 1; }
    retry 90 backend_up || { echo "백엔드가 응답하지 않음"; exit 1; }
    info "백엔드 OK (${ELAPSED}초)"
    start_sim
    info "시뮬레이터 기동 (pid $sim_pid)"
    sim AUTO >/dev/null
}

# 1. 정상 수집
scenario_1() {
    say "1. 정상 수집"
    local started left
    started=$(date +%s)
    start_agent
    info "에이전트 기동 (pid $agent_pid)"

    check "10초 안에 ONLINE" retry 10 is_online
    info "ONLINE 까지 ${ELAPSED}초"

    # 기동 직후에는 아직 샘플이 없다. 기동 시점으로부터 10초를 채우고 나서 본다.
    left=$(( started + 10 - $(date +%s) ))
    if [ "$left" -gt 0 ]; then
        info "샘플이 쌓이도록 ${left}초 대기"
        sleep "$left"
    fi

    local row
    row=$(svctl devices | grep "$ID")
    info "svctl devices: $(echo "$row" | tr -s ' ')"
    check "devices 에 ONLINE 표시"   grep -q 'ONLINE'     <<<"$row"
    check "devices 에 최신 온도 표시" grep -q 'temperature' <<<"$row"

    local rows
    rows=$(svctl history "$ID" --last 5m | grep -c '^[0-9]')
    info "history --last 5m: ${rows}건"
    check "history 에 샘플이 쌓임" [ "$rows" -ge 1 ]
}

# 2. 원격 제어
scenario_2() {
    say "2. 원격 제어"
    local out start elapsed
    start=$(date +%s)
    out=$(svctl led "$ID" on 2>&1)
    elapsed=$(( $(date +%s) - start ))
    info "svctl led on: $(echo "$out" | tr -s ' \n' ' ') (${elapsed}초)"

    check "5초 안에 끝남"      [ "$elapsed" -le 5 ]
    check "PENDING 이 출력됨"  grep -q 'PENDING' <<<"$out"
    check "ACKED 가 출력됨"    grep -q 'ACKED'   <<<"$out"
    check "시뮬레이터 led=1"   retry 5 test "$(sim_led)" = 1
}

# 3. 자동 규칙
scenario_3() {
    say "3. 자동 규칙"
    svctl rule "$ID" --threshold 30 >/dev/null
    info "규칙 threshold=30"

    snapshot "$RULE_ON"
    sim "SET t=35" >/dev/null
    check "15초 안에 rule/on 명령 생성" retry 15 has_new "$RULE_ON"
    info "생성까지 ${ELAPSED}초"

    local id
    id=$(new_ids "$RULE_ON" | head -1)
    info "명령 id=${id:-없음}"
    check "1건만 생성"          [ "$(new_count "$RULE_ON")" -eq 1 ]
    check "ACKED 로 끝남"        retry 10 acked "$id"

    info "35℃ 유지한 채 12초 관찰"
    sleep 12
    check "추가 명령이 생기지 않음" [ "$(new_count "$RULE_ON")" -eq 1 ]

    snapshot "$RULE_OFF"
    sim "SET t=20" >/dev/null
    check "15초 안에 rule/off 명령 생성" retry 15 has_new "$RULE_OFF"
    check "off 도 1건만"                 [ "$(new_count "$RULE_OFF")" -eq 1 ]
    sim AUTO >/dev/null
}

# 4. OFFLINE 감지·복구
scenario_4() {
    say "4. OFFLINE 감지·복구"
    stop_agent
    info "에이전트 종료 (SIGINT)"

    check "45초 안에 OFFLINE" retry 45 is_offline
    info "OFFLINE 까지 ${ELAPSED}초 (기대 30~40초)"

    start_agent
    info "에이전트 재기동 (pid $agent_pid)"
    check "10초 안에 ONLINE 복귀" retry 10 is_online
    info "복귀까지 ${ELAPSED}초"

    local out
    out=$(svctl led "$ID" off 2>&1)
    info "svctl led off: $(echo "$out" | tr -s ' \n' ' ')"
    check "복귀 후 svctl led 정상 동작" grep -q 'ACKED' <<<"$out"
}

# 5. 연결 끊김 내성
scenario_5() {
    say "5. 연결 끊김 내성"
    local before lines_before lines_after
    before=$(latest_seq)
    lines_before=$(grep -c 'connect\|connection error' "$AGENT_LOG")
    info "끊기 직전 seq=$before"

    sim "DROP 15" >/dev/null
    info "15초간 연결 거부"
    sleep 18

    check "에이전트 프로세스 생존"  kill -0 "$agent_pid"
    lines_after=$(grep -c 'connect\|connection error' "$AGENT_LOG")
    info "재연결 관련 로그 ${lines_before} -> ${lines_after}줄"
    check "재연결 시도 로그를 남김" [ "$lines_after" -gt "$lines_before" ]

    check "수집 재개" retry 30 seq_above "$before"
    info "재개까지 ${ELAPSED}초, seq=$(latest_seq)"
}

setup
scenarios=("$@")
[ $# -eq 0 ] && scenarios=(1 2 3 4 5)
for n in "${scenarios[@]}"; do "scenario_$n"; done

say "결과: PASS $PASS / FAIL $FAIL"
[ "$FAIL" -eq 0 ]
