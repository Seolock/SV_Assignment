import logging
import queue
import threading
import time
from pathlib import Path
import yaml
import requests
import serial


CONFIG = yaml.safe_load((Path(__file__).resolve().parent/"config.yaml").read_text(encoding="utf-8"))
TIMEOUT = CONFIG["http_request_timeout_sec"]

logging.basicConfig(level=CONFIG["log_level"], format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger(__name__)

link = None # serial.Serial
link_lock = threading.Lock()
stop = threading.Event()

data_queue = queue.Queue()
ack_queue = queue.Queue()


# 시리얼 스키마
SCHEMA = {
    "HELLO": {"device": str, "fw": str},
    "DATA":  {"seq": int, "t": float, "h": float},
    "ACK":   {"id": str, "led": int},
    "NACK":  {"id": str, "reason": str},
    "ERR":   {"reason": str},
}

# 시리얼 파서
def parser(line):

    parts = line.strip().split(",")
    msg_type = parts[0]

    schema = SCHEMA.get(msg_type)
    if schema is None:
        return None

    kv = {}
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            kv[k.strip()] = v.strip()

    msg = {"type": msg_type}
    for key, type in schema.items():
        if key not in kv:
            return None
        try:
            msg[key] = type(kv[key])
        except ValueError:
            return None

    return msg


# 백엔드 url
def api(path):
    return f"{CONFIG['backend_url']}/api/v1/" + path


# 장치 등록
def post_devices(message):
    url = api("devices")
    payload = { 
        "device_id": message["device"],
        "device_url": CONFIG["device_url"],
        "fw": message["fw"]
    }
    try:
        r = requests.post(url, json=payload, timeout=TIMEOUT)
        r.raise_for_status()
        log.info("device registered: %s", message["device"])
        return True
    except Exception as e:
        log.warning("register failed (%s)",e)
        return False


# 하트비트 전송
def post_heartbeat():
    url = api(f"devices/{CONFIG['device_id']}/heartbeat")
    try:
        r = requests.post(url, timeout=TIMEOUT)
        r.raise_for_status()
        log.info("post_heartbeat ok: %s",CONFIG['device_id'])
        return True
    except Exception as e:
        log.warning("post_heartbeat fail: %s",e)
        return False


# 텔레메트리 전송
def post_telemetry(sample):
    url = api(f"devices/{CONFIG['device_id']}/telemetry")
    payload = {
                "seq": sample["seq"],
                "ts": sample["ts"],
                "temperature": sample["t"],
                "humidity": sample["h"],
            }
    try:
        r = requests.post(url, json=payload, timeout=TIMEOUT)
        r.raise_for_status()
        log.info("post_telemetry ok: %s",CONFIG['device_id'])
        return True
    except Exception as e:
        log.warning("post_telemetry fail: %s",e)
        return False


# 명령 받기
def get_pending():
    url = api(f"devices/{CONFIG['device_id']}/commands/pending")
    try:
        r = requests.get(url, timeout=TIMEOUT)
        r.raise_for_status()
        log.info("get_pending ok: %s",CONFIG['device_id'])
        return r.json()
    except Exception as e:
        log.warning("get_pending fail: %s",e)
        return []


# 명령 결과 전송
def patch_commands(cmd_id, status, reason=None):
    url = api(f"commands/{cmd_id}")
    payload = {"status": "ACKED"}
    if not status:
        payload = {"status": "FAILED", "reason":reason}
    try:
        r = requests.patch(url, json=payload, timeout=TIMEOUT)
        r.raise_for_status()
        log.info("patch_commands ok: %s",cmd_id)
        return True
    except Exception as e:
        log.warning("patch_commands fail: %s",e)
        return False


# 장치 연결
def connect():
    global link
    try:
        with link_lock:
            link = serial.serial_for_url(CONFIG["device_url"], timeout=1)
        log.info("connect ok: %s", CONFIG["device_id"])
        return True
    except Exception as e:
        log.warning("connect fail: %s",e)
        return False


# 장치 연결 해제
def disconnect():
    global link
    with link_lock:
        try:
            link.close()
        except Exception:
            pass
        link = None
    log.info("disconnect ok: %s",CONFIG["device_id"])


# 명령 전송
def write(cmd_id, led):
    line = f"CMD,id={cmd_id},led={led}\n"
    try:
        with link_lock:
            link.write(line.encode())
            log.info("write ok: %s", cmd_id)
            return True
    except Exception as e:
        log.warning("write fail: %s",e)
        return False


# 한 명령 수행
def handle_command(cmd):

    cmd_id = cmd["command_id"]
    led = 1 if cmd["value"]=="on" else 0

    while True:
        try:
            ack_queue.get_nowait()
        except queue.Empty:
            break

    stat = write(cmd_id, led)
    if not stat:
        patch_commands(cmd_id, False, "device_disconnected")
        log.warning("handle_command fail: %s", cmd_id)
        return

    start = time.time()
    while start + CONFIG["device_ack_timeout_sec"] > time.time():
        try:
            message = ack_queue.get(timeout=1)
        except queue.Empty:
            continue
        if message["id"]!=cmd_id:
            continue
        if message["type"]=="ACK":
            patch_commands(cmd_id, True)
            log.info("handle_command ok: %s", cmd_id)
            return
        else:
            patch_commands(cmd_id, False, message["reason"])
            log.info("handle_command ok: %s", cmd_id)
            return
    patch_commands(cmd_id, False, "device_timeout")
    log.warning("handle_command fail: %s", cmd_id)


# 명령 받고 처리 - 쓰레드
def commander():

    interval = CONFIG["command_poll_interval_sec"]

    while not stop.wait(interval):
        for cmd in get_pending():
            handle_command(cmd)
            if stop.is_set():
                break

    log.info("commander stopped")



# 디바이스 시리얼 읽기 - 쓰레드
def reader():

    while not stop.is_set():

        if link is None:
            if not connect():
                stop.wait(2)
            continue

        try:
            line = link.readline().decode().strip()
            if not line:
                continue
        except Exception as e:
            log.warning("connection error: %s", e)
            disconnect()
            continue

        message = parser(line)
        if message is None:
            log.warning("ignored line: %s", line)
            continue
        
        if message["type"]=="DATA":
            message["ts"] = time.time()
            data_queue.put(message)
        elif message["type"]=="ACK" or message["type"]=="NACK":
            ack_queue.put(message)
        elif message["type"]=="HELLO":
            if message["device"]==CONFIG['device_id']:
                post_devices(message)
        else:
            log.warning("device error: %s", message["reason"])

    disconnect()
    log.info("reader stopped")


# 백엔드로 데이터 전송 - 쓰레드
def sender():

    interval = CONFIG["heartbeat_interval_sec"]
    next_heartbeat = 0.0

    while not stop.is_set():

        if time.time() >= next_heartbeat:
            post_heartbeat()
            next_heartbeat = time.time() + interval

        try:
            sample = data_queue.get(timeout=1)
            post_telemetry(sample)
        except Exception:
            continue


    log.info("sender stopped")



# 메인
def main():

    threads = []
    for f in (reader, sender, commander):
        t = threading.Thread(target=f, name=f.__name__, daemon=True)
        threads.append(t)

    for t in threads:
        t.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log.info("shutting down")

    stop.set()
    for t in threads:
        t.join(timeout=5)

    log.info("GOD IS GOOD, ALL THE TIME")


if __name__ == "__main__":
    main()