#!/usr/bin/env python3
"""
Acceptance harness for the API gateway's rate limiting (BE-RL-01 .. BE-RL-08).

Run ONE case per invocation:

    ./venv/bin/python scripts/rate_limit_test.py BE-RL-01

Each case waits for its bucket to refill, aligns the burst to a whole-second
boundary, fires the requests, and prints a per-request table plus a verdict.

Why the alignment matters: RedisRateLimiter's Lua script reads Redis TIME in
whole seconds, so a burst that straddles a second boundary is handed a full
second's worth of tokens mid-flight (+20 on the default routes). Firing just
after a boundary keeps the whole burst inside one refill window.

Limits come from backend/api-gateway/src/main/resources/application.yml:

    otp-rate-limited        replenish 1/s   burst 5     /api/v1/auth/staff/otp[/verify] + /user/ alias
    auth-rate-limited       replenish 2/s   burst 10    the rest of /api/v1/auth/**      + /user/ alias
    <everything else>       replenish 20/s  burst 40    one bucket per route group

The bucket key is "<route group>:<caller>", caller being the JWT subject when
the gateway authenticated the request and the client IP otherwise. The client
IP is the RIGHTMOST X-Forwarded-For entry, which nginx appends itself -- so
against a host behind nginx a forged header changes nothing (BE-RL-08), and
every anonymous case below shares one bucket per route group.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import math
import os
import sys
import time
from dataclasses import dataclass, field

import requests
import urllib3

# --------------------------------------------------------------------------- config

DEFAULT_BASE_URL = "https://jalsoochak.beehyv.com"

OTP_PATH = "/api/v1/auth/staff/otp"
OTP_ALIAS_PATH = "/user/api/v1/auth/staff/otp"
LOGIN_PATH = "/api/v1/auth/login"
# A real 200 read (dev: tenant 17 = Assam). Any public analytics GET exercises the same
# bucket -- the limiter runs before the proxy, so even a 400 would count a token.
ANALYTICS_PATH = "/api/v1/analytics/continuous-schemes?tenant_id=17"
WEBHOOK_PATH = "/api/v1/telemetry/intro"
# The staff directory is scoped to the caller's own tenant (#482), so tenantCode must
# match both accounts. limit=1 keeps 60 responses cheap. Override with --authenticated-path.
AUTHENTICATED_PATH = "/api/v1/tenant/user/staff?tenantCode=AS&limit=1"
FREE_PATH = "/actuator/health"  # no route predicate matches it, so it costs no token

OTP_BURST, OTP_REPLENISH = 5, 1
AUTH_BURST, AUTH_REPLENISH = 10, 2
DEFAULT_BURST, DEFAULT_REPLENISH = 40, 20

TOO_MANY = 429

# An unregistered number: user-service answers 404 before any OTP is sent, so the
# burst costs gateway tokens without putting real WhatsApp messages on the wire.
# Override with --phone if you need a registered one.
DEFAULT_PHONE = "919999000011"
DEFAULT_TENANT = "AS"

# BE-RL-03/04 only need the gateway to count a request, not for it to succeed, so the
# default identity is one that cannot resolve to an account.
INVALID_EMAIL = "rate-limit-probe@example.invalid"
INVALID_PASSWORD = "not-a-real-password"


# --------------------------------------------------------------------------- plumbing

@dataclass
class Result:
    index: int
    status: object
    remaining: str = "-"
    capacity: str = "-"
    replenish: str = "-"
    retry_after: str = ""
    sent_ms: float = 0.0
    label: str = ""
    error: str = ""


@dataclass
class Verdict:
    passed: bool | None = None  # None => inconclusive
    lines: list[str] = field(default_factory=list)

    def note(self, line: str) -> None:
        self.lines.append(line)


def new_session(cfg) -> requests.Session:
    session = requests.Session()
    session.verify = cfg.verify
    adapter = requests.adapters.HTTPAdapter(pool_connections=1, pool_maxsize=1, max_retries=0)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


def warm(cfg, sessions) -> None:
    """Open every connection up front so TLS handshakes do not stagger the burst."""
    def ping(session):
        try:
            session.get(cfg.base_url + FREE_PATH, timeout=cfg.timeout)
        except requests.RequestException:
            pass

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(sessions)) as pool:
        list(pool.map(ping, sessions))


def align_to_second() -> None:
    """Sleep to just past the next whole second, where the token bucket was refilled."""
    now = time.time()
    time.sleep(max(0.0, math.ceil(now) - now) + 0.02)


def burst(cfg, method, path, count, stagger_ms, *, headers=None, body=None,
          headers_for=None, session_for=None, first_index=1, label_for=None) -> list[Result]:
    """Fire `count` requests `stagger_ms` apart, in order, and return them in send order."""
    sessions = session_for or [new_session(cfg) for _ in range(count)]
    warm(cfg, sessions)
    align_to_second()

    results: list[Result | None] = [None] * count
    url = cfg.base_url + path
    start = time.monotonic()

    def run(i: int) -> None:
        target = start + (i * stagger_ms / 1000.0)
        delay = target - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        sent = time.monotonic()
        request_headers = dict(headers or {})
        if headers_for:
            request_headers.update(headers_for(i))
        try:
            response = sessions[i].request(
                method, url, headers=request_headers, json=body, timeout=cfg.timeout)
            results[i] = Result(
                index=first_index + i,
                status=response.status_code,
                remaining=response.headers.get("X-RateLimit-Remaining", "-"),
                capacity=response.headers.get("X-RateLimit-Burst-Capacity", "-"),
                replenish=response.headers.get("X-RateLimit-Replenish-Rate", "-"),
                retry_after=response.headers.get("Retry-After", ""),
                sent_ms=(sent - start) * 1000,
                label=label_for(i) if label_for else "",
            )
        except requests.RequestException as exc:
            results[i] = Result(index=first_index + i, status="ERROR",
                                sent_ms=(sent - start) * 1000,
                                label=label_for(i) if label_for else "",
                                error=str(exc))

    with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
        list(pool.map(run, range(count)))

    return [r for r in results if r is not None]


def one(cfg, method, path, **kwargs) -> Result:
    return burst(cfg, method, path, 1, 0, **kwargs)[0]


def table(results: list[Result], extra_header: str = "") -> None:
    print(f"  {'#':>3}  {'status':>6}  {'remaining':>9}  {'capacity':>8}  {'t+ms':>6}  {extra_header}")
    for r in results:
        note = r.error or r.label
        print(f"  {r.index:>3}  {str(r.status):>6}  {r.remaining:>9}  {r.capacity:>8}"
              f"  {r.sent_ms:>6.0f}  {note}")


def first_429(results: list[Result]) -> int | None:
    for r in results:
        if r.status == TOO_MANY:
            return r.index
    return None


def upstream_429s(results: list[Result]) -> list[Result]:
    """
    429s that did NOT come from the gateway.

    user-service answers 429 with a Retry-After of its own when Keycloak's brute-force
    detection has locked the account (AccountTemporarilyLockedException). That request was
    *allowed* by the limiter, so it still carries a non-zero X-RateLimit-Remaining -- which
    is what tells the two apart. Counting one as a rate-limit rejection would turn a locked
    account into a false PASS.
    """
    return [r for r in results
            if r.status == TOO_MANY and (r.retry_after or r.remaining not in ("0", "-"))]


def check_no_upstream_429(results: list[Result], verdict: Verdict) -> bool:
    impostors = upstream_429s(results)
    if not impostors:
        return True
    verdict.passed = None
    first = impostors[0]
    verdict.note(f"request {first.index} answered 429 with Retry-After={first.retry_after or 'none'} "
                 f"and remaining={first.remaining}.")
    verdict.note("That 429 came from user-service, not the gateway -- almost certainly Keycloak's")
    verdict.note("brute-force lockout on this account. Use a correct password, or wait out the")
    verdict.note("lockout, before reading anything into these numbers.")
    return False


def limiter_is_on(results: list[Result]) -> bool:
    return any(r.remaining != "-" for r in results)


def check_limiter(results: list[Result], verdict: Verdict) -> bool:
    if limiter_is_on(results):
        return True
    verdict.passed = None
    verdict.note("No X-RateLimit-* header on any response. The limiter never ran:")
    verdict.note("  RATE_LIMIT_ENABLED is false on this environment, or Redis is unreachable")
    verdict.note("  (ToggleableRedisRateLimiter fails open). Nothing below is a real result.")
    return False


def elapsed_note(results: list[Result], replenish: int, verdict: Verdict) -> None:
    span = (results[-1].sent_ms - results[0].sent_ms) / 1000.0
    refilled = int(span * replenish)
    verdict.note(f"burst spanned {span:.2f}s -> up to {refilled} token(s) could have refilled mid-flight")


def phone_payload(cfg) -> dict:
    return {"phoneNumber": cfg.phone, "tenantCode": cfg.tenant}


def drain_otp(cfg) -> list[Result]:
    """Spend the OTP bucket. Returns the burst so the caller can show it ran out."""
    return burst(cfg, "POST", OTP_PATH, OTP_BURST + 2, cfg.stagger_slow, body=phone_payload(cfg))


def login_body(cfg) -> dict:
    """Credentials for the BE-RL-03/04 login bursts."""
    return {"email": cfg.login_email, "password": cfg.login_password}


def describe_login(cfg) -> str:
    """Never prints the password, and says which kind of run this was."""
    kind = "real credentials (expect 200s)" if cfg.login_password != INVALID_PASSWORD \
        else "invalid credentials (expect 401s)"
    return f"{cfg.login_email} -- {kind}"


def login(cfg, email: str, password: str) -> tuple[str | None, str]:
    """Mint an access token. Returns (token, diagnostic)."""
    session = new_session(cfg)
    for attempt in range(3):
        try:
            response = session.post(cfg.base_url + LOGIN_PATH, timeout=cfg.timeout,
                                    json={"email": email, "password": password})
        except requests.RequestException as exc:
            return None, f"transport error: {exc}"
        if response.status_code == TOO_MANY:
            # The auth bucket is shared with BE-RL-03/04; give it time to refill.
            time.sleep(AUTH_BURST / AUTH_REPLENISH + 1)
            continue
        if response.status_code != 200:
            return None, f"HTTP {response.status_code}: {response.text[:200]}"
        # user-service serialises with a snake_case naming strategy; accept either spelling
        # so the harness survives a change of strategy.
        data = response.json().get("data") or {}
        token = data.get("access_token") or data.get("accessToken")
        if not token:
            return None, f"200 but no access token in the body (data keys: {sorted(data)})"
        return token, f"ok ({data.get('user_role') or data.get('role') or 'role unknown'})"
    return None, "429 on every attempt -- the auth bucket never refilled"


def cooldown(cfg, burst_size: int, replenish: int, what: str) -> None:
    if cfg.no_cooldown:
        return
    seconds = cfg.cooldown if cfg.cooldown is not None else burst_size / replenish + 1
    if seconds <= 0:
        return
    print(f"  waiting {seconds:.0f}s for the {what} bucket to refill "
          f"(burst {burst_size} at {replenish}/s)...")
    time.sleep(seconds)


# --------------------------------------------------------------------------- cases

def be_rl_01(cfg) -> Verdict:
    """Seven POSTs to the OTP endpoint in quick succession."""
    v = Verdict()
    cooldown(cfg, OTP_BURST, OTP_REPLENISH, "OTP")

    results = burst(cfg, "POST", OTP_PATH, 7, cfg.stagger_slow, body=phone_payload(cfg))
    table(results)
    if not check_limiter(results, v):
        return v

    hit = first_429(results)
    elapsed_note(results, OTP_REPLENISH, v)

    # "the hotspot is unaffected": a route on the default bucket must still answer.
    control = one(cfg, "GET", cfg.analytics_path)
    print(f"\n  control GET {cfg.analytics_path} -> {control.status} "
          f"(remaining {control.remaining}, capacity {control.capacity})")

    # "allowed again within a second or two": poll until the bucket hands out a token.
    recovered_after = None
    started = time.monotonic()
    while time.monotonic() - started < 5:
        time.sleep(0.5)
        if one(cfg, "POST", OTP_PATH, body=phone_payload(cfg)).status != TOO_MANY:
            recovered_after = time.monotonic() - started
            break
    print(f"  recovery: {'allowed again after %.1fs' % recovered_after if recovered_after else 'still 429 after 5s'}")

    countdown = [r.remaining for r in results[:OTP_BURST]]
    v.note(f"first 429 at request {hit} (expected 6 or 7)")
    v.note(f"X-RateLimit-Remaining before the 429: {' -> '.join(countdown)}")
    v.note(f"control route answered {control.status} (must not be 429)")
    v.passed = (
        hit in (6, 7)
        and control.status != TOO_MANY
        and recovered_after is not None
        and countdown == [str(OTP_BURST - 1 - i) for i in range(OTP_BURST)]
    )
    return v


def be_rl_02(cfg) -> Verdict:
    """Exhaust the OTP bucket on the flat path, then call the /user/-prefixed alias."""
    v = Verdict()
    cooldown(cfg, OTP_BURST, OTP_REPLENISH, "OTP")

    print("  draining the bucket via " + OTP_PATH)
    drained = drain_otp(cfg)
    table(drained)
    if not check_limiter(drained, v):
        return v
    if first_429(drained) is None:
        v.passed = None
        v.note("the flat path never returned 429, so the bucket was never exhausted -- nothing to test")
        return v

    # Two attempts: a refill can land between them and hand the shared bucket one
    # token. A separate bucket would still hold its full burst and allow both.
    print(f"\n  now the alias {OTP_ALIAS_PATH}")
    alias = burst(cfg, "POST", OTP_ALIAS_PATH, 2, cfg.stagger_slow, body=phone_payload(cfg))
    table(alias)

    rejected = sum(1 for r in alias if r.status == TOO_MANY)
    v.note(f"alias answered {[r.status for r in alias]}; {rejected} of 2 rejected")
    v.note("a shared bucket rejects at least one; a separate bucket would allow both")
    v.passed = rejected >= 1
    return v


def be_rl_03(cfg) -> Verdict:
    """Twelve rapid POSTs to the login endpoint."""
    v = Verdict()
    cooldown(cfg, AUTH_BURST, AUTH_REPLENISH, "auth")

    results = burst(cfg, "POST", LOGIN_PATH, 12, cfg.stagger_slow, body=login_body(cfg))
    table(results)
    if not check_limiter(results, v):
        return v
    if not check_no_upstream_429(results, v):
        return v

    hit = first_429(results)
    elapsed_note(results, AUTH_REPLENISH, v)
    before = sorted({str(r.status) for r in results if r.status != TOO_MANY})
    v.note(f"identity: {describe_login(cfg)}")
    v.note(f"allowed requests answered {before} -- the limit counts them either way")
    v.note(f"first 429 at request {hit} (expected 11 or 12)")
    v.note(f"capacity header reported {results[0].capacity} (expected {AUTH_BURST})")
    v.passed = hit in (11, 12) and results[0].capacity == str(AUTH_BURST)
    return v


def be_rl_04(cfg) -> Verdict:
    """Exhaust the OTP bucket, then log in."""
    v = Verdict()
    cooldown(cfg, OTP_BURST, OTP_REPLENISH, "OTP")

    print("  draining the OTP bucket")
    drained = drain_otp(cfg)
    table(drained)
    if not check_limiter(drained, v):
        return v
    if first_429(drained) is None:
        v.passed = None
        v.note("the OTP bucket never ran out, so the premise of the case does not hold")
        return v

    attempt = one(cfg, "POST", LOGIN_PATH, body=login_body(cfg))
    if not check_no_upstream_429([attempt], v):
        return v
    print(f"\n  login after the drain -> {attempt.status} "
          f"(remaining {attempt.remaining}, capacity {attempt.capacity})")

    v.note(f"identity: {describe_login(cfg)}")
    v.note(f"login answered {attempt.status} with an empty OTP bucket (must not be 429)")
    v.note(f"login's own capacity header: {attempt.capacity} (expected {AUTH_BURST}, i.e. a different bucket)")
    v.passed = attempt.status != TOO_MANY
    return v


def be_rl_05(cfg) -> Verdict:
    """Two accounts on one network, 30 rapid authenticated requests each."""
    v = Verdict()
    if not (cfg.email_a and cfg.password_a and cfg.email_b and cfg.password_b):
        v.passed = None
        v.note("needs two accounts: pass --email-a/--password-a and --email-b/--password-b,")
        v.note("or set RL_EMAIL_A / RL_PASSWORD_A / RL_EMAIL_B / RL_PASSWORD_B.")
        return v

    token_a, why_a = login(cfg, cfg.email_a, cfg.password_a)
    token_b, why_b = login(cfg, cfg.email_b, cfg.password_b)
    print(f"  account A login: {why_a}")
    print(f"  account B login: {why_b}")
    if not token_a or not token_b:
        v.passed = None
        v.note("could not mint both tokens, so the buckets could not be told apart")
        return v

    # Interleaved A,B,A,B... from one machine: if the gateway keyed on IP instead of
    # the JWT subject, these 60 requests would overrun a single 40-token bucket.
    count = 60
    tokens = [token_a if i % 2 == 0 else token_b for i in range(count)]
    results = burst(
        cfg, "GET", cfg.authenticated_path, count, cfg.stagger_fast,
        headers_for=lambda i: {"Authorization": f"Bearer {tokens[i]}"},
        label_for=lambda i: "A" if i % 2 == 0 else "B",
    )
    table(results, extra_header="account")

    # On a gated route the security filter chain rejects before the gateway's route
    # filters run, so a stale token yields 401s with no X-RateLimit-* header at all --
    # which would otherwise be misread as "the limiter is switched off".
    blocked = [r for r in results if r.status in (401, 403) and r.remaining == "-"]
    if blocked:
        statuses = sorted({str(r.status) for r in blocked})
        v.passed = None
        v.note(f"{len(blocked)} of {count} were rejected by the gateway's security chain "
               f"(statuses {statuses}) with no X-RateLimit-* header at all.")
        v.note("That chain runs ahead of the route filters, so those requests never reached the")
        v.note(f"limiter and no bucket was exercised. Check both accounts can reach "
               f"{cfg.authenticated_path}.")
        return v
    if not check_limiter(results, v):
        return v

    per_account = {"A": [r for r in results if r.label == "A"],
                   "B": [r for r in results if r.label == "B"]}
    ok = True
    for name, rows in per_account.items():
        rejected = sum(1 for r in rows if r.status == TOO_MANY)
        statuses = sorted({str(r.status) for r in rows})
        v.note(f"account {name}: {len(rows)} requests, {rejected} rejected, statuses {statuses}")
        ok = ok and rejected == 0
    total_rejected = sum(1 for r in results if r.status == TOO_MANY)
    v.note(f"{total_rejected} of {count} rejected overall -- a shared per-IP bucket "
           f"would reject roughly {count - DEFAULT_BURST}")
    v.passed = ok
    return v


def be_rl_06(cfg) -> Verdict:
    """45 rapid anonymous GETs to one public analytics read."""
    v = Verdict()
    cooldown(cfg, DEFAULT_BURST, DEFAULT_REPLENISH, "default")

    results = burst(cfg, "GET", cfg.analytics_path, 45, cfg.stagger_fast)
    table(results)
    if not check_limiter(results, v):
        return v

    hit = first_429(results)
    elapsed_note(results, DEFAULT_REPLENISH, v)
    capacity = results[0].capacity
    allowed = sum(1 for r in results if r.status != TOO_MANY)
    v.note(f"first 429 at request {hit} (expected around 41 or 42)")
    v.note(f"X-RateLimit-Burst-Capacity: {capacity} (expected {DEFAULT_BURST})")
    v.note(f"{allowed} allowed, {45 - allowed} rejected")
    v.passed = capacity == str(DEFAULT_BURST) and hit is not None and 41 <= hit <= 43
    return v


def be_rl_07(cfg) -> Verdict:
    """45 rapid POSTs to a Glific webhook with no webhook token."""
    v = Verdict()
    cooldown(cfg, DEFAULT_BURST, DEFAULT_REPLENISH, "default")

    results = burst(cfg, "POST", WEBHOOK_PATH, 45, cfg.stagger_fast, body={})
    table(results)
    if not check_limiter(results, v):
        return v

    hit = first_429(results)
    elapsed_note(results, DEFAULT_REPLENISH, v)
    counts: dict[str, int] = {}
    for r in results:
        counts[str(r.status)] = counts.get(str(r.status), 0) + 1
    before = [r.status for r in results if r.index < (hit or 46)]
    rejected_by_gateway = counts.get(str(TOO_MANY), 0)

    v.note(f"status counts: {counts}")
    v.note(f"first 429 at request {hit} (expected around 41)")
    v.note(f"X-RateLimit-Burst-Capacity: {results[0].capacity} (expected {DEFAULT_BURST})")
    v.note("--- for DevOps / OPS-LT-6 ---")
    v.note(f"  {len(before)} unauthenticated webhook calls reached telemetry-service and were "
           f"rejected there ({sorted(set(str(s) for s in before))})")
    v.note(f"  {rejected_by_gateway} were shed by the gateway before reaching it")
    v.note(f"  all Glific traffic from one source address shares this one bucket "
           f"({DEFAULT_BURST} burst, {DEFAULT_REPLENISH}/s sustained)")
    v.passed = (
        hit is not None and 41 <= hit <= 43
        and all(s == 401 for s in before)
    )
    return v


def be_rl_08(cfg) -> Verdict:
    """Seven OTP requests, each with a different forged X-Forwarded-For."""
    v = Verdict()
    cooldown(cfg, OTP_BURST, OTP_REPLENISH, "OTP")

    results = burst(
        cfg, "POST", OTP_PATH, 7, cfg.stagger_slow, body=phone_payload(cfg),
        headers_for=lambda i: {"X-Forwarded-For": f"198.51.100.{i + 1}"},
        label_for=lambda i: f"forged 198.51.100.{i + 1}",
    )
    table(results, extra_header="X-Forwarded-For sent")
    if not check_limiter(results, v):
        return v

    hit = first_429(results)
    elapsed_note(results, OTP_REPLENISH, v)
    v.note(f"first 429 at request {hit} (expected 6 or 7, exactly as BE-RL-01)")
    if hit is None:
        v.note("No 429 at all: every forged value got its own bucket. Either this host is NOT")
        v.note("behind the nginx that appends the real peer to X-Forwarded-For, or nginx is not")
        v.note("appending. Straight at the gateway the rightmost entry IS the forgery, so this")
        v.note("result is expected there and says nothing about production.")
    v.passed = hit in (6, 7)
    return v


CASES = {
    "BE-RL-01": (be_rl_01, "Seven POSTs to /api/v1/auth/staff/otp in quick succession"),
    "BE-RL-02": (be_rl_02, "Exhaust the OTP bucket, then call the /user/-prefixed alias"),
    "BE-RL-03": (be_rl_03, "Twelve rapid POSTs to /api/v1/auth/login"),
    "BE-RL-04": (be_rl_04, "Exhaust the OTP bucket, then log in"),
    "BE-RL-05": (be_rl_05, "Two accounts, 30 rapid authenticated requests each"),
    "BE-RL-06": (be_rl_06, "45 rapid anonymous GETs to a public analytics read"),
    "BE-RL-07": (be_rl_07, "45 rapid POSTs to a Glific webhook with no webhook token"),
    "BE-RL-08": (be_rl_08, "Seven OTP requests with forged X-Forwarded-For values"),
}


# --------------------------------------------------------------------------- entrypoint

def parse_args(argv) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("case", nargs="?", help="one of " + ", ".join(CASES) + ", or 'all'")
    parser.add_argument("--list", action="store_true", help="list the cases and exit")
    parser.add_argument("--base-url", default=os.environ.get("RL_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--phone", default=os.environ.get("RL_PHONE", DEFAULT_PHONE),
                        help="phone for the OTP body; default is unregistered so no OTP is sent")
    parser.add_argument("--tenant", default=os.environ.get("RL_TENANT", DEFAULT_TENANT))
    parser.add_argument("--analytics-path", default=ANALYTICS_PATH)
    parser.add_argument("--authenticated-path", default=AUTHENTICATED_PATH)
    parser.add_argument("--login-email", default=os.environ.get("RL_LOGIN_EMAIL", INVALID_EMAIL),
                        help="identity for the BE-RL-03/04 login bursts "
                             "(default: an address that cannot exist, so every attempt 401s)")
    parser.add_argument("--login-password",
                        default=os.environ.get("RL_LOGIN_PASSWORD", INVALID_PASSWORD),
                        help="password for --login-email. Pass a real one to drive the burst with "
                             "successful logins; prefer RL_LOGIN_PASSWORD so it stays out of your "
                             "shell history. A WRONG password repeated 12 times can trip Keycloak's "
                             "brute-force lockout on a real account -- the default address cannot.")
    parser.add_argument("--email-a", default=os.environ.get("RL_EMAIL_A"))
    parser.add_argument("--password-a", default=os.environ.get("RL_PASSWORD_A"))
    parser.add_argument("--email-b", default=os.environ.get("RL_EMAIL_B"))
    parser.add_argument("--password-b", default=os.environ.get("RL_PASSWORD_B"))
    parser.add_argument("--cooldown", type=float, default=None,
                        help="seconds to wait before the burst (default: a full refill window)")
    parser.add_argument("--no-cooldown", action="store_true")
    parser.add_argument("--stagger-slow", type=float, default=20.0,
                        help="ms between requests on the OTP/auth bursts (default 20)")
    parser.add_argument("--stagger-fast", type=float, default=2.0,
                        help="ms between requests on the 40-burst cases (default 2)")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--insecure", action="store_true", help="skip TLS verification")
    args = parser.parse_args(argv)
    args.base_url = args.base_url.rstrip("/")
    args.verify = not args.insecure
    if args.insecure:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
    return args


def main(argv) -> int:
    cfg = parse_args(argv)

    if cfg.list or not cfg.case:
        print("Cases (run one per invocation):\n")
        for case_id, (_, description) in CASES.items():
            print(f"  {case_id}  {description}")
        print("\nExample: ./venv/bin/python scripts/rate_limit_test.py BE-RL-01")
        return 0

    requested = [c.upper() for c in ([*CASES] if cfg.case.lower() == "all" else [cfg.case])]
    unknown = [c for c in requested if c not in CASES]
    if unknown:
        print(f"unknown case(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    print(f"target: {cfg.base_url}")
    outcomes = {}
    for case_id in requested:
        run, description = CASES[case_id]
        print(f"\n{'=' * 78}\n{case_id}  {description}\n{'=' * 78}")
        verdict = run(cfg)
        print()
        for line in verdict.lines:
            print(f"  {line}")
        label = {True: "PASS", False: "FAIL", None: "INCONCLUSIVE"}[verdict.passed]
        outcomes[case_id] = label
        print(f"\n  => {label}")

    if len(outcomes) > 1:
        print(f"\n{'=' * 78}\nsummary")
        for case_id, label in outcomes.items():
            print(f"  {case_id}  {label}")

    return 0 if all(label == "PASS" for label in outcomes.values()) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
