"""Collects Egyptian radio stations and logo candidates (run in CI, which has open
internet). Output in out/: wiki_en.txt / wiki_ar.txt (station list wikitext),
wiki_images.json (logo of every station article linked from the lists),
radiobrowser_eg.json (all Egyptian stations in radio-browser.info) and the
pictures themselves in out/img/. Nothing here is shipped as is: logos are checked
by hand before any is built into MMI."""
import json, os, re, urllib.parse, urllib.request

OUT = "out"
os.makedirs(f"{OUT}/img", exist_ok=True)
UA = {"User-Agent": "MMI-radio-logo-collector/1.0 (github.com/wamble-panel/openlauncher)"}


def get(url, binary=False, limit=3_000_000):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
            data = r.read(limit + 1)
            if len(data) > limit:
                return None
            return data if binary else data.decode("utf-8", "replace")
    except Exception as e:
        print("fail", url[:120], e)
        return None


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:60] or "x"


def save_img(url, name):
    ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower()
    if ext not in (".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico"):
        ext = ".img"
    data = get(url, binary=True)
    if not data or len(data) < 600:
        return None
    path = f"{OUT}/img/{name}{ext}"
    with open(path, "wb") as f:
        f.write(data)
    return path


meta = {}
# 1. Wikipedia station lists (English and Arabic) and the logo of every linked article.
for lang, title in [("en", "List_of_radio_stations_in_Egypt"), ("ar", "قائمة_محطات_الإذاعة_في_مصر")]:
    api = f"https://{lang}.wikipedia.org/w/api.php"
    txt = get(f"{api}?action=parse&page={urllib.parse.quote(title)}&prop=wikitext&format=json&formatversion=2")
    if txt:
        try:
            open(f"{OUT}/wiki_{lang}.txt", "w").write(json.loads(txt)["parse"]["wikitext"])
        except Exception as e:
            print("parse", lang, e)
    links = []
    cont = ""
    while True:
        j = get(f"{api}?action=query&titles={urllib.parse.quote(title)}&prop=links&pllimit=500&plnamespace=0&format=json&formatversion=2{cont}")
        if not j:
            break
        j = json.loads(j)
        for p in j["query"]["pages"]:
            links += [l["title"] for l in p.get("links", [])]
        if "continue" not in j:
            break
        cont = "&plcontinue=" + urllib.parse.quote(j["continue"]["plcontinue"])
    for i in range(0, len(links), 40):
        batch = "|".join(links[i:i + 40])
        j = get(f"{api}?action=query&titles={urllib.parse.quote(batch)}&prop=pageimages&piprop=original&format=json&formatversion=2")
        if not j:
            continue
        for p in json.loads(j)["query"]["pages"]:
            src = p.get("original", {}).get("source")
            if src:
                path = save_img(src, f"wiki_{lang}_{slug(p['title'])}")
                meta[f"wiki_{lang}:{p['title']}"] = {"url": src, "file": path}
json.dump(meta, open(f"{OUT}/wiki_images.json", "w"), ensure_ascii=False, indent=1)

# 2. Every Egyptian station in radio-browser.info, with its favicon.
rb = None
for host in ["de1", "de2", "fi1", "nl1", "at1"]:
    t = get(f"https://{host}.api.radio-browser.info/json/stations/bycountrycodeexact/EG?hidebroken=false&limit=1000")
    if t:
        rb = json.loads(t)
        break
rb = rb or []
keep = []
for s in rb:
    e = {k: s.get(k) for k in ("name", "url_resolved", "homepage", "favicon", "tags", "state", "language", "clickcount", "stationuuid")}
    if e["favicon"] and e["favicon"].startswith("http"):
        e["file"] = save_img(e["favicon"], f"rb_{slug(e['name'])}_{e['stationuuid'][:6]}")
    keep.append(e)
json.dump(keep, open(f"{OUT}/radiobrowser_eg.json", "w"), ensure_ascii=False, indent=1)
print("wiki images", len(meta), "radio-browser stations", len(keep))
