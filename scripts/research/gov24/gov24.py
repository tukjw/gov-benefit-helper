# /// script
# requires-python = ">=3.12"
# dependencies = ["duckdb==1.5.5"]
# ///
"""공공서비스(혜택) API 데이터 검증용 호출·집계 스크립트 (#5).

    uv run scripts/research/gov24/gov24.py probe --max-calls 30
    uv run scripts/research/gov24/gov24.py fetch --per-page <n> --max-calls <n>
    uv run scripts/research/gov24/gov24.py analyze --snapshot <id> [--compare <id>]
    uv run scripts/research/gov24/gov24.py series --snapshots <id> <id> ...

인증키는 환경 변수 GOV24_SERVICE_KEY(공공데이터포털 Decoding 키)로만 받는다.
키는 출력하지 않고 저장하는 파일에서는 가린다. 원문 스냅샷은 저장소 밖 data_dir()에 둔다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

BASE_URL = "https://api.odcloud.kr/api/gov24/v3"
PATHS = ("serviceList", "serviceDetail", "supportConditions")
TABLES = {"serviceList": "service_list", "serviceDetail": "service_detail", "supportConditions": "support_conditions"}
MODIFIED_DIR = "serviceList-modified"
AUTH_HEADER_SCHEME = "Infuser"
TIMEOUT_SECONDS = 60
KST = dt.timezone(dt.timedelta(hours=9))

REPO_ROOT = Path(__file__).resolve().parents[3]
QUERY_DIR = Path(__file__).resolve().parent / "queries"
RESEARCH_DIR = REPO_ROOT / "docs" / "research" / "gov24-api-data"
# 실행 순서. snapshot_diff.sql은 --compare가 있을 때만 실행한다.
QUERY_FILES = (
    "counts.sql",
    "id_consistency.sql",
    "field_quality.sql",
    "condition_values.sql",
    "deadline_classes.sql",
    "region_evidence.sql",
    "samples.sql",
)
COMPARE_QUERY_FILES = ("snapshot_diff.sql",)
SERIES_QUERY_FILES = ("snapshot_series.sql",)
MAX_CELL_CHARS = 200


class BudgetExceeded(Exception):
    pass


class FetchFailed(Exception):
    pass


class CallBudget:
    """호출 수 상한. 상한에 닿으면 더 호출하지 않고 멈춘다."""

    def __init__(self, limit: int):
        self.limit = limit
        self.used = 0

    @property
    def remaining(self) -> int:
        return self.limit - self.used

    def spend(self) -> None:
        if self.used >= self.limit:
            raise BudgetExceeded(f"호출 상한 {self.limit}회에 도달했다")
        self.used += 1


@dataclass
class Response:
    status: int | None  # 연결 실패·시간 초과는 None
    body: bytes
    elapsed_ms: int
    error: str | None = None


def load_key() -> str:
    key = os.environ.get("GOV24_SERVICE_KEY", "").strip()
    if not key:
        sys.exit("GOV24_SERVICE_KEY 환경 변수가 없다")
    return key


def data_dir() -> Path:
    if os.environ.get("GOV24_DATA_DIR"):
        return Path(os.environ["GOV24_DATA_DIR"]).expanduser()
    base = os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share"
    return Path(base) / "gov-benefit-helper" / "gov24"


def key_forms(key: str) -> set[str]:
    return {key, urllib.parse.quote(key, safe=""), urllib.parse.quote_plus(key)}


def redact(text: str, key: str) -> str:
    for form in key_forms(key):
        text = text.replace(form, "***")
    return text


def now_iso() -> str:
    return dt.datetime.now(KST).isoformat(timespec="seconds")


def auth_form(auth: str) -> str:
    return {
        "header": f"Authorization: {AUTH_HEADER_SCHEME} <key>",
        "header-raw": "Authorization: <key>",
        "query": "serviceKey=<key>",
        "none": "없음",
    }[auth]


def request(path: str, params: dict, auth: str, key: str, budget: CallBudget) -> Response:
    """한 번 호출한다. auth는 header, header-raw, query, none 중 하나다."""
    budget.spend()
    query = dict(params)
    headers = {"Accept": "application/json"}
    if auth == "header":
        headers["Authorization"] = f"{AUTH_HEADER_SCHEME} {key}"
    elif auth == "header-raw":
        headers["Authorization"] = key
    elif auth == "query":
        query["serviceKey"] = key
    url = f"{BASE_URL}/{path}?{urllib.parse.urlencode(query)}"
    started = time.monotonic()
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            status, body, error = resp.status, resp.read(), None
    except urllib.error.HTTPError as e:
        status, body, error = e.code, e.read(), None
    except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as e:
        status, body, error = None, b"", f"{type(e).__name__}: {getattr(e, 'reason', e)}"
    elapsed_ms = int((time.monotonic() - started) * 1000)
    return Response(status, body, elapsed_ms, redact(error, key) if error else None)


def request_with_retry(path: str, params: dict, auth: str, key: str, budget: CallBudget) -> Response:
    """시간 초과·연결 실패·5xx에만 한 번 재시도한다."""
    resp = request(path, params, auth, key, budget)
    if resp.status is None or resp.status >= 500:
        time.sleep(2)
        resp = request(path, params, auth, key, budget)
    return resp


# ---------- probe ----------


def probe_record(name: str, path: str, params: dict, auth: str, resp: Response, key: str) -> dict:
    text = redact(resp.body.decode("utf-8", "replace"), key)
    record = {
        "name": name,
        "path": path,
        "params": params,
        "auth": auth_form(auth),
        "status": resp.status,
        "elapsed_ms": resp.elapsed_ms,
        "bytes": len(resp.body),
        "error": resp.error,
    }
    try:
        doc = json.loads(text)
    except json.JSONDecodeError:
        doc = None
    if isinstance(doc, dict) and isinstance(doc.get("data"), list):
        record["meta"] = {k: v for k, v in doc.items() if k != "data"}
        record["data_len"] = len(doc["data"])
        record["first_record_keys"] = list(doc["data"][0]) if doc["data"] else []
    else:
        record["body_head"] = text[:1000]
    return record


def probe_auth(key: str, budget: CallBudget) -> list[dict]:
    params = {"page": 1, "perPage": 1}
    return [
        probe_record(f"auth:{auth}", "serviceList", params, auth, request("serviceList", params, auth, key, budget), key)
        for auth in ("header", "header-raw", "query")
    ]


def probe_page_size(key: str, budget: CallBudget, auth: str, sizes: list[int]) -> list[dict]:
    out = []
    for path in PATHS:
        for size in sizes:
            params = {"page": 1, "perPage": size}
            resp = request(path, params, auth, key, budget)
            out.append(probe_record(f"page-size:{path}:{size}", path, params, auth, resp, key))
    return out


def probe_failures(key: str, budget: CallBudget, auth: str) -> list[dict]:
    cases = [
        ("no-key", "serviceList", {"page": 1, "perPage": 1}, "none", key),
        ("invalid-key", "serviceList", {"page": 1, "perPage": 1}, auth, "invalid-key-for-probe"),
        ("page-0", "serviceList", {"page": 0, "perPage": 1}, auth, key),
        ("per-page-0", "serviceList", {"page": 1, "perPage": 0}, auth, key),
        ("page-beyond-end", "serviceList", {"page": 999999, "perPage": 1}, auth, key),
        ("unknown-service-id", "serviceDetail", {"page": 1, "perPage": 1, "cond[서비스ID::EQ]": "000000000000"}, auth, key),
    ]
    out = []
    for name, path, params, case_auth, case_key in cases:
        resp = request(path, params, case_auth, case_key, budget)
        out.append(probe_record(f"failure:{name}", path, params, case_auth, resp, key))
    return out


def cmd_probe(args: argparse.Namespace) -> None:
    key = load_key()
    budget = CallBudget(args.max_calls)
    results = probe_auth(key, budget)
    working = next((r["name"].split(":", 1)[1] for r in results if r["status"] == 200), None)
    if working is None:
        print("인증에 성공한 방식이 없어 페이지 크기·실패 응답 확인을 건너뛴다")
    else:
        results += probe_page_size(key, budget, working, args.sizes)
        results += probe_failures(key, budget, working)
    out_dir = data_dir() / "probes"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{dt.datetime.now(KST):%Y%m%dT%H%M%S}.json"
    report = {"probed_at": now_iso(), "base_url": BASE_URL, "calls_used": budget.used, "results": results}
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for r in results:
        meta = r.get("meta") or {}
        summary = {k: meta.get(k) for k in ("totalCount", "matchCount", "currentCount", "perPage")} if meta else r.get("body_head", "")[:160]
        print(f"{r['name']:<40} status={r['status']} {r['elapsed_ms']:>6}ms {r['bytes']:>10}B data={r.get('data_len')} {summary}")
    print(f"호출 {budget.used}회, 결과: {out_path}")


# ---------- fetch ----------


def collect(path: str, per_page: int, out_dir: Path, budget: CallBudget, key: str, result: dict,
            cond: dict | None = None) -> None:
    """한 경로를 페이지 순회로 전부 받아 out_dir에 저장한다. 진행 상황은 result에 기록한다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    forms = [f.encode() for f in key_forms(key)]
    page = 1
    while True:
        resp = request_with_retry(path, {"page": page, "perPage": per_page, **(cond or {})}, "header", key, budget)
        entry = {"page": page, "status": resp.status, "bytes": len(resp.body), "elapsed_ms": resp.elapsed_ms, "fetched_at": now_iso()}
        result["pages"].append(entry)
        if resp.status != 200:
            head = redact(resp.body[:300].decode("utf-8", "replace"), key)
            raise FetchFailed(f"{path} {page}쪽: status={resp.status} error={resp.error} body={head}")
        if any(f in resp.body for f in forms):
            raise FetchFailed(f"{path} {page}쪽: 응답 본문에 인증키가 들어 있어 저장하지 않는다")
        doc = json.loads(resp.body)
        # perPage가 크면 200 {"code":0,"msg":"정상"}만 오고 data가 빠진다(2026-09-28 probe). 마지막 쪽으로 보지 않는다.
        if not isinstance(doc.get("data"), list):
            raise FetchFailed(f"{path} {page}쪽: 200이지만 data가 없다 body={redact(resp.body[:300].decode('utf-8', 'replace'), key)}")
        data = doc["data"]
        (out_dir / f"page-{page:04d}.json").write_bytes(resp.body)
        entry.update(currentCount=doc.get("currentCount"), records=len(data), sha256=hashlib.sha256(resp.body).hexdigest())
        if page == 1:
            result["totalCount"] = doc.get("totalCount")
            result["matchCount"] = doc.get("matchCount")
            remaining_pages = math.ceil((result["matchCount"] or 0) / per_page) - 1
            if remaining_pages > budget.remaining:
                raise BudgetExceeded(f"{path}: 남은 {remaining_pages}쪽이 남은 호출 {budget.remaining}회보다 많다")
        result["records"] += len(data)
        # 조건(cond)이 있으면 totalCount는 전체 건수, matchCount가 조건에 맞는 건수다(2026-09-28 probe).
        if page * per_page >= (result["matchCount"] or 0):
            return
        if not data:
            result["warning"] = f"{page}쪽이 비어 matchCount보다 먼저 끝났다"
            return
        page += 1
        time.sleep(0.2)


