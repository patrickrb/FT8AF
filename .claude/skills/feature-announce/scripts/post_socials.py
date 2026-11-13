"""Publish one FT8AF announcement to FT8AF's accounts through each network's HTTP API.

Ported from the Sorrel skill's post_socials.py (~/.claude/skills/release-social-posts),
with one hard difference: credentials come ONLY from FT8AF's own env file, never from the
process environment and never from a .env beside a script, so a run can't post to
Sorrel's accounts by picking up its tokens. The file is FT8AF_SOCIAL_ENV, default
%LOCALAPPDATA%\ft8af-factory\social.env (KEY=value lines; never commit it).

Usage:
  python -I post_socials.py --posts posts.json [--only facebook,x] [--dry-run]
  python -I post_socials.py --self-test

posts.json (every key optional; only the networks present are posted):
  {
    "facebook": {"text": "...", "link": "https://ft8af.app/blog/<slug>"},
    "x":        {"text": "... https://ft8af.app/blog/<slug>"},          280 weighted chars
    "bluesky":  {"text": "... https://..."},                           300 graphemes
    "threads":  {"text": "...", "link": "https://..."},
    "discord":  {"text": "...", "link": "https://..."}                 2000 chars
  }

Env file keys:
  facebook   META_PAGE_ID, META_PAGE_TOKEN
  x          X_API_KEY, X_API_SECRET, X_ACCESS_TOKEN, X_ACCESS_SECRET
  bluesky    BSKY_HANDLE, BSKY_APP_PASSWORD   (optional BSKY_PDS)
  threads    THREADS_USER_ID, THREADS_TOKEN
  discord    DISCORD_ANNOUNCE_WEBHOOK         (a webhook on the announcements channel)

A network whose keys are missing fails on its own and the rest still post. Prints the
post URL (or id) per network; exits 1 if any network failed. Token values are never
printed.
"""
import argparse
import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import sys
import time
import unicodedata
import urllib.parse
from datetime import datetime, timezone

import requests

TIMEOUT = 30
GRAPH = "https://graph.facebook.com/v23.0"
THREADS = "https://graph.threads.net/v1.0"
X_TWEETS = "https://api.x.com/2/tweets"
UA = "FT8AFSocialPoster/1.0 (+https://ft8af.app)"

NETWORKS = ["facebook", "x", "bluesky", "threads", "discord", "instagram", "pinterest"]
ENV = {
    "facebook": ["META_PAGE_ID", "META_PAGE_TOKEN"],
    "instagram": ["IG_USER_ID", "META_PAGE_TOKEN"],
    "threads": ["THREADS_USER_ID", "THREADS_TOKEN"],
    "x": ["X_API_KEY", "X_API_SECRET", "X_ACCESS_TOKEN", "X_ACCESS_SECRET"],
    "bluesky": ["BSKY_HANDLE", "BSKY_APP_PASSWORD"],
    "pinterest": ["PINTEREST_TOKEN"],
    "discord": ["DISCORD_ANNOUNCE_WEBHOOK"],
}
SECRET_VARS = ["META_PAGE_TOKEN", "THREADS_TOKEN", "X_API_KEY", "X_API_SECRET",
               "X_ACCESS_TOKEN", "X_ACCESS_SECRET", "BSKY_APP_PASSWORD", "PINTEREST_TOKEN",
               "DISCORD_ANNOUNCE_WEBHOOK"]
URL_RE = re.compile(r"https?://[^\s<>\"')\]]*[^\s<>\"')\].,;:!?]")


class PostError(Exception):
    pass


# --------------------------------------------------------------------------- env + http

def env_path():
    return os.environ.get("FT8AF_SOCIAL_ENV") or os.path.join(
        os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "ft8af-factory", "social.env")


def load_dotenv():
    """Credentials come only from FT8AF's env file. Every credential variable already in
    the process environment is dropped first, so a shell that exported another product's
    tokens can't make this script post to that product's accounts."""
    for keys in ENV.values():
        for key in keys:
            os.environ.pop(key, None)
    os.environ.pop("BSKY_PDS", None)
    path = env_path()
    if not os.path.isfile(path):
        return path
    with open(path, encoding="utf-8-sig") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, val = line.split("=", 1)
            key = key.strip()
            if key.startswith("export "):
                key = key[7:].strip()
            val = val.strip()
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "'\"":
                val = val[1:-1]
            if key:
                os.environ[key] = val
    return path


