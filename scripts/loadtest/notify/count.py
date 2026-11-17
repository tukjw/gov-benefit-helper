#!/usr/bin/env python3
"""알림 발송 장애 측정(#137) 결과 디렉터리 하나를 센다. run.sh가 시나리오 끝에 부른다. 다시 셀 때도 같은 명령이다.

사용법: count.py <결과 디렉터리>
읽는 파일: meta.json, fake-ses.jsonl(.gz), digests.tsv, injections.tsv, notify-off.tsv(있으면), k6-summary.json(있으면),
app-log.txt(.gz)
쓰는 파일: counts.json. 사람이 읽는 요약은 표준 출력(run.sh가 counts.txt로 남긴다).

센 값(유실과 중복은 앱 DB가 아니라 가짜 SES 기록으로 센다. 수신 주소로 묶음과 맞춘다)
- 접수: 가짜 SES가 200으로 받은 알림 요청. accepted, accepted_late(앱의 응답 제한보다 늦게 200),
  accepted_held(접수로 기록하고 붙잡은 채 앱을 죽임)
- 보내야 할 묶음: 취소(CANCELED)되지 않은 합성 묶음
- 못 받은 묶음: 보내야 할 묶음 중 접수가 0번. 끝난 상태별로 나눈다. FAILED는 최종 실패, PENDING은 끝나지 않은 묶음,
  ACCEPTED는 앱은 접수로 기록했는데 가짜 SES는 받은 적이 없는 묶음(조용한 유실)
- 중복 접수: 같은 수신 주소의 두 번째 이후 접수
- 재개: 강제 종료·배포 명령 뒤 알림 요청이 3초 이상 끊긴 구간. 끊기기 직전 요청과 다시 시작한 첫 요청의 시각
- 멈춤 요약 로그: 명령부터 명령이 끝날 때까지 앱 로그에 남은 interrupted=true 발송 요약. 이전 앱이 종료 신호를 받고
  발송을 멈췄다는 근거다(#141). 없으면 null
- 알림을 끈 뒤 나간 알림: 끈 사용자의 접수 중 UPDATE를 마친 시각(after)보다 늦게 도착한 것(확실),
  UPDATE를 보내기 전(before)과 마친 시각 사이에 도착한 것(커밋 시각에 따라 다름)
- 계정 메일 실패: k6가 204를 받은 인증번호 요청 수 - 가짜 SES가 접수한 계정 메일 수
"""
import gzip
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ACCEPTED = {"accepted", "accepted_late", "accepted_held"}
SUMMARY = re.compile(r"새 혜택 알림 발송 accepted=(\d+) retrying=(\d+) failed=(\d+) skipped=(\d+) "
                     r"interrupted=(true|false) elapsedMs=(\d+)")
SIGNUP_FAILURE = "가입 인증번호 메일을 보내지 못했습니다"
STARTED = re.compile(r"Started \S+ in ([\d.]+) seconds")
RESUME_GAP_S = 3.0
LOG_TIME = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{2}:\d{2})\s")


def open_text(path):
    gz = path.with_name(path.name + ".gz")
    if path.exists():
        return open(path, encoding="utf-8")
    if gz.exists():
        return gzip.open(gz, "rt", encoding="utf-8")
    return None


def read_tsv(path):
    if not path.exists():
        return []
    with open(path, encoding="utf-8") as f:
        lines = [line.rstrip("\n").split("\t") for line in f if line.strip()]
    if lines and lines[0][0] in ("round", "email"):
        header = lines[0]
        return [dict(zip(header, row)) for row in lines[1:]]
    return lines


def pct(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, math.ceil(len(ordered) * p / 100) - 1))]


def rnd(value, digits=1):
    return None if value is None else round(value, digits)


def as_float(text):
    return float(text) if text not in (None, "") else None