def write_manifest(snapshot_dir: Path, manifest: dict) -> None:
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    (snapshot_dir / "manifest.json").write_text(text, encoding="utf-8")
    if manifest["status"] == "complete":
        target = RESEARCH_DIR / "snapshots" / f"{manifest['snapshot_id']}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")


def cmd_fetch(args: argparse.Namespace) -> None:
    key = load_key()
    budget = CallBudget(args.max_calls)
    snapshot_id = f"{dt.datetime.now(KST):%Y%m%dT%H%M%S}"
    snapshot_dir = data_dir() / "snapshots" / snapshot_id
    snapshot_dir.mkdir(parents=True)
    manifest = {
        "snapshot_id": snapshot_id,
        "base_url": BASE_URL,
        "auth": auth_form("header"),
        "per_page": args.per_page,
        "modified_since": args.modified_since,
        "started_at": now_iso(),
        "paths": {},
    }
    # 수정일시 필터 조회는 serviceList-modified 폴더에 따로 둔다. 목록 비교와 맞춰 보는 데 쓴다.
    jobs = [(path, path, None) for path in args.paths]
    if args.modified_since:
        jobs.append(("serviceList", MODIFIED_DIR, {"cond[수정일시::GTE]": args.modified_since}))
    status = "complete"
    try:
        for path, name, cond in jobs:
            result = manifest["paths"][name] = {"totalCount": None, "matchCount": None, "records": 0, "pages": []}
            collect(path, args.per_page, snapshot_dir / name, budget, key, result, cond)
            print(f"{name}: totalCount={result['totalCount']} matchCount={result['matchCount']} "
                  f"records={result['records']} pages={len(result['pages'])}")
    except (BudgetExceeded, FetchFailed) as e:
        status = "failed"
        manifest["error"] = redact(str(e), key)
        print(f"수집 중단: {manifest['error']}", file=sys.stderr)
    finally:
        manifest.update(status=status, finished_at=now_iso(), calls_used=budget.used)
        write_manifest(snapshot_dir, manifest)
    print(f"스냅샷 {snapshot_id} ({status}), 호출 {budget.used}회: {snapshot_dir}")
    if status != "complete":
        sys.exit(1)