def redact(text):
    text = str(text)
    for var in SECRET_VARS:
        val = os.environ.get(var)
        if val and len(val) >= 4:
            text = text.replace(val, "<%s>" % var)
            text = text.replace(urllib.parse.quote(val, safe=""), "<%s>" % var)
    return text


def need_env(network):
    missing = [v for v in ENV[network] if not os.environ.get(v)]
    if missing:
        raise PostError("missing env %s" % ", ".join(missing))
    return [os.environ[v] for v in ENV[network]]


def call(method, url, token_var=None, **kw):
    """One HTTP request with a 30 s timeout; raises PostError with a redacted message
    that names the token to check on 401/403."""
    kw.setdefault("timeout", TIMEOUT)
    headers = kw.pop("headers", None) or {}
    headers.setdefault("User-Agent", UA)
    where = "%s %s" % (method, url.split("?")[0])
    try:
        r = requests.request(method, url, headers=headers, **kw)
    except requests.RequestException as e:
        raise PostError(redact("%s: %s" % (where, e.__class__.__name__)))
    if r.status_code >= 400:
        hint = ""
        if r.status_code in (400, 401, 403) and token_var:
            hint = " (if this is an auth error, check %s: expired, revoked or missing a scope)" % token_var
        raise PostError(redact("%s -> HTTP %d%s: %s" % (where, r.status_code, hint, r.text[:600])))
    try:
        return r.json()
    except ValueError:
        return {"_raw": r.text}


# --------------------------------------------------------------------------- text helpers

def graphemes(text):
    """Rough grapheme count: combining marks, variation selectors, skin tones, tag
    characters and ZWJ-joined emoji don't add; a regional-indicator pair counts once."""
    n = 0
    join_next = False
    ri_open = False
    for ch in text:
        cp = ord(ch)
        if join_next:
            join_next = False
            continue
        if cp == 0x200D:
            join_next = True
            continue
        if (unicodedata.combining(ch) or unicodedata.category(ch) in ("Mn", "Me")
                or 0xFE00 <= cp <= 0xFE0F or 0x1F3FB <= cp <= 0x1F3FF or 0xE0020 <= cp <= 0xE007F):
            continue
        if 0x1F1E6 <= cp <= 0x1F1FF:
            if ri_open:
                ri_open = False
                continue
            ri_open = True
        else:
            ri_open = False
        n += 1
    return n


def x_length(text):
    """X's weighted length, approximated: each URL counts 23, CJK and emoji count 2."""
    total = 0
    pos = 0
    for m in URL_RE.finditer(text):
        total += _x_weight(text[pos:m.start()]) + 23
        pos = m.end()
    return total + _x_weight(text[pos:])


def _x_weight(s):
    w = 0
    for ch in s:
        cp = ord(ch)
        light = cp <= 0x10FF or 0x2000 <= cp <= 0x200D or 0x2010 <= cp <= 0x201F or 0x2032 <= cp <= 0x2037
        w += 1 if light else 2
    return w


def require(post, network, keys):
    if not isinstance(post, dict):
        raise PostError("posts.json %s entry must be an object" % network)
    missing = [k for k in keys if not str(post.get(k) or "").strip()]
    if missing:
        raise PostError("posts.json %s is missing %s" % (network, ", ".join(missing)))


def check_len(label, value, limit, counter=len):
    n = counter(value)
    if n > limit:
        raise PostError("%s is %d, over the %d limit" % (label, n, limit))
    return n


def dry_out(endpoint, payload):
    return "DRY RUN  %s\n%s" % (endpoint, json.dumps(payload, indent=2, ensure_ascii=False))


# --------------------------------------------------------------------------- facebook

def facebook(post, dry, warn):
    require(post, "facebook", ["text", "link"])
    payload = {"message": post["text"], "link": post["link"]}
    if dry:
        return dry_out("POST %s/{META_PAGE_ID}/feed" % GRAPH, payload)
    page_id, token = need_env("facebook")
    res = call("POST", "%s/%s/feed" % (GRAPH, page_id), "META_PAGE_TOKEN",
               data=dict(payload, access_token=token))
    pid = res.get("id")
    if not pid:
        raise PostError("no id in response: %s" % res)
    return "https://www.facebook.com/%s" % pid


