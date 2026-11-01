"""부하 테스트 입력 데이터를 만든다(#96, 결정 0037). run.sh가 부하 VM에서 실행한다.

    python3 -I prepare.py --base-url http://10.10.0.11:8080 --out <디렉터리>/inputs.json

검색 API의 무작위 쪽에서 서비스 ID를, 프로필 선택지 API로 비회원 조건 비교에 보낼 프로필을 만든다.
표준 라이브러리만 쓴다. 같은 --seed와 같은 데이터면 같은 입력이 나온다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import random
import sys
import urllib.request
from pathlib import Path

PAGE_SIZE = 100
TIMEOUT_SECONDS = 30
KST = dt.timezone(dt.timedelta(hours=9))


def get_json(base_url: str, path: str) -> dict:
    with urllib.request.urlopen(base_url + path, timeout=TIMEOUT_SECONDS) as response:
        return json.load(response)


def sample_service_ids(base_url: str, count: int, rng: random.Random) -> tuple[list[str], int]:
    """무작위 쪽(100건씩)에서 서비스 ID를 count개 모은다. 검색은 이름순이라 앞쪽만 쓰면 이름이 치우친다."""
    total = get_json(base_url, "/api/benefits?size=1")["totalCount"]
    pages = list(range(1, math.ceil(total / PAGE_SIZE) + 1))
    rng.shuffle(pages)
    ids: list[str] = []
    for page in pages:
        items = get_json(base_url, f"/api/benefits?size={PAGE_SIZE}&page={page}")["items"]
        ids.extend(item["serviceId"] for item in items)
        if len(ids) >= count:
            break
    return ids[:count], total


def attributes(options: list[dict], rng: random.Random) -> list[str] | None:
    """미입력, 해당 없음, 또는 값 1~2개. NONE은 다른 값과 함께 보내지 않는다(결정 0022)."""
    roll = rng.random()
    if roll < 0.3:
        return None
    if roll < 0.5:
        return ["NONE"]
    values = [option["value"] for option in options if option["value"] != "NONE"]
    return rng.sample(values, rng.randint(1, 2))


def random_profile(options: dict, rng: random.Random) -> dict:
    """비회원이 입력할 법한 프로필. 항목마다 일부는 비워 둔다(모든 항목이 선택이다)."""
    profile: dict = {}
    if rng.random() < 0.8:
        birth = dt.date(1950, 1, 1) + dt.timedelta(days=rng.randint(0, (dt.date(2008, 12, 31) - dt.date(1950, 1, 1)).days))
        profile["birthDate"] = birth.isoformat()
    if rng.random() < 0.8:
        profile["gender"] = rng.choice(["MALE", "FEMALE"])
    if rng.random() < 0.8:
        sido = rng.choice(options["sidos"])
        profile["sidoCode"] = sido["code"]
        if sido["sigungus"] and rng.random() < 0.7:
            profile["sigunguCode"] = rng.choice(sido["sigungus"])["code"]
    profile["lifeAttributes"] = attributes(options["lifeAttributes"], rng)
    profile["householdAttributes"] = attributes(options["householdAttributes"], rng)
    return profile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    # 기본은 운영 app VM의 내부 브리지 주소다. BASE_URL 환경 변수는 스크립트를 로컬에서 확인할 때 쓴다.
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://10.10.0.11:8080"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--service-ids", type=int, default=1000)
    parser.add_argument("--profiles", type=int, default=200)
    parser.add_argument("--seed", type=int, default=96)
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    rng = random.Random(args.seed)
    service_ids, benefit_count = sample_service_ids(base_url, args.service_ids, rng)
    options = get_json(base_url, "/api/profile/options")
    inputs = {
        "generatedAt": dt.datetime.now(KST).isoformat(timespec="seconds"),
        "baseUrl": base_url,
        "seed": args.seed,
        "benefitCount": benefit_count,
        "serviceIds": service_ids,
        "sidoCodes": [sido["code"] for sido in options["sidos"]],
        "profiles": [random_profile(options, rng) for _ in range(args.profiles)],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(inputs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"입력 데이터: 혜택 {benefit_count}건 중 서비스 ID {len(service_ids)}개, 프로필 {args.profiles}개 → {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