# ---------- analyze ----------


def snapshot_path(snapshot_id: str) -> Path:
    """스냅샷 폴더를 찾고, 파일이 목록 파일의 해시와 같은지 확인한다."""
    path = data_dir() / "snapshots" / snapshot_id
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    if manifest["status"] != "complete":
        sys.exit(f"스냅샷 {snapshot_id}은 완료되지 않았다")
    for api_path, result in manifest["paths"].items():
        for entry in result["pages"]:
            body = (path / api_path / f"page-{entry['page']:04d}.json").read_bytes()
            if hashlib.sha256(body).hexdigest() != entry["sha256"]:
                sys.exit(f"스냅샷 {snapshot_id}의 {api_path} {entry['page']}쪽이 목록 파일과 다르다")
    return path


def load_snapshot(con, snapshot_dir: Path, schema: str) -> None:
    """스냅샷을 <schema> 스키마에 올린다.

    snapshot: 스냅샷 ID, 수집 날짜, 수집 시작 시각(수정일시와 같은 형식), 수정일시 필터 값.
    pages: 경로·쪽별 응답 메타 정보.
    <table>_raw: 레코드 원문(JSON). <table>: 레코드의 필드를 문자열 컬럼으로 편 뷰.
    modified_list_raw: 수정일시 필터로 받은 목록 원문. 필터를 쓰지 않은 스냅샷이면 비어 있다.
    """
    manifest = json.loads((snapshot_dir / "manifest.json").read_text(encoding="utf-8"))
    started = dt.datetime.fromisoformat(manifest["started_at"]).strftime("%Y%m%d%H%M%S")
    modified_since = manifest.get("modified_since")
    con.execute(f"CREATE SCHEMA {schema}")
    con.execute(f"""
        CREATE TABLE {schema}.snapshot AS
        SELECT '{manifest["snapshot_id"]}' AS snapshot_id,
               strptime('{started[:8]}', '%Y%m%d')::DATE AS snapshot_date,
               '{started}' AS started_at,
               {f"'{modified_since}'" if modified_since else "NULL::VARCHAR"} AS modified_since
    """)
    con.execute(f"""
        CREATE TABLE {schema}.pages (path VARCHAR, page INTEGER, perPage BIGINT, totalCount BIGINT,
                                     currentCount BIGINT, matchCount BIGINT, records BIGINT)
    """)
    con.execute(f"CREATE TABLE {schema}.modified_list_raw (page INTEGER, rec JSON)")
    if (snapshot_dir / MODIFIED_DIR).is_dir():
        con.execute(f"""
            INSERT INTO {schema}.modified_list_raw
            SELECT regexp_extract(filename, 'page-(\\d+)\\.json$', 1)::INTEGER, unnest(data)
            FROM read_json('{(snapshot_dir / MODIFIED_DIR / "page-*.json").as_posix()}',
                           columns = {{'data': 'JSON[]'}}, filename = true)
        """)
    for api_path, table in TABLES.items():
        glob = (snapshot_dir / api_path / "page-*.json").as_posix()
        con.execute(f"""
            CREATE TEMP TABLE page_source AS
            SELECT regexp_extract(filename, 'page-(\\d+)\\.json$', 1)::INTEGER AS file_page, *
            FROM read_json('{glob}',
                           columns = {{'page': 'BIGINT', 'perPage': 'BIGINT', 'totalCount': 'BIGINT',
                                       'currentCount': 'BIGINT', 'matchCount': 'BIGINT', 'data': 'JSON[]'}},
                           filename = true)
        """)
        con.execute(f"""
            INSERT INTO {schema}.pages
            SELECT '{api_path}', file_page, perPage, totalCount, currentCount, matchCount, len(data) FROM page_source
        """)
        con.execute(f"CREATE TABLE {schema}.{table}_raw AS SELECT file_page AS page, unnest(data) AS rec FROM page_source")
        con.execute("DROP TABLE page_source")
        keys = [row[0] for row in con.execute(
            f"SELECT DISTINCT unnest(json_keys(rec)) FROM {schema}.{table}_raw ORDER BY 1").fetchall()]
        columns = ", ".join(f"rec->>'{k}' AS \"{k}\"" for k in keys)
        con.execute(f"CREATE VIEW {schema}.{table} AS SELECT page, {columns} FROM {schema}.{table}_raw")