# --------------------------------------------------------------------------- instagram

def instagram(post, dry, warn):
    require(post, "instagram", ["caption", "image_url"])
    check_len("instagram caption", post["caption"], 2200)
    tags = len(re.findall(r"#\w", post["caption"]))
    if tags > 30:
        raise PostError("instagram caption has %d hashtags, over the 30 limit" % tags)
    payload = {"image_url": post["image_url"], "caption": post["caption"]}
    if dry:
        return dry_out("POST %s/{IG_USER_ID}/media, poll status_code, POST .../media_publish" % GRAPH, payload)
    ig_id, token = need_env("instagram")
    res = call("POST", "%s/%s/media" % (GRAPH, ig_id), "META_PAGE_TOKEN",
               data=dict(payload, access_token=token))
    container = res.get("id")
    if not container:
        raise PostError("no container id: %s" % res)
    status = None
    for _ in range(40):  # about two minutes
        st = call("GET", "%s/%s" % (GRAPH, container), "META_PAGE_TOKEN",
                  params={"fields": "status_code,status", "access_token": token})
        status = st.get("status_code")
        if status == "FINISHED":
            break
        if status in ("ERROR", "EXPIRED"):
            raise PostError("media container %s: %s" % (status, st.get("status", "")))
        time.sleep(3)
    if status != "FINISHED":
        raise PostError("media container never reached FINISHED (last status %s)" % status)
    pub = call("POST", "%s/%s/media_publish" % (GRAPH, ig_id), "META_PAGE_TOKEN",
               data={"creation_id": container, "access_token": token})
    media_id = pub.get("id")
    if not media_id:
        raise PostError("no media id: %s" % pub)
    try:
        link = call("GET", "%s/%s" % (GRAPH, media_id), "META_PAGE_TOKEN",
                    params={"fields": "permalink", "access_token": token}).get("permalink")
    except PostError:
        link = None
    return link or "media id %s" % media_id


# --------------------------------------------------------------------------- threads

def threads(post, dry, warn):
    require(post, "threads", ["text"])
    text = post["text"].rstrip()
    link = (post.get("link") or "").strip()
    if link and link not in text:
        text = text + "\n\n" + link
    check_len("threads text", text, 500)
    payload = {"media_type": "TEXT", "text": text}
    if dry:
        return dry_out("POST %s/{THREADS_USER_ID}/threads, POST .../threads_publish" % THREADS, payload)
    uid, token = need_env("threads")
    res = call("POST", "%s/%s/threads" % (THREADS, uid), "THREADS_TOKEN",
               data=dict(payload, access_token=token))
    container = res.get("id")
    if not container:
        raise PostError("no container id: %s" % res)
    for _ in range(10):  # text containers are normally ready at once
        try:
            st = call("GET", "%s/%s" % (THREADS, container), "THREADS_TOKEN",
                      params={"fields": "status,error_message", "access_token": token})
        except PostError:
            break
        status = st.get("status")
        if status in (None, "FINISHED", "PUBLISHED"):
            break
        if status in ("ERROR", "EXPIRED"):
            raise PostError("container %s: %s" % (status, st.get("error_message", "")))
        time.sleep(3)
    pub = call("POST", "%s/%s/threads_publish" % (THREADS, uid), "THREADS_TOKEN",
               data={"creation_id": container, "access_token": token})
    tid = pub.get("id")
    if not tid:
        raise PostError("no thread id: %s" % pub)
    try:
        permalink = call("GET", "%s/%s" % (THREADS, tid), "THREADS_TOKEN",
                         params={"fields": "permalink", "access_token": token}).get("permalink")
    except PostError:
        permalink = None
    return permalink or "thread id %s" % tid


# --------------------------------------------------------------------------- x (OAuth 1.0a)

def _pct(s):
    return urllib.parse.quote(str(s), safe="~-._")


