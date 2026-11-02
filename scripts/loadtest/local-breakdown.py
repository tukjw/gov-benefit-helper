#!/usr/bin/env python3
"""로컬 측정 구간 하나의 CPU·할당을 나눠 본다. local-profile.sh가 부른다.

사용법: python3 -I scripts/loadtest/local-breakdown.py <기록.jfr> <구간 앞 스레드 덤프> <구간 뒤 스레드 덤프> --requests <요청 수>

- 스레드 묶음별 CPU: jcmd Thread.print의 스레드별 cpu= 값이 구간 동안 늘어난 양이다. 요청 처리, GC, JIT를 나눈다.
- 요청 스레드 CPU의 단계별 몫: JFR CPU 샘플(jdk.ExecutionSample)의 스택으로 단계를 정한다. 먼저 스택 어딘가에 TLS 프레임이
  있으면 DB 연결의 암호 처리로(HTTP는 TLS를 쓰지 않는다), 지원조건 원문 파싱 메서드가 있으면 파싱으로 본다. 그 밖에는 스택을
  맨 안쪽부터 올라가며 처음 만난 프레임으로 정한다. JDK·Jackson·Hikari 프레임은 누가 불렀는지 봐야 하므로 건너뛴다.
- 할당: 요청 스레드의 누적 할당량(jdk.ThreadAllocationStatistics)이 늘어난 양의 합이다. 할당 샘플은 초당 개수가 제한돼
  요청 사이에 쌓인 양이 다음 요청의 첫 할당에 몰려 잡히므로, 단계별로 나누지 않는다.
"""
import argparse
import json
import re
import subprocess
from collections import Counter

# (스택 어딘가에 이 접두사의 프레임이 있으면, 단계). RULES보다 먼저 본다.
CONTAINS = (
    (("sun.security.ssl.", "com.sun.crypto.provider."), "DB 연결 TLS(암호화·복호화)"),
    (("kr.govbenefit.match.service.MatchService.parse(", "kr.govbenefit.match.domain.SupportConditions.parse("),
     "지원조건 원문 파싱(Jackson)"),
)
# 누가 불렀는지 봐야 하는 프레임. 이 접두사로 시작하면 건너뛴다.
TRANSPARENT = ("java.", "jdk.", "sun.", "tools.jackson.", "com.fasterxml.", "com.zaxxer.")
# (프레임 접두사, 단계). 위에서부터 맞춰 본다.
RULES = (
    ("com.mysql.cj.", "JDBC 드라이버(결과 읽기·문자열 디코딩)"),
    ("org.hibernate.", "Hibernate(엔티티 적재·영속성 컨텍스트)"),
    ("kr.govbenefit.region.domain.Regions.", "운영 기관 지역 찾기(Regions)"),
    ("kr.govbenefit.match.domain.", "판정(ConditionMatcher 등)"),
    ("kr.govbenefit.region.domain.", "판정(ConditionMatcher 등)"),
    ("kr.govbenefit.match.dto.", "목록 항목 만들기"),
    ("kr.govbenefit.benefit.dto.", "목록 항목 만들기"),
    ("kr.govbenefit.common.api.ApiTimes", "목록 항목 만들기"),
    ("kr.govbenefit.", "그 밖의 앱 코드"),
    ("org.springframework.", "스프링·톰캣(HTTP, 요청·응답 JSON)"),
    ("org.apache.", "스프링·톰캣(HTTP, 요청·응답 JSON)"),
)
OTHER = "그 밖"
REQUEST_THREAD = "http-nio-"
# (스레드 이름 접두사, 묶음). 맞는 것이 없으면 그 밖이다.
THREAD_GROUPS = (
    (REQUEST_THREAD, "요청 처리(톰캣 작업 스레드)"),
    ("GC Thread#", "GC"), ("G1 ", "GC"), ("VM Thread", "GC"),
    ("C1 CompilerThread", "JIT 컴파일"), ("C2 CompilerThread", "JIT 컴파일"),
)
TIME_UNITS = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}


def classify(frames):
    for prefixes, category in CONTAINS:
        if any(frame.startswith(prefixes) for frame in frames):
            return category
    for frame in frames:
        if frame.startswith(TRANSPARENT):
            continue
        for prefix, category in RULES:
            if frame.startswith(prefix):
                return category
        return OTHER
    return OTHER


def thread_cpu_ms(path):
    """스레드 덤프에서 스레드 이름별 누적 CPU 시간(ms)을 읽는다."""
    cpu = {}
    for line in open(path, encoding="utf-8"):
        match = re.match(r'^"(.+?)" .*\bcpu=([\d.]+)ms', line)
        if match:
            cpu[match.group(1)] = float(match.group(2))
    return cpu


