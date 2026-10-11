"""给印迹 Android 生成一套用于官网截图的测试数据。

用法：python seed-device-data.py <db 路径> [图片目录]
图片目录里放 mint.jpg / coffee.jpg / bread.jpg，会按 entries 里的 media 键挂到对应印迹上。
思路：不起 WAL（journal_mode=DELETE），让整个库就是一个自包含的 .db 文件，可整体推回设备。
"""
import hashlib
import os
import sqlite3
import sys
import uuid

db_path = sys.argv[1]
media_dir = sys.argv[2] if len(sys.argv) > 2 else None

conn = sqlite3.connect(db_path)
conn.execute("PRAGMA journal_mode=DELETE")
cur = conn.cursor()

for t in ("entry_tags", "feelings", "visual_media", "audio", "entries"):
    cur.execute(f"DELETE FROM {t}")

now = 1786000000000  # 固定基准，避免每次生成都不同

# 三条印迹：一条带感受（刮卡截图用）、两条用于时间线分组与标签筛选
entries = [
    {
        "id": "seed-1",
        "body": "把窗台那盆快枯死的薄荷换到南边窗台，浇透水。三天后居然冒了两片新叶——原来它只是缺光，不是要死了。",
        "minute": "2026-10-09T19:42",
        "tags": ["personal"],
        "feeling": "有点想哭。原来很多东西不是不行，只是放错了位置。",
        "media": ["mint"],
    },
    {
        "id": "seed-2",
        "body": "楼下开了家很小的面包店，晚上七点后打八折。买了两个可颂，边走边吃，走到江边刚好吃完。",
        "minute": "2026-10-05T20:15",
        "tags": ["personal", "travel"],
        "feeling": None,
        "media": ["coffee"],
    },
    {
        "id": "seed-3",
        "body": "把这个月的账单和发票整理完，顺手清掉了三个早就不用的订阅。桌面终于能看见木头了。",
        "minute": "2026-09-28T22:03",
        "tags": ["work"],
        "feeling": None,
        "media": ["bread"],
    },
]

for index, item in enumerate(entries):
    cur.execute(
        "INSERT INTO entries (id, body, eventLocalMinute, eventZoneId, createdAtMs, updatedAtMs, deletedAtMs, placeId) "
        "VALUES (?,?,?,?,?,?,NULL,NULL)",
        (item["id"], item["body"], item["minute"], "Asia/Shanghai", now - index * 86400000, now - index * 86400000),
    )
    for tag_id in item["tags"]:
        cur.execute("INSERT OR REPLACE INTO entry_tags (entryId, tagId) VALUES (?,?)", (item["id"], tag_id))
    cur.execute(
        "INSERT OR REPLACE INTO entry_fts (entryId, body) VALUES (?,?)",
        (item["id"], item["body"]),
    )
    if item["feeling"]:
        # 明文模式：encoding=PLAIN，payload 就是原文（与 ImprintSecurity.encodeFeeling 一致）
        cur.execute(
            "INSERT OR REPLACE INTO feelings (entryId, encoding, payload) VALUES (?,?,?)",
            (item["id"], "PLAIN", item["feeling"]),
        )

# 媒体：每条印迹按 media 键挂对应图片。文件名必须是 36 位 UUID（MediaFiles.resolve 的要求），
# 宿主侧把 UUID 与图片的对应关系写在 media-map.txt 里，安装脚本据此落盘。
media_map = []
for item in entries:
    for ordinal, key in enumerate(item.get("media") or []):
        if not media_dir:
            break
        path = os.path.join(media_dir, f"{key}.jpg")
        if not os.path.exists(path):
            print(f"缺少图片：{path}")
            continue
        data = open(path, "rb").read()
        file_id = str(uuid.uuid4())
        media_map.append((file_id, f"{key}.jpg"))
        cur.execute(
            "INSERT OR REPLACE INTO visual_media "
            "(id, entryId, kind, ordinal, relativePath, mime, byteLength, sha256, createdAtMs, "
            " motionVideoPath, motionVideoSha256, motionVideoByteLength) "
            "VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
            (file_id, item["id"], "IMAGE", ordinal, file_id, "image/jpeg",
             len(data), hashlib.sha256(data).hexdigest(), now),
        )
        print(f"媒体: {item['id']} <- {key}.jpg  id={file_id}  {len(data)} bytes")

if media_map:
    with open(os.path.join(os.path.dirname(db_path), "media-map.txt"), "w", encoding="utf-8") as fh:
        for file_id, name in media_map:
            fh.write(f"{file_id} {name}\n")
    print(f"媒体映射已写出：{len(media_map)} 条")

conn.commit()
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
conn.commit()
conn.close()

# 校验
conn = sqlite3.connect(db_path)
cur = conn.cursor()
print("entries =", cur.execute("SELECT COUNT(*) FROM entries").fetchone()[0])
print("entry_tags =", cur.execute("SELECT COUNT(*) FROM entry_tags").fetchone()[0])
print("feelings =", cur.execute("SELECT COUNT(*) FROM feelings").fetchone()[0])
print("visual_media =", cur.execute("SELECT COUNT(*) FROM visual_media").fetchone()[0])
print("journal_mode =", cur.execute("PRAGMA journal_mode").fetchone()[0])
for row in cur.execute("SELECT id, eventLocalMinute, substr(body,1,18) FROM entries ORDER BY eventLocalMinute DESC"):
    print("  ", row)
conn.close()
print("db bytes =", os.path.getsize(db_path))