def oauth1_header(method, url, consumer_key, consumer_secret, token, token_secret, params=None):
    """OAuth 1.0a HMAC-SHA1 Authorization header (RFC 5849). A JSON body is not part of
    the signature; query or form params passed in `params` are."""
    oauth = {
        "oauth_consumer_key": consumer_key,
        "oauth_nonce": secrets.token_hex(16),
        "oauth_signature_method": "HMAC-SHA1",
        "oauth_timestamp": str(int(time.time())),
        "oauth_token": token,
        "oauth_version": "1.0",
    }
    allp = dict(params or {})
    allp.update(oauth)
    pairs = sorted((_pct(k), _pct(v)) for k, v in allp.items())
    param_str = "&".join("%s=%s" % kv for kv in pairs)
    base = "&".join([method.upper(), _pct(url), _pct(param_str)])
    key = "%s&%s" % (_pct(consumer_secret), _pct(token_secret))
    sig = base64.b64encode(hmac.new(key.encode(), base.encode(), hashlib.sha1).digest()).decode()
    oauth["oauth_signature"] = sig
    return "OAuth " + ", ".join('%s="%s"' % (_pct(k), _pct(v)) for k, v in sorted(oauth.items()))


def x(post, dry, warn):
    require(post, "x", ["text"])
    check_len("x text (weighted, URLs as 23)", post["text"], 280, x_length)
    payload = {"text": post["text"]}
    if dry:
        return dry_out("POST %s (OAuth 1.0a user context)" % X_TWEETS, payload)
    ck, cs, at, ats = need_env("x")
    auth = oauth1_header("POST", X_TWEETS, ck, cs, at, ats)
    res = call("POST", X_TWEETS, "the X_* keys", json=payload,
               headers={"Authorization": auth, "Content-Type": "application/json"})
    tid = (res.get("data") or {}).get("id")
    if not tid:
        raise PostError("no tweet id: %s" % res)
    return "https://x.com/FT8AF/status/%s" % tid


# --------------------------------------------------------------------------- bluesky

def bsky_facets(text):
    facets = []
    for m in URL_RE.finditer(text):
        start = len(text[:m.start()].encode("utf-8"))
        end = start + len(m.group(0).encode("utf-8"))
        facets.append({
            "index": {"byteStart": start, "byteEnd": end},
            "features": [{"$type": "app.bsky.richtext.facet#link", "uri": m.group(0)}],
        })
    return facets


def og_tags(page_html):
    tags = {}
    for m in re.finditer(r"<meta\b[^>]*>", page_html, re.I):
        tag = m.group(0)
        prop = re.search(r"""(?:property|name)\s*=\s*["']([^"']+)["']""", tag, re.I)
        content = re.search(r"""content\s*=\s*"([^"]*)"|content\s*=\s*'([^']*)'""", tag, re.I)
        if prop and content:
            key = prop.group(1).lower()
            if key.startswith("og:") and key not in tags:
                tags[key] = html.unescape(content.group(1) if content.group(1) is not None else content.group(2))
    return tags


def bsky_embed(url, pds, jwt, warn):
    try:
        r = requests.get(url, timeout=TIMEOUT, headers={"User-Agent": UA})
        r.raise_for_status()
        og = og_tags(r.text)
    except requests.RequestException as e:
        warn("link card: could not fetch %s (%s); posting without a card" % (url, e.__class__.__name__))
        return None
    external = {
        "uri": url,
        "title": og.get("og:title", ""),
        "description": og.get("og:description", ""),
    }
    img = og.get("og:image")
    if img:
        img = urllib.parse.urljoin(url, img)
        try:
            ir = requests.get(img, timeout=TIMEOUT, headers={"User-Agent": UA})
            ir.raise_for_status()
            data = ir.content
            mime = ir.headers.get("Content-Type", "image/jpeg").split(";")[0].strip()
            if len(data) > 1_000_000:
                warn("link card: og:image is %d bytes, over 1 MB; card without a thumbnail" % len(data))
            elif not mime.startswith("image/"):
                warn("link card: og:image is %s, not an image; card without a thumbnail" % mime)
            else:
                blob = call("POST", pds + "/xrpc/com.atproto.repo.uploadBlob", "BSKY_APP_PASSWORD",
                            data=data, headers={"Authorization": "Bearer " + jwt, "Content-Type": mime})
                if blob.get("blob"):
                    external["thumb"] = blob["blob"]
        except (requests.RequestException, PostError) as e:
            warn("link card: thumbnail skipped (%s)" % redact(e))
    else:
        warn("link card: page has no og:image; card without a thumbnail")
    return {"$type": "app.bsky.embed.external", "external": external}