def thread_groups(before_path, after_path):
    before, after = thread_cpu_ms(before_path), thread_cpu_ms(after_path)
    groups = Counter()
    for name, value in after.items():
        group = next((g for prefix, g in THREAD_GROUPS if name.startswith(prefix)), OTHER)
        groups[group] += value - before.get(name, 0.0)
    return groups


def read_samples(path):
    """jfr print 출력에서 (이벤트 종류, 필드, 프레임 목록)을 차례로 돌려준다."""
    command = ["jfr", "print", "--stack-depth", "64", "--events", "jdk.ExecutionSample,jdk.GarbageCollection", path]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, text=True, encoding="utf-8")
    kind, fields, frames, in_stack = None, {}, [], False
    for raw in process.stdout:
        line = raw.strip()
        start = re.match(r"^(jdk\.\w+) \{$", line)
        if start:
            kind, fields, frames, in_stack = start.group(1), {}, [], False
        elif kind is None:
            continue
        elif line == "stackTrace = [":
            in_stack = True
        elif in_stack and line == "]":
            in_stack = False
        elif in_stack:
            if line != "...":
                frames.append(line)
        elif line == "}":
            yield kind, fields, frames
            kind = None
        elif " = " in line:
            key, value = line.split(" = ", 1)
            fields[key] = value
    if process.wait() != 0:
        raise SystemExit(f"jfr print 실패: {path}")


def request_thread_allocation(path):
    """요청 스레드마다 누적 할당량의 처음과 마지막 값 차이를 더한다(바이트)."""
    output = subprocess.run(["jfr", "print", "--json", "--events", "jdk.ThreadAllocationStatistics", path],
                            capture_output=True, text=True, encoding="utf-8", check=True).stdout
    first, last = {}, {}
    for event in json.loads(output)["recording"]["events"]:
        values = event["values"]
        thread = (values.get("thread") or {}).get("javaName") or ""
        if thread.startswith(REQUEST_THREAD):
            first.setdefault(thread, values["allocated"])
            last[thread] = values["allocated"]
    return sum(last[thread] - first[thread] for thread in last)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("recording")
    parser.add_argument("threads_before")
    parser.add_argument("threads_after")
    parser.add_argument("--requests", type=int, required=True)
    args = parser.parse_args()
    requests = args.requests

    samples = Counter()
    gc_count, gc_pause_ms = 0, 0.0
    for kind, fields, frames in read_samples(args.recording):
        if kind == "jdk.GarbageCollection":
            gc_count += 1
            number, unit = fields["sumOfPauses"].split()
            gc_pause_ms += float(number.replace(",", "")) * TIME_UNITS[unit]
        elif fields.get("sampledThread", "").startswith(f'"{REQUEST_THREAD}'):
            samples[classify(frames)] += 1

    groups = thread_groups(args.threads_before, args.threads_after)
    total_cpu = sum(groups.values())
    print(f"요청 {requests}건. 스레드 묶음별 CPU(스레드 덤프의 cpu= 증가량)")
    print()
    print("| 묶음 | CPU(초) | 요청 1건당(ms) | 비율 |")
    print("| --- | --- | --- | --- |")
    for group, ms in groups.most_common():
        print(f"| {group} | {ms / 1000:.1f} | {ms / requests:.0f} | {100 * ms / max(total_cpu, 1):.0f}% |")
    print(f"| 합계 | {total_cpu / 1000:.1f} | {total_cpu / requests:.0f} | 100% |")
    print()

    request_ms = groups["요청 처리(톰캣 작업 스레드)"] / requests
    total_samples = sum(samples.values())
    print(f"요청 스레드 CPU의 단계별 몫(JFR CPU 샘플 {total_samples}개). 요청 1건당 ms는 요청 처리 CPU({request_ms:.0f}ms)를 비율로 나눈 값")
    print()
    print("| 단계 | 샘플 비율 | 요청 1건당(ms) |")
    print("| --- | --- | --- |")
    for category, count in samples.most_common():
        share = count / max(total_samples, 1)
        print(f"| {category} | {100 * share:.1f}% | {share * request_ms:.0f} |")
    print()

    allocated = request_thread_allocation(args.recording)
    print(f"요청 스레드 할당 {allocated / 1024 ** 2:,.0f}MB(요청 1건당 {allocated / requests / 1024 ** 2:.1f}MB). "
          f"GC {gc_count}회, 정지 합계 {gc_pause_ms:,.0f}ms(요청 1건당 {gc_pause_ms / requests:.1f}ms).")


if __name__ == "__main__":
    main()
