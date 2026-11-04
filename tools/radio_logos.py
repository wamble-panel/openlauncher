"""Collects logo candidates for the Cairo FM stations (run in CI, which has open
internet): from each station's website (og:image, touch icons, <img> named logo),
Wikidata's official logo (P154) and Wikipedia's article picture, rendered as PNG.
Output on the radio-logos-data branch: out/img/<freq>__<source>_<n>.<ext> and
out/found.json. Nothing here is shipped as is: logos are checked by hand first."""
import html, json, os, re, time, urllib.parse, urllib.request

OUT = "out"
os.makedirs(f"{OUT}/img", exist_ok=True)
UA = {"User-Agent": "MMI-radio-logo-collector/1.1 (https://github.com/wamble-panel/openlauncher; car head-unit radio logos)"}

# Greater Cairo FM (Wikipedia, citing fmscan.org): frequency, names to search, websites.
STATIONS = [
    ("87.8", ["Mix FM Egypt"], ["https://www.mixfmegypt.com/"]),
    ("88.2", ["Radio Hits 88.2"], ["http://radiohits882.com/", "https://radiohits882.com/"]),
    ("88.7", ["Radio Masr", "راديو مصر"], ["https://radiomasr.net/", "https://www.radiomasr.net/"]),
    ("89.5", ["Middle East Radio", "إذاعة الشرق الأوسط"], []),
    ("90.9", ["El Radio 9090", "Radio 9090"], ["https://www.9090.fm/"]),
    ("91.5", ["Cultural Program Egypt", "البرنامج الثقافي"], []),
    ("92.1", ["NRJ Egypt"], ["https://www.nrjegypt.com/", "https://nrjegypt.com/"]),
    ("92.7", ["Mega FM Egypt", "ميجا إف إم"], ["https://megafm.com.eg/", "https://www.megafm.com.eg/", "https://megafm.net/"]),
    ("93.7", ["On Sport FM"], ["https://www.onsportfm.com/", "http://www.onsportfm.com/"]),
    ("95.0", ["Shaabi FM", "شعبي إف إم"], ["http://95-fm.com/en", "https://95-fm.com/"]),
    ("95.4", ["European Programme Egypt", "البرنامج الأوروبي"], []),
    ("98.2", ["Holy Quran Radio Cairo", "إذاعة القرآن الكريم (مصر)"], []),
    ("98.8", ["Musical Program Egypt", "البرنامج الموسيقي"], []),
    ("100.6", ["Nogoum FM", "نجوم إف إم"], ["https://www.nogoumfm.net/"]),
    ("102.2", ["Greater Cairo Radio", "إذاعة القاهرة الكبرى"], []),
    ("102.7", ["Nile News radio", "النيل للأخبار"], []),
    ("104.2", ["Nile FM"], ["https://nilefm.com/", "https://www.nilefm.com/"]),
    ("105.3", ["Nagham FM", "نغم إف إم"], ["https://naghamfm.com/", "https://www.naghamfm.com/", "https://naghamfm.net/"]),
    ("105.8", ["Egyptian Songs Radio", "إذاعة الأغاني"], []),
    ("106.3", ["Voice of the Arabs", "صوت العرب"], []),
]


def get(url, binary=False, limit=4_000_000):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
            data = r.read(limit + 1)
            if len(data) > limit:
                return None
            return data if binary else data.decode("utf-8", "replace")
    except Exception as e:
        print("fail", url[:120], e)
        return None


found = {}
count = {}


def save(freq, src, url):
    n = count.get(freq, 0)
    if n >= 14:
        return
    ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower()
    if ext not in (".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico"):
        ext = ".img"
    data = get(url, binary=True)
    time.sleep(0.4)
    if not data or len(data) < 800:
        return
    count[freq] = n + 1
    path = f"{OUT}/img/{freq}__{src}_{n}{ext}"
    open(path, "wb").write(data)
    found.setdefault(freq, []).append({"source": src, "url": url, "file": path})


def site_images(page_url):
    """og:image, twitter:image, touch / large icons and <img> whose src, alt or class says logo."""
    t = get(page_url)
    if not t:
        return []
    out = []
    for m in re.finditer(r"<meta[^>]+>", t, re.I):
        tag = m.group(0)
        if re.search(r"(og:image|twitter:image)", tag, re.I):
            c = re.search(r'content=["\']([^"\']+)', tag, re.I)
            if c:
                out.append(c.group(1))
    for m in re.finditer(r"<link[^>]+>", t, re.I):
        tag = m.group(0)
        if re.search(r'rel=["\'][^"\']*(apple-touch-icon|icon)', tag, re.I):
            h = re.search(r'href=["\']([^"\']+)', tag, re.I)
            if h:
                out.append(h.group(1))
    for m in re.finditer(r"<img[^>]+>", t, re.I):
        tag = m.group(0)
        if re.search(r"logo", tag, re.I):
            s = re.search(r'(?:data-src|src)=["\']([^"\']+)', tag, re.I)
            if s:
                out.append(s.group(1))
    urls = []
    for u in out:
        u = urllib.parse.urljoin(page_url, html.unescape(u.strip()))
        if u.startswith("http") and u not in urls:
            urls.append(u)
    return urls[:8]


def commons_png(filename, width=512):
    j = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url"
            f"&iiurlwidth={width}&titles=" + urllib.parse.quote("File:" + filename))
    try:
        ii = json.loads(j)["query"]["pages"][0]["imageinfo"][0]
        return ii.get("thumburl") or ii.get("url")
    except Exception:
        return None


def wikidata_logos(name, lang):
    j = get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&limit=4"
            f"&language={lang}&search=" + urllib.parse.quote(name))
    urls = []
    if not j:
        return urls
    for e in json.loads(j).get("search", []):
        c = get(f"https://www.wikidata.org/wiki/Special:EntityData/{e['id']}.json")
        if not c:
            continue
        claims = json.loads(c)["entities"][e["id"]].get("claims", {})
        for prop in ("P154", "P18"):
            for cl in claims.get(prop, []):
                v = cl.get("mainsnak", {}).get("datavalue", {}).get("value")
                if isinstance(v, str):
                    u = commons_png(v)
                    if u:
                        urls.append(u)
    return urls


def wiki_page_image(name, lang):
    j = get(f"https://{lang}.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
            "&prop=pageimages&piprop=thumbnail&pithumbsize=512&titles=" + urllib.parse.quote(name))
    try:
        return [json.loads(j)["query"]["pages"][0]["thumbnail"]["source"]]
    except Exception:
        return []


for freq, names, sites in STATIONS:
    for s in sites:
        for u in site_images(s):
            save(freq, "site", u)
    for n in names:
        lang = "ar" if re.search(r"[؀-ۿ]", n) else "en"
        for u in wikidata_logos(n, lang):
            save(freq, "wikidata", u)
        for u in wiki_page_image(n, lang):
            save(freq, f"wiki{lang}", u)
    print(freq, count.get(freq, 0))

json.dump(found, open(f"{OUT}/found.json", "w"), ensure_ascii=False, indent=1)