def bluesky(post, dry, warn):
    require(post, "bluesky", ["text"])
    text = post["text"]
    check_len("bluesky text (graphemes)", text, 300, graphemes)
    facets = bsky_facets(text)
    urls = [f["features"][0]["uri"] for f in facets]
    pds = (os.environ.get("BSKY_PDS") or "https://bsky.social").rstrip("/")
    if dry:
        return dry_out("POST %s/xrpc/com.atproto.server.createSession, uploadBlob, createRecord" % pds,
                       {"text": text, "graphemes": graphemes(text), "facets": facets,
                        "embed": ("app.bsky.embed.external built from og: tags of %s" % urls[0]) if urls else None})
    handle, password = need_env("bluesky")
    sess = call("POST", pds + "/xrpc/com.atproto.server.createSession", "BSKY_APP_PASSWORD",
                json={"identifier": handle, "password": password})
    jwt, did = sess.get("accessJwt"), sess.get("did")
    if not jwt or not did:
        raise PostError("createSession returned no session")
    record = {
        "$type": "app.bsky.feed.post",
        "text": text,
        "createdAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
        "langs": ["en"],
    }
    if facets:
        record["facets"] = facets
        embed = bsky_embed(urls[0], pds, jwt, warn)
        if embed:
            record["embed"] = embed
    res = call("POST", pds + "/xrpc/com.atproto.repo.createRecord", "BSKY_APP_PASSWORD",
               json={"repo": did, "collection": "app.bsky.feed.post", "record": record},
               headers={"Authorization": "Bearer " + jwt})
    uri = res.get("uri") or ""
    rkey = uri.rsplit("/", 1)[-1] if uri else None
    if not rkey:
        raise PostError("no uri in response: %s" % res)
    return "https://bsky.app/profile/%s/post/%s" % (sess.get("handle") or handle, rkey)


# --------------------------------------------------------------------------- pinterest

def pinterest(post, dry, warn):
    require(post, "pinterest", ["title", "description", "link", "image_url", "board"])
    check_len("pinterest title", post["title"], 100)
    check_len("pinterest description", post["description"], 500)
    check_len("pinterest alt_text", post.get("alt_text") or "", 500)
    api = (os.environ.get("PINTEREST_API") or "https://api.pinterest.com").rstrip("/")
    board = post["board"].strip()
    pin = {
        "title": post["title"],
        "description": post["description"],
        "link": post["link"],
        "media_source": {"source_type": "image_url", "url": post["image_url"]},
    }
    if post.get("alt_text"):
        pin["alt_text"] = post["alt_text"]
    if dry:
        return dry_out("GET %s/v5/boards (find or create '%s'), POST %s/v5/pins" % (api, board, api),
                       dict(pin, board_id="<id of '%s'>" % board))
    (token,) = need_env("pinterest")
    h = {"Authorization": "Bearer " + token}
    board_id = None
    bookmark = None
    while True:
        params = {"page_size": 100}
        if bookmark:
            params["bookmark"] = bookmark
        page = call("GET", api + "/v5/boards", "PINTEREST_TOKEN", params=params, headers=h)
        for b in page.get("items") or []:
            if (b.get("name") or "").strip().lower() == board.lower():
                board_id = b.get("id")
                break
        bookmark = page.get("bookmark")
        if board_id or not bookmark:
            break
    if not board_id:
        created = call("POST", api + "/v5/boards", "PINTEREST_TOKEN", headers=h,
                       json={"name": board, "privacy": "PUBLIC"})
        board_id = created.get("id")
        if not board_id:
            raise PostError("could not create board '%s': %s" % (board, created))
        warn("created board '%s'" % board)
    res = call("POST", api + "/v5/pins", "PINTEREST_TOKEN", headers=h, json=dict(pin, board_id=board_id))
    pid = res.get("id")
    if not pid:
        raise PostError("no pin id: %s" % res)
    return "https://www.pinterest.com/pin/%s/" % pid


# --------------------------------------------------------------------------- main

HANDLERS = {"facebook": facebook, "instagram": instagram, "threads": threads,
            "x": x, "bluesky": bluesky, "pinterest": pinterest}


# --------------------------------------------------------------------------- discord

