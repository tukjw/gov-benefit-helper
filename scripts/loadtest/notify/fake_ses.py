#!/usr/bin/env python3
"""알림 발송 장애 측정(#137)의 가짜 SES. SES API v2 SendEmail(POST /v2/email/outbound-emails)만 받는다.

표준 라이브러리만 쓴다. 부하 생성 VM에서 run.sh가 시나리오마다 새로 띄운다.

- 응답: 성공은 200 {"MessageId": ...}, 오류는 x-amzn-ErrorType 헤더와 {"message": ...} 본문(SES v2와 같은 형식).
- 지연: --latency 파일(한 줄에 ms 하나, 실제 SES 사전 측정의 성공 지연)에서 무작위로 골라 그만큼 늦게 응답한다.
- 속도 제한: 초당 --bucket-rate건이 차는 토큰 버킷(용량 --bucket-capacity). 토큰이 없으면 429 TooManyRequestsException.
  실제 SES에서 잰 값(초당 14건, 용량 약 145건, 2026-11-09)이 기본값이다.
- 장애 주입: 관리 포트(--control-host:--control-port, 기본 127.0.0.1:8026)로 켜고 끈다.
    POST /outage    {"on": true, "status": 500, "code": "InternalFailure"} | {"on": false}
    POST /late      {"every": 100, "seconds": 15}   알림 요청 every번째마다 seconds초 뒤에 200 | {"every": 0}
    POST /hold-next 다음 알림 요청을 접수로 기록한 뒤 /release까지 응답하지 않는다
    POST /release   붙잡은 요청에 200으로 응답한다(앱이 이미 죽었으면 보낼 곳이 없다)
    GET  /stats     받은 수, 접수 수, 첫·마지막 요청 시각(종류별), 붙잡은 수, 장애 상태
- 기록: 요청마다 한 줄(JSONL)을 --log에 쓴다. 유실과 중복은 앱 DB가 아니라 이 기록으로 센다(count.py).
    id, arrived(epoch 초), to, kind(digest | signup | other), result, status, responded, client_gone
    result: accepted(200) | accepted_late(늦게 200) | accepted_held(붙잡힌 채 기록, 응답 전에 앱이 죽을 수 있다)
            | rate_limited(429) | outage(주입한 오류)
  붙잡은 요청은 응답하기 전에 기록한다. 나머지는 응답을 쓴 뒤 기록한다.
"""
import argparse
import collections
import json
import random
import signal
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEND_PATH = "/v2/email/outbound-emails"
OUTAGE_MESSAGES = {
    "InternalFailure": "The request processing has failed because of an unknown error, exception or failure.",
    "ServiceUnavailable": "Service is unavailable.",
    "TooManyRequestsException": "Maximum sending rate exceeded.",
}


class TokenBucket:
    def __init__(self, capacity, rate):
        self.capacity = float(capacity)
        self.rate = float(rate)
        self.tokens = float(capacity)
        self.last = time.monotonic()

    def take(self):
        now = time.monotonic()
        self.tokens = min(self.capacity, self.tokens + (now - self.last) * self.rate)
        self.last = now
        if self.tokens >= 1.0:
            self.tokens -= 1.0
            return True
        return False


class State:
    """요청 처리와 관리 경로가 함께 쓰는 상태. 모든 변경은 lock 안에서 한다."""

    def __init__(self, args):
        self.lock = threading.Lock()
        self.bucket = TokenBucket(args.bucket_capacity, args.bucket_rate)
        self.random = random.Random(args.seed)
        self.latencies = load_latencies(args.latency)
        self.log = open(args.log, "a", encoding="utf-8")
        self.seq = 0
        self.received = collections.Counter()
        self.accepted = collections.Counter()
        self.first_arrived = {}
        self.last_arrived = {}
        self.outage = None
        self.late_every = 0
        self.late_seconds = 0.0
        self.late_counter = 0
        self.hold_armed = False
        self.held = []

    def latency(self):
        with self.lock:
            return self.random.choice(self.latencies) / 1000.0 if self.latencies else 0.0

    def write(self, record):
        line = json.dumps(record, ensure_ascii=False) + "\n"
        with self.lock:
            if not self.log.closed:
                self.log.write(line)
                self.log.flush()

    def stats(self):
        with self.lock:
            return {
                "received": dict(self.received),
                "accepted": dict(self.accepted),
                "firstArrived": dict(self.first_arrived),
                "lastArrived": dict(self.last_arrived),
                "held": len(self.held),
                "holdArmed": self.hold_armed,
                "outage": self.outage,
                "late": {"every": self.late_every, "seconds": self.late_seconds},
                "now": time.time(),
            }

    def release_all(self):
        with self.lock:
            held, self.held = self.held, []
            self.hold_armed = False
        for event in held:
            event.set()
        return len(held)


def load_latencies(path):
    if not path:
        return []
    with open(path, encoding="utf-8") as f:
        values = [float(line) for line in f if line.strip() and not line.startswith("#")]
    if not values:
        raise SystemExit(f"지연 표본이 비어 있습니다: {path}")
    return values