def parse_queries(text: str) -> list[tuple[str, str]]:
    """`-- name: 제목` 줄로 나뉜 쿼리를 (제목, SQL)로 돌려준다. 제목이 `setup`이면 결과를 쓰지 않는다."""
    blocks, name, lines = [], None, []
    for line in text.splitlines():
        if line.startswith("-- name:"):
            if name is not None:
                blocks.append((name, "\n".join(lines)))
            name, lines = line.removeprefix("-- name:").strip(), []
        elif name is not None:
            lines.append(line)
    if name is not None:
        blocks.append((name, "\n".join(lines)))
    return blocks


def cell(value) -> str:
    if value is None:
        return "NULL"
    text = str(value).replace("\r", "").replace("\n", "⏎").replace("|", "\\|")
    return text if len(text) <= MAX_CELL_CHARS else text[:MAX_CELL_CHARS] + "…"


def to_markdown(columns: list[str], rows: list[tuple]) -> str:
    if not rows:
        return "(결과 없음)"
    lines = ["| " + " | ".join(columns) + " |", "| " + " | ".join("---" for _ in columns) + " |"]
    lines += ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    return "\n".join(lines)


def run_queries(con, files: tuple[str, ...]) -> list[str]:
    sections = []
    for file in files:
        sections.append(f"## {file}")
        for name, sql in parse_queries((QUERY_DIR / file).read_text(encoding="utf-8")):
            try:
                rel = con.execute(sql)
            except Exception as e:
                raise SystemExit(f"{file} '{name}' 실행 실패: {e}")
            if name == "setup":
                continue
            columns = [d[0] for d in rel.description]
            sections.append(f"### {name}\n\n{to_markdown(columns, rel.fetchall())}")
    return sections