def discord(post, dry, warn):
    require(post, "discord", ["text"])
    content = post["text"]
    if post.get("link") and post["link"] not in content:
        content = content.rstrip() + "\n" + post["link"]
    check_len("discord text", content, 2000)
    payload = {"content": content, "allowed_mentions": {"parse": []}}
    if dry:
        return dry_out("POST {DISCORD_ANNOUNCE_WEBHOOK}?wait=true", payload)
    (hook,) = need_env("discord")
    res = call("POST", hook + ("&" if "?" in hook else "?") + "wait=true", "DISCORD_ANNOUNCE_WEBHOOK",
               json=payload)
    return "discord message %s" % (res.get("id") if isinstance(res, dict) else "posted")


HANDLERS["discord"] = discord


def self_test():
    """Offline: inherited credentials are dropped and only the FT8AF file is read."""
    import tempfile
    os.environ["META_PAGE_TOKEN"] = "inherited-from-another-product"
    os.environ["X_API_KEY"] = "inherited"
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "social.env")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("# comment\nMETA_PAGE_ID=123\nMETA_PAGE_TOKEN='ft8af-token'\n")
        os.environ["FT8AF_SOCIAL_ENV"] = path
        assert load_dotenv() == path
        assert os.environ.get("META_PAGE_TOKEN") == "ft8af-token", "file value not loaded"
        assert "X_API_KEY" not in os.environ, "inherited credential survived"
        os.environ["FT8AF_SOCIAL_ENV"] = os.path.join(tmp, "missing.env")
        load_dotenv()
        assert "META_PAGE_TOKEN" not in os.environ, "missing file must leave no credentials"
    out = discord({"text": "New in FT8AF", "link": "https://ft8af.app/blog/x"}, True, print)
    assert "https://ft8af.app/blog/x" in out and '"parse": []' in out, out
    print("post_socials self-test ok")


def main():
    ap = argparse.ArgumentParser(description="Publish FT8AF social posts via the networks' APIs.")
    ap.add_argument("--posts", help="posts.json")
    ap.add_argument("--self-test", action="store_true", help="offline checks, posts nothing")
    ap.add_argument("--only", help="comma-separated networks, e.g. facebook,x")
    ap.add_argument("--dry-run", action="store_true", help="print what would be sent; call nothing")
    args = ap.parse_args()
    for stream in (sys.stdout, sys.stderr):  # Windows consoles default to cp1252; posts carry emoji
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    if args.self_test:
        return self_test()
    if not args.posts:
        ap.error("--posts is required")
    path = load_dotenv()
    print("credentials: %s%s" % (path, "" if os.path.isfile(path) else " (missing: every network will fail)"))
    with open(args.posts, encoding="utf-8-sig") as fh:
        posts = json.load(fh)
    if not isinstance(posts, dict):
        sys.exit("posts.json must be an object keyed by network")
    unknown = [k for k in posts if k not in NETWORKS]
    if unknown:
        print("ignoring unknown keys in posts.json: %s" % ", ".join(unknown))
    selected = [n for n in NETWORKS if n in posts]
    if args.only:
        only = [s.strip().lower() for s in args.only.split(",") if s.strip()]
        bad = [s for s in only if s not in NETWORKS]
        if bad:
            sys.exit("--only: unknown network(s) %s (choose from %s)" % (", ".join(bad), ", ".join(NETWORKS)))
        selected = [n for n in selected if n in only]
    if not selected:
        sys.exit("nothing to post: no matching networks in %s" % args.posts)

    failed = []
    for name in selected:
        warnings = []
        if args.dry_run:
            missing = [v for v in ENV[name] if not os.environ.get(v)]
            if missing:
                warnings.append("env not set: %s (a real run would fail)" % ", ".join(missing))
        try:
            result = HANDLERS[name](posts[name], args.dry_run, warnings.append)
            print("[ok]   %s: %s" % (name, result))
        except PostError as e:
            failed.append(name)
            print("[FAIL] %s: %s" % (name, redact(e)))
        except Exception as e:  # one network's bug must not stop the others
            failed.append(name)
            print("[FAIL] %s: unexpected %s: %s" % (name, e.__class__.__name__, redact(e)))
        for w in warnings:
            print("       %s: %s" % (name, w))
        sys.stdout.flush()
    if failed:
        print("\nfailed: %s" % ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