def kind_of(subject):
    if "새 혜택" in subject:
        return "digest"
    if "인증번호" in subject:
        return "signup"
    return "other"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "fake-ses"
    # 응답의 헤더와 본문을 한 번에, 곧바로 보낸다. 따로 쓰고 Nagle을 켜 두면 본문이 클라이언트의 지연 ACK(리눅스 약 40ms)를
    # 기다렸다 나가, 요청마다 그만큼 늦어진다(2026-11-10 첫 측정의 도구 오차).
    disable_nagle_algorithm = True
    wbufsize = 64 * 1024

    def handle_expect_100(self):
        ok = super().handle_expect_100()
        self.wfile.flush()
        return ok

    def log_message(self, fmt, *args):
        pass

    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def send_json(self, status, body, error_type=None):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("x-amzn-RequestId", str(uuid.uuid4()))
        if error_type:
            self.send_header("x-amzn-ErrorType", error_type)
        self.end_headers()
        self.wfile.write(data)
        self.wfile.flush()


class SesHandler(Handler):
    def do_POST(self):
        arrived = time.time()
        body = self.read_body()
        if self.path != SEND_PATH:
            self.send_json(404, {"message": "not found"}, "NotFoundException")
            return
        try:
            request = json.loads(body)
            to = request["Destination"]["ToAddresses"][0]
            subject = request.get("Content", {}).get("Simple", {}).get("Subject", {}).get("Data", "")
        except (ValueError, KeyError, IndexError, AttributeError):
            self.send_json(400, {"message": "bad request"}, "BadRequestException")
            return
        state = self.server.state
        kind = kind_of(subject)
        hold = None
        late = 0.0
        with state.lock:
            state.seq += 1
            record = {"id": state.seq, "arrived": arrived, "to": to, "kind": kind}
            state.received[kind] += 1
            state.first_arrived.setdefault(kind, arrived)
            state.last_arrived[kind] = arrived
            if not state.bucket.take():
                record["result"] = "rate_limited"
                status, error = 429, ("TooManyRequestsException", OUTAGE_MESSAGES["TooManyRequestsException"])
            elif state.outage:
                record["result"] = "outage"
                status, error = state.outage["status"], (state.outage["code"], state.outage["message"])
            elif kind == "digest" and state.hold_armed:
                state.hold_armed = False
                hold = threading.Event()
                state.held.append(hold)
                record["result"] = "accepted_held"
                status, error = 200, None
            else:
                if kind == "digest" and state.late_every > 0:
                    state.late_counter += 1
                    if state.late_counter % state.late_every == 0:
                        late = state.late_seconds
                record["result"] = "accepted_late" if late else "accepted"
                status, error = 200, None
            record["status"] = status
            if status == 200:
                state.accepted[kind] += 1
        if hold is not None:
            record["responded"] = None
            state.write(record)
            hold.wait()
        else:
            time.sleep(late or state.latency())
        try:
            if error:
                self.send_json(status, {"message": error[1]}, error[0])
            else:
                self.send_json(200, {"MessageId": str(uuid.uuid4())})
            responded = time.time()
            client_gone = False
        except (BrokenPipeError, ConnectionResetError):
            responded = None
            client_gone = True
            self.close_connection = True
        if hold is None:
            record["responded"] = responded
            record["client_gone"] = client_gone
            state.write(record)


class ControlHandler(Handler):
    def do_GET(self):
        if self.path == "/stats":
            self.send_json(200, self.server.state.stats())
        else:
            self.send_json(404, {"message": "not found"})

    def do_POST(self):
        state = self.server.state
        try:
            body = json.loads(self.read_body() or b"{}")
        except ValueError:
            self.send_json(400, {"message": "JSON 본문이 아닙니다."})
            return
        if self.path == "/outage":
            with state.lock:
                if body.get("on"):
                    code = body.get("code", "InternalFailure")
                    state.outage = {"status": int(body.get("status", 500)), "code": code,
                                    "message": OUTAGE_MESSAGES.get(code, code)}
                else:
                    state.outage = None
        elif self.path == "/late":
            with state.lock:
                state.late_every = int(body.get("every", 0))
                state.late_seconds = float(body.get("seconds", 0))
                state.late_counter = 0
        elif self.path == "/hold-next":
            with state.lock:
                state.hold_armed = True
        elif self.path == "/release":
            self.send_json(200, {"released": state.release_all()})
            return
        else:
            self.send_json(404, {"message": "not found"})
            return
        self.send_json(200, state.stats())


def serve(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return thread


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8025)
    p.add_argument("--control-host", default="127.0.0.1")
    p.add_argument("--control-port", type=int, default=8026)
    p.add_argument("--log", required=True)
    p.add_argument("--latency", help="성공 지연 표본(ms, 한 줄에 하나). 없으면 지연 없이 응답한다")
    p.add_argument("--seed", type=int, default=137)
    p.add_argument("--bucket-capacity", type=float, default=145)
    p.add_argument("--bucket-rate", type=float, default=14)
    args = p.parse_args()

    state = State(args)
    ses = ThreadingHTTPServer((args.host, args.port), SesHandler)
    control = ThreadingHTTPServer((args.control_host, args.control_port), ControlHandler)
    for server in (ses, control):
        server.daemon_threads = True
        server.state = state
    serve(ses)
    serve(control)
    print(f"가짜 SES http://{args.host}:{args.port}, 관리 http://{args.control_host}:{args.control_port}, "
          f"버킷 {args.bucket_capacity:g}/{args.bucket_rate:g}/s, 지연 표본 {len(state.latencies)}개, 기록 {args.log}",
          file=sys.stderr, flush=True)

    stop = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
    while not stop.wait(1):
        pass
    state.release_all()
    ses.shutdown()
    control.shutdown()
    with state.lock:
        state.log.close()


if __name__ == "__main__":
    main()
