"""
Generates believable family demo data for store screenshots: list/diary JSON files in the app's storage
format, plus an identity prefs file per phone. Used by scripts/screenshots.sh.

usage: python demo_data.py OUT_DIR
Writes OUT_DIR/lists/*.json (same for both phones) and OUT_DIR/{me,sarah}/identity.xml.
"""
import base64
import datetime
import json
import os
import sys
import uuid

out = sys.argv[1]
now = int(datetime.datetime.now().timestamp() * 1000)
today = datetime.date.today()
MIN = 60_000

ME, SARAH, TOM = "demo-device-me", "demo-device-sarah", "demo-device-tom"
NAMES = {ME: "Me", SARAH: "Sarah", TOM: "Tom"}


def secret():
    # Fresh every run: a fixed key would put each run on the same relay topic, and the relays would
    # replay earlier runs' edits and members into the screenshots.
    return base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")


def describe(item):
    if item.get("date"):
        d = datetime.date.fromisoformat(item["date"])
        when = d.strftime("%a %-d %b") if os.name != "nt" else d.strftime("%a %#d %b")
        return f"added {item['text']} on {when}" + (f" at {item['time']}" if item.get("time") else "")
    return ("ticked " if item.get("checked") else "added ") + item["text"]


def make(name, kind, theme, entries):
    """entries: (text, by, minutes_ago, extra) — extra holds checked/date/time."""
    lid = str(uuid.uuid4())
    items, audit = {}, {}
    for pos, (text, by, ago, extra) in enumerate(entries):
        iid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"demo/{name}/{text}"))
        ts = now - ago * MIN
        item = {"id": iid, "text": text, "createdAt": ts - 30 * MIN, "version": {"ts": ts, "by": by},
                "editor": NAMES[by], "pos": float(pos + 1), "posVersion": {"ts": ts - 30 * MIN, "by": by}}
        item.update(extra)
        items[iid] = item
        aid = f"{iid}/{ts}/{by}"
        audit[aid] = {"id": aid, "itemId": iid, "ts": ts, "by": by, "byName": NAMES[by], "description": describe(item)}
    data = {"id": lid, "name": name, "secret": secret(), "items": items, "audit": audit,
            "members": {SARAH: "Sarah", TOM: "Tom"}, "createdHere": True, "fullSynced": True, "kind": kind}
    return data, theme


def day(offset):
    return (today + datetime.timedelta(days=offset)).isoformat()


lists = [
    make("Groceries", "list", None, [
        ("Milk", SARAH, 12, {"checked": True}),
        ("Sourdough bread", TOM, 40, {}),
        ("Bananas", SARAH, 9, {"checked": True}),
        ("Coffee beans", ME, 55, {}),
        ("Dishwasher tablets", TOM, 70, {}),
        ("Cheddar", ME, 5, {"checked": True}),
        ("Birthday candles", SARAH, 30, {}),
    ]),
    make("Family diary", "diary", "rose-pine-dawn", [
        ("School drop-off", TOM, 300, {"date": day(0), "time": "08:15"}),
        ("Swimming lessons", SARAH, 200, {"date": day(0), "time": "15:30"}),
        ("Pick up dry cleaning", ME, 150, {"date": day(0)}),
        ("Book club at ours", SARAH, 100, {"date": day(0), "time": "19:30"}),
        ("Dentist — Lily", SARAH, 600, {"date": day(1), "time": "09:30"}),
        ("Dinner at Nan's", TOM, 900, {"date": day(2), "time": "18:00"}),
        ("Football", TOM, 800, {"date": day(-1), "time": "17:00"}),
        ("Bins out", ME, 700, {"date": day(-2)}),
        ("Parent–teacher night", SARAH, 1000, {"date": day(4), "time": "18:30"}),
    ]),
    make("Holiday packing", "list", "nord", [
        ("Passports", SARAH, 300, {"checked": True}),
        ("Sunscreen", TOM, 200, {}),
        ("Phone chargers", ME, 100, {"checked": True}),
        ("Beach towels", SARAH, 90, {}),
        ("Travel adaptor", TOM, 60, {}),
    ]),
    make("Jobs around the house", "list", "gruvbox-light", [
        ("Fix the gate latch", TOM, 2000, {}),
        ("Clean the gutters", ME, 3000, {"checked": True}),
        ("Repaint the fence", SARAH, 4000, {}),
    ]),
]

os.makedirs(os.path.join(out, "lists"), exist_ok=True)
for who in ("me", "sarah"):
    os.makedirs(os.path.join(out, who, "lists"), exist_ok=True)
for (data, theme) in lists:
    for who in ("me", "sarah"):
        d = dict(data)
        if who == "me":
            d["themeId"] = theme  # themes are per phone; only the screenshot phone needs them
        else:
            d["members"] = {ME: "Me", TOM: "Tom"}
        with open(os.path.join(out, who, "lists", d["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(d, f)


def prefs(device_id, name, theme):
    return f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="deviceId">{device_id}</string>
    <string name="deviceName">{name}</string>
    <string name="theme">{theme}</string>
    <string name="knownNames">{json.dumps({k: v for k, v in NAMES.items() if k != device_id}).replace('"', '&quot;')}</string>
    <boolean name="backgroundSync" value="true" />
</map>
"""


with open(os.path.join(out, "me", "identity.xml"), "w", encoding="utf-8") as f:
    f.write(prefs(ME, "Me", "twodo-dark"))
with open(os.path.join(out, "sarah", "identity.xml"), "w", encoding="utf-8") as f:
    f.write(prefs(SARAH, "Sarah", "twodo-dark"))
print(f"wrote {len(lists)} lists for 2 phones to {out}")
