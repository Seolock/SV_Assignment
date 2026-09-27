import argparse
import json
import os
import sys
import time
import requests


TIMEOUT = 1

# 명령어 파서
def build_parser():
    parser = argparse.ArgumentParser(prog="svctl")
    parser.add_argument("--url", default=os.environ.get("SVCTL_URL","http://localhost:8000"))

    sub = parser.add_subparsers(dest="command", required=True)

    devices = sub.add_parser("devices", help="장치 목록 표로 출력")

    status = sub.add_parser("status", help="장치 상세 출력")
    status.add_argument("id", help="장치 ID")

    history = sub.add_parser("history", help="측정 이력 표로 출력")
    history.add_argument("id", help="장치 ID")
    history.add_argument("--last", default="1h")
    history.add_argument("--limit", type=int, default=50)

    led = sub.add_parser("led", help="명령 생성 및 상태 변화 출력")
    led.add_argument("id", help="장치 ID")
    led.add_argument("led", choices=["on", "off"], help="on|off")

    rule = sub.add_parser("rule", help="규칙 조회 및 설정")
    rule.add_argument("id", help="장치 ID")
    rule.add_argument("--threshold", type=float)

    commands = sub.add_parser("commands", help="명령 이력 출력")
    commands.add_argument("id", help="장치 ID")
    commands.add_argument("--limit", type=int, default=20)

    devices.set_defaults(func=run_devices)
    status.set_defaults(func=run_status)
    history.set_defaults(func=run_history)
    led.set_defaults(func=run_led)
    rule.set_defaults(func=run_rule)
    commands.set_defaults(func=run_commands)

    return parser


# 출력 형식
def show(r):
    print()

    if not r.content:
        print()
        return

    try:
        data = r.json()
    except ValueError:
        print(r.text.strip())
        print()
        return

    def format_value(value):
        if isinstance(value, (dict, list)):
            return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))
        if value is None:
            return "-"
        if isinstance(value, bool):
            return "yes" if value else "no"
        return str(value)

    def print_table(rows):
        if not rows:
            return

        columns = []
        for row in rows:
            for key in row:
                if key not in columns:
                    columns.append(key)

        values = [
            [format_value(row.get(column)) for column in columns]
            for row in rows
        ]
        widths = [len(column) for column in columns]
        for row in values:
            for index, value in enumerate(row):
                widths[index] = max(widths[index], len(value))

        def line(row):
            return " | ".join(value.ljust(widths[index]) for index, value in enumerate(row))

        print(line(columns))
        print("-+-".join("-" * width for width in widths))
        for row in values:
            print(line(row))

    if isinstance(data, list):
        if all(isinstance(item, dict) for item in data):
            print_table(data)
        else:
            for item in data:
                print(f"- {format_value(item)}")
        print()
        return

    if isinstance(data, dict):
        for key, value in data.items():
            if isinstance(value, list) and all(isinstance(item, dict) for item in value):
                print(f"\n{key}:")
                print_table(value)
            else:
                print(f"{key}: {format_value(value)}")
        print()
        return

    print(format_value(data))
    print()


# 장치 리스트
def run_devices(args):
    url = f"{args.url}/api/v1/devices"
    try:
        r = requests.get(url, timeout=TIMEOUT)
        r.raise_for_status()
        show(r)
    except Exception as e:
        print(f"devices 요청 실패: {e}")
        return 1


# 장치 상세
def run_status(args):
    url = f"{args.url}/api/v1/devices/{args.id}"
    try:
        r = requests.get(url, timeout=TIMEOUT)
        r.raise_for_status()
        show(r)
    except Exception as e:
        print(f"status 요청 실패: {e}")
        return 1


# 텔레메트리 리스트
def run_history(args):
    url = f"{args.url}/api/v1/devices/{args.id}/telemetry"
    try:
        params = {"last": args.last, "limit": args.limit}
        r = requests.get(url, params=params, timeout=TIMEOUT)
        r.raise_for_status()
        show(r)
    except Exception as e:
        print(f"history 요청 실패: {e}")
        return 1


# 명령 생성
def run_led(args):
    url = f"{args.url}/api/v1/devices/{args.id}/commands"
    payload = {"type": "SET_LED", "value": args.led, "source": "cli"}
    try:
        r = requests.post(url, json=payload, timeout=TIMEOUT)
        r.raise_for_status()
        show(r)
        command = r.json()
        command_id = command["command_id"]

        url = f"{args.url}/api/v1/commands/{command_id}"
        start = time.time()
        last = "PENDING"
        while start + 15 > time.time():
            r = requests.get(url, timeout=TIMEOUT)
            r.raise_for_status()
            status = r.json()["status"]
            if status != last:
                print(status+"\n")
                last = status
            if status!="PENDING" and status!="SENT":
                return
            time.sleep(1)
        print("TIMEOUT\n")
    except Exception as e:
        print(f"led 요청 실패: {e}")
        return 1


# 규칙 조회,생성
def run_rule(args):
    url = f"{args.url}/api/v1/devices/{args.id}/rule"
    try:
        if args.threshold is not None:
            payload = {"metric": "temperature", "threshold": args.threshold}
            r = requests.put(url, json=payload, timeout=TIMEOUT)
            r.raise_for_status()
        else:
            r = requests.get(url, timeout=TIMEOUT)
            r.raise_for_status()
        show(r)
    except Exception as e:
        print(f"rule 요청 실패: {e}")
        return 1


# 명령 리스트
def run_commands(args):
    url = f"{args.url}/api/v1/devices/{args.id}/commands"
    try:
        params = {"limit": args.limit}
        r = requests.get(url, params=params, timeout=TIMEOUT)
        r.raise_for_status()
        show(r)
    except Exception as e:
        print(f"commands 요청 실패: {e}")
        return 1


# 메인
def main():
    args = build_parser().parse_args()
    try:
        return args.func(args) or 0
    except Exception as e:
        print(f"backend error: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