def resume_after(arrivals, t_cmd, t_done):
    """명령 뒤 처음으로 RESUME_GAP_S초 이상 끊긴 구간. 다시 시작한 요청은 명령이 끝난 뒤(이전 프로세스가 끝난 뒤)여야 한다.
    UP 뒤로 두지 않는다. 헬스 체크는 1초마다라서 발송이 UP을 보기 직전에 다시 시작하기도 한다."""
    start = t_cmd - 2
    after = t_done if t_done is not None else t_cmd
    previous = None
    for arrived in arrivals:
        if arrived < start:
            continue
        if previous is not None and arrived - previous >= RESUME_GAP_S and arrived >= after:
            return previous, arrived
        previous = arrived
    return previous, None


def main():
    if len(sys.argv) != 2:
        sys.exit("사용법: count.py <결과 디렉터리>")
    out = Path(sys.argv[1])
    meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    scenario = meta["scenario"]

    records = []
    with open_text(out / "fake-ses.jsonl") as f:
        for line in f:
            if line.strip():
                records.append(json.loads(line))
    digest_records = sorted((r for r in records if r["kind"] == "digest"), key=lambda r: r["arrived"])
    signup_records = [r for r in records if r["kind"] == "signup"]

    accepted_by_to = defaultdict(list)
    for r in digest_records:
        if r["result"] in ACCEPTED:
            accepted_by_to[r["to"]].append(r["arrived"])

    digests = [{"email": row[0], "status": row[1], "attempts": int(row[2]), "lastError": row[3]}
               for row in read_tsv(out / "digests.tsv")]
    status = Counter(d["status"] for d in digests)
    to_send = [d for d in digests if d["status"] != "CANCELED"]
    missing = [d for d in to_send if not accepted_by_to.get(d["email"])]
    missing_by_status = Counter(d["status"] for d in missing)
    duplicate_counts = {to: len(times) - 1 for to, times in accepted_by_to.items() if len(times) > 1}
    unknown = sorted(set(accepted_by_to) - {d["email"] for d in digests})

    arrivals = [r["arrived"] for r in digest_records]
    accepted_times = sorted(t for times in accepted_by_to.values() for t in times)
    first = arrivals[0] if arrivals else None
    last_accept = accepted_times[-1] if accepted_times else None
    first_accept_delay = [min(times) - first for times in accepted_by_to.values()] if first else []
    total_s = (last_accept - first) if first and last_accept else None
    accepted_first = len(accepted_by_to)

    # 응답을 보낸 뒤 다음 요청이 올 때까지(앱이 기록하고 다음 묶음을 준비하는 시간)와 요청 간격
    after_response = [(b["arrived"] - a["responded"]) * 1000
                      for a, b in zip(digest_records, digest_records[1:]) if a.get("responded")]
    intervals = [(b - a) * 1000 for a, b in zip(arrivals, arrivals[1:])]

    counts = {
        "scenario": scenario,
        "users": meta["users"],
        "endReason": meta["endReason"],
        "digests": {"total": len(digests), "byStatus": dict(status), "toSend": len(to_send)},
        "requests": {
            "digest": len(digest_records),
            "byResult": dict(Counter(r["result"] for r in digest_records)),
            "clientGone": sum(1 for r in digest_records if r.get("client_gone")),
        },
        "lost": {"total": len(missing), "byStatus": dict(missing_by_status),
                 "silent": missing_by_status.get("ACCEPTED", 0)},
        "duplicates": {"extraAcceptances": sum(duplicate_counts.values()), "addresses": len(duplicate_counts)},
        "unfinished": status.get("PENDING", 0),
        "finalFailed": status.get("FAILED", 0),
        "acceptedUnknownAddresses": len(unknown),
        "timing": {
            "firstRequest": first,
            "lastAcceptance": last_accept,
            "totalSeconds": rnd(total_s),
            "acceptedPerSecond": rnd(accepted_first / total_s, 2) if total_s else None,
            "responseToNextRequestMs": {"p50": rnd(pct(after_response, 50)), "p90": rnd(pct(after_response, 90))},
            "requestIntervalMs": {"p50": rnd(pct(intervals, 50)), "p90": rnd(pct(intervals, 90))},
            "firstAcceptanceDelaySeconds": {"p50": rnd(pct(first_accept_delay, 50)),
                                            "p90": rnd(pct(first_accept_delay, 90)),
                                            "max": rnd(max(first_accept_delay) if first_accept_delay else None)},
        },
    }

    # 강제 종료·배포 회차
    rounds = []
    injections = [row for row in read_tsv(out / "injections.tsv") if isinstance(row, dict)]
    restarts = sorted((row for row in injections if row["kind"] in ("kill", "hold", "deploy")),
                      key=lambda row: float(row["t_cmd"]))
    duplicate_times = sorted(t for times in accepted_by_to.values() for t in sorted(times)[1:])
    round_windows = []
    for i, row in enumerate(restarts):
        t_cmd = float(row["t_cmd"])
        t_up = as_float(row.get("t_up"))
        next_cmd = float(restarts[i + 1]["t_cmd"]) if i + 1 < len(restarts) else math.inf
        round_windows.append((t_cmd, as_float(row.get("t_done")) or next_cmd))
        last_before, resumed = resume_after(arrivals, t_cmd, as_float(row.get("t_done")))
        rounds.append({
            "round": row["round"], "kind": row["kind"], "detail": row.get("detail", ""),
            "lastBeforeStopAfterCmdSeconds": rnd(last_before - t_cmd, 2) if last_before else None,
            "cmdToUpSeconds": rnd(t_up - t_cmd, 2) if t_up else None,
            "upToResumeSeconds": rnd(resumed - t_up, 2) if resumed and t_up else None,
            "cmdToResumeSeconds": rnd(resumed - t_cmd, 2) if resumed else None,
            "gapSeconds": rnd(resumed - last_before, 2) if resumed and last_before else None,
            "duplicates": sum(1 for t in duplicate_times if t_cmd <= t < next_cmd),
            "stopSummaryAfterCmdSeconds": None,
        })
    if rounds:
        counts["rounds"] = rounds
        counts["duplicatesBeforeFirstRound"] = sum(1 for t in duplicate_times if t < float(restarts[0]["t_cmd"]))

    # 알림 끄기
    off_rows = [row for row in read_tsv(out / "notify-off.tsv") if isinstance(row, dict)]
    if off_rows:
        definite = possible = already = 0
        for row in off_rows:
            before, after = float(row["before"]), float(row["after"])
            times = accepted_by_to.get(row["email"], [])
            definite += sum(1 for t in times if t > after)
            possible += sum(1 for t in times if before < t <= after)
            already += 1 if any(t <= before for t in times) else 0
        counts["notifyOff"] = {"users": len(off_rows), "sentAfterOff": definite, "sentDuringOffUpdate": possible,
                               "alreadySentBeforeOff": already, "canceled": status.get("CANCELED", 0)}

    # 계정 메일
    summary_path = out / "k6-summary.json"
    if summary_path.exists() or signup_records:
        k6 = json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
        metrics = k6.get("metrics", {})
        sent = metrics.get("http_reqs", {}).get("values", {}).get("count")
        failed_http = metrics.get("http_req_failed", {}).get("values", {}).get("passes")
        accepted_signup = {r["to"] for r in signup_records if r["result"] in ACCEPTED}
        ok = sent - failed_http if sent is not None and failed_http is not None else None
        counts["accountMail"] = {
            "k6Requests": sent, "k6Non2xx": failed_http,
            "sesRequests": len(signup_records),
            "sesByResult": dict(Counter(r["result"] for r in signup_records)),
            "accepted": len(accepted_signup),
            "failed": ok - len(accepted_signup) if ok is not None else None,
        }

    # 앱 로그: 발송 요약(한 실행이 스케줄러 스레드를 붙잡은 시간), 계정 메일 실패, 앱 시작
    log = open_text(out / "app-log.txt")
    if log:
        elapsed, interrupted, signup_failures, started, stop_summaries = [], 0, 0, [], []
        with log:
            for line in log:
                m = SUMMARY.search(line)
                if m:
                    elapsed.append(int(m.group(6)))
                    if m.group(5) == "true":
                        interrupted += 1
                        t = LOG_TIME.match(line)
                        if t:
                            stop_summaries.append(datetime.fromisoformat(t.group(1)).timestamp())
                elif SIGNUP_FAILURE in line:
                    signup_failures += 1
                else:
                    s = STARTED.search(line)
                    if s:
                        started.append(float(s.group(1)))
        counts["appLog"] = {"sendRuns": len(elapsed), "sendRunMaxMs": max(elapsed) if elapsed else None,
                            "sendRunInterrupted": interrupted, "signupMailFailures": signup_failures,
                            "appStarts": len(started), "appStartSeconds": started}
        for r, (t_cmd, t_end) in zip(rounds, round_windows):
            stops = [t for t in stop_summaries if t_cmd <= t <= t_end]
            r["stopSummaryAfterCmdSeconds"] = rnd(stops[0] - t_cmd, 2) if stops else None

    (out / "counts.json").write_text(json.dumps(counts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print_summary(counts)


def print_summary(c):
    t = c["timing"]
    print(f"[{c['scenario']}] 사용자 {c['users']}명, 끝: {c['endReason']}")
    print(f"  묶음 상태: {c['digests']['byStatus']} (보내야 할 묶음 {c['digests']['toSend']})")
    print(f"  알림 요청 {c['requests']['digest']}건: {c['requests']['byResult']}")
    print(f"  못 받은 묶음 {c['lost']['total']} (상태별 {c['lost']['byStatus']}, 조용한 유실 {c['lost']['silent']}), "
          f"끝나지 않은 묶음 {c['unfinished']}, 최종 실패 {c['finalFailed']}")
    print(f"  중복 접수 {c['duplicates']['extraAcceptances']}건(주소 {c['duplicates']['addresses']}개)")
    print(f"  총 소요 {t['totalSeconds']}초(첫 요청 → 마지막 접수), 초당 접수 {t['acceptedPerSecond']}, "
          f"첫 접수까지 p50 {t['firstAcceptanceDelaySeconds']['p50']}초, 최대 {t['firstAcceptanceDelaySeconds']['max']}초")
    print(f"  요청 간격 p50 {t['requestIntervalMs']['p50']}ms(p90 {t['requestIntervalMs']['p90']}ms), "
          f"응답 → 다음 요청 p50 {t['responseToNextRequestMs']['p50']}ms(p90 {t['responseToNextRequestMs']['p90']}ms)")
    for r in c.get("rounds", []):
        stop = r.get("stopSummaryAfterCmdSeconds")
        print(f"  {r['round']}회차 {r['kind']}: UP까지 {r['cmdToUpSeconds']}초, 재개까지 {r['cmdToResumeSeconds']}초"
              f"(UP 뒤 {r['upToResumeSeconds']}초), 끊긴 시간 {r['gapSeconds']}초, 명령 뒤 마지막 요청 "
              f"{r['lastBeforeStopAfterCmdSeconds']}초, 멈춤 요약 로그 {'없음' if stop is None else f'{stop}초'}, "
              f"중복 {r['duplicates']}")
    if "notifyOff" in c:
        n = c["notifyOff"]
        print(f"  알림 끄기 {n['users']}명: 끈 뒤 나간 알림 {n['sentAfterOff']}(UPDATE 중 {n['sentDuringOffUpdate']}), "
              f"끄기 전에 이미 나감 {n['alreadySentBeforeOff']}, 취소 {n['canceled']}")
    if "accountMail" in c:
        a = c["accountMail"]
        print(f"  계정 메일: k6 요청 {a['k6Requests']}(2xx 아님 {a['k6Non2xx']}), 가짜 SES {a['sesByResult']}, "
              f"접수 {a['accepted']}, 실패 {a['failed']}")
    if "appLog" in c:
        g = c["appLog"]
        print(f"  앱 로그: 발송 실행 {g['sendRuns']}번, 한 실행 최대 {g['sendRunMaxMs']}ms, 중단 {g['sendRunInterrupted']}, "
              f"계정 메일 실패 로그 {g['signupMailFailures']}, 앱 시작 {g['appStarts']}번")


if __name__ == "__main__":
    main()