def write_results(args: argparse.Namespace, sections: list[str]) -> Path:
    command = f"uv run scripts/research/gov24/gov24.py analyze --snapshot {args.snapshot}"
    if args.compare:
        command += f" --compare {args.compare}"
    header = [
        f"# 집계 결과 — 스냅샷 {args.snapshot}",
        "",
        "이 파일은 스크립트가 생성한다. 직접 고치지 않는다.",
        "",
        f"- 스냅샷 목록: [`snapshots/{args.snapshot}.json`](../snapshots/{args.snapshot}.json)",
        f"- 비교 스냅샷: `{args.compare}`" if args.compare else "- 비교 스냅샷: 없음",
        f"- 생성 명령: `{command}`",
        f"- 쿼리: `scripts/research/gov24/queries/`",
    ]
    out = RESEARCH_DIR / "results" / f"{args.snapshot}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(header) + "\n\n" + "\n\n".join(sections) + "\n", encoding="utf-8")
    return out


def cmd_analyze(args: argparse.Namespace) -> None:
    import duckdb

    con = duckdb.connect()
    load_snapshot(con, snapshot_path(args.snapshot), "cur")
    files = QUERY_FILES
    if args.compare:
        load_snapshot(con, snapshot_path(args.compare), "prev")
        files += COMPARE_QUERY_FILES
    print(f"결과: {write_results(args, run_queries(con, files))}")


def write_series_results(args: argparse.Namespace, sections: list[str]) -> Path:
    first, last = args.snapshots[0], args.snapshots[-1]
    header = [
        f"# 집계 결과 — 스냅샷 {first} ~ {last}",
        "",
        "이 파일은 스크립트가 생성한다. 직접 고치지 않는다.",
        "",
        "- 스냅샷: " + ", ".join(f"[`{i}`](../snapshots/{i}.json)" for i in args.snapshots),
        f"- 생성 명령: `uv run scripts/research/gov24/gov24.py series --snapshots {' '.join(args.snapshots)}`",
        f"- 쿼리: `scripts/research/gov24/queries/`",
    ]
    out = RESEARCH_DIR / "results" / f"series-{first}-{last}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(header) + "\n\n" + "\n\n".join(sections) + "\n", encoding="utf-8")
    return out


def cmd_series(args: argparse.Namespace) -> None:
    """여러 스냅샷의 목록을 obs(순번, 서비스ID, ...)와 snapshots(순번, 스냅샷, started_at)로 합쳐 집계한다."""
    import duckdb

    if len(args.snapshots) < 2 or args.snapshots != sorted(set(args.snapshots)):
        sys.exit("--snapshots에는 서로 다른 스냅샷 2개 이상을 수집 순서(ID 오름차순)로 준다")
    con = duckdb.connect()
    for i, snapshot_id in enumerate(args.snapshots):
        load_snapshot(con, snapshot_path(snapshot_id), f"s{i}")
    indexes = range(len(args.snapshots))
    con.execute("CREATE TEMP TABLE snapshots AS " + " UNION ALL ".join(
        f"SELECT {i} AS 순번, snapshot_id AS 스냅샷, started_at FROM s{i}.snapshot" for i in indexes))
    con.execute("CREATE TEMP TABLE obs AS " + " UNION ALL ".join(
        f"SELECT {i} AS 순번, 서비스ID, 서비스명, 등록일시, 수정일시 FROM s{i}.service_list" for i in indexes))
    print(f"결과: {write_series_results(args, run_queries(con, SERIES_QUERY_FILES))}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    probe = sub.add_parser("probe", help="인증 형식·페이지 크기·실패 응답을 적은 호출로 확인한다")
    probe.add_argument("--max-calls", type=int, required=True)
    probe.add_argument("--sizes", type=int, nargs="+", default=[100, 500, 1000, 5000])
    probe.set_defaults(func=cmd_probe)

    fetch = sub.add_parser("fetch", help="세 경로를 페이지 순회로 전부 받아 스냅샷으로 저장한다")
    fetch.add_argument("--per-page", type=int, required=True)
    fetch.add_argument("--max-calls", type=int, required=True)
    fetch.add_argument("--paths", nargs="+", choices=PATHS, default=list(PATHS))
    fetch.add_argument("--modified-since", help="목록을 cond[수정일시::GTE]=<값>으로 한 번 더 받는다 (예: 20260928163852)")
    fetch.set_defaults(func=cmd_fetch)

    analyze = sub.add_parser("analyze", help="스냅샷을 집계해 docs/research/gov24-api-data/results/에 쓴다")
    analyze.add_argument("--snapshot", required=True)
    analyze.add_argument("--compare")
    analyze.set_defaults(func=cmd_analyze)

    series = sub.add_parser("series", help="여러 스냅샷의 목록에서 사라짐·재등장을 집계한다")
    series.add_argument("--snapshots", nargs="+", required=True, help="수집 순서대로 준 스냅샷 ID")
    series.set_defaults(func=cmd_series)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
