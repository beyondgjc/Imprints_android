"""给印迹 Android 生成一套用于官网截图的测试数据。

用法：python seed-device-data.py <db 路径> [图片目录]
图片目录里放 mint.jpg / coffee.jpg / bread.jpg，会按 entries 里的 media 键挂到对应印迹上。
思路：不起 WAL（journal_mode=DELETE），让整个库就是一个自包含的 .db 文件，可整体推回设备。
"""
import hashlib
import os
import sqlite3
import sys
import time
import uuid

db_path = sys.argv[1]
media_dir = sys.argv[2] if len(sys.argv) > 2 else None

conn = sqlite3.connect(db_path)
conn.execute("PRAGMA journal_mode=DELETE")
cur = conn.cursor()

for t in ("entry_tags", "feelings", "visual_media", "audio", "entries"):
    cur.execute(f"DELETE FROM {t}")

# 必须是"当前时间"：回收站的回收保留期按 deletedAtMs 判定，
# 用固定基准会让条目看起来是几个月前删的，一启动就被清理任务删掉。
now = int(time.time() * 1000)

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
    # 更早月份的印迹：让「时间穿梭」月历上有多个绿点
    {
        "id": "seed-04",
        "body": "把去年的日历撕下来，换上新的一本。第一页空着，看着挺舒服。",
        "minute": "2026-01-06T09:12",
        "tags": ["personal"],
        "feeling": None,
    },
    {
        "id": "seed-05",
        "body": "楼下的玉兰先开了两朵，其余的还在憋着。",
        "minute": "2026-03-11T17:40",
        "tags": ["personal", "travel"],
        "feeling": None,
    },
    {
        "id": "seed-06",
        "body": "第一次自己修好了漏水的水龙头，拧紧那一刻特别有成就感。",
        "minute": "2026-05-17T21:05",
        "tags": ["personal"],
        "feeling": None,
    },
    {
        "id": "seed-07",
        "body": "连着下了三天雨，阳台的衣服一直没干透。",
        "minute": "2026-07-22T08:30",
        "tags": ["personal"],
        "feeling": None,
    },
    {
        "id": "seed-08",
        "body": "把用了六年的旧手机里的照片导出来，一万多张，翻了一晚上。",
        "minute": "2026-08-14T23:18",
        "tags": ["personal", "work"],
        "feeling": None,
    },
]

# 回收站条目：删除时间错开，让「剩余 N 天」有层次（约 1 / 3 / 6 天前删的）
recycled = [
    ("trash-1", "买错型号的数据线，退货运费比线还贵，算了。", "2026-10-10T14:20", now - 1 * 86400000),
    ("trash-2", "记错的开会时间，白跑一趟公司。", "2026-10-08T09:00", now - 3 * 86400000),
    ("trash-3", "草稿：本来想写点什么，写到一半忘了要说啥。", "2026-10-05T22:40", now - 6 * 86400000),
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
    if item.get("feeling"):
        # 明文模式：encoding=PLAIN，payload 就是原文（与 ImprintSecurity.encodeFeeling 一致）
        cur.execute(
            "INSERT OR REPLACE INTO feelings (entryId, encoding, payload) VALUES (?,?,?)",
            (item["id"], "PLAIN", item["feeling"]),
        )

# 回收站：deletedAtMs 非空即视为在回收站，附件与标签都可不带
for index, (eid, body, minute, deleted_at) in enumerate(recycled):
    cur.execute(
        "INSERT OR REPLACE INTO entries (id, body, eventLocalMinute, eventZoneId, createdAtMs, updatedAtMs, deletedAtMs, placeId) "
        "VALUES (?,?,?,?,?,?,?,NULL)",
        (eid, body, minute, "Asia/Shanghai", deleted_at - 3600000, deleted_at - 3600000, deleted_at),
    )
    cur.execute("INSERT OR REPLACE INTO entry_fts (entryId, body) VALUES (?,?)", (eid, body))

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
print("  活跃   =", cur.execute("SELECT COUNT(*) FROM entries WHERE deletedAtMs IS NULL").fetchone()[0])
print("  回收站 =", cur.execute("SELECT COUNT(*) FROM entries WHERE deletedAtMs IS NOT NULL").fetchone()[0])
print("月份分布 =", [r[0] for r in cur.execute(
    "SELECT DISTINCT substr(eventLocalMinute,1,7) FROM entries WHERE deletedAtMs IS NULL ORDER BY 1")])
print("entry_tags =", cur.execute("SELECT COUNT(*) FROM entry_tags").fetchone()[0])
print("feelings =", cur.execute("SELECT COUNT(*) FROM feelings").fetchone()[0])
print("visual_media =", cur.execute("SELECT COUNT(*) FROM visual_media").fetchone()[0])
print("journal_mode =", cur.execute("PRAGMA journal_mode").fetchone()[0])
for row in cur.execute("SELECT id, eventLocalMinute, substr(body,1,18) FROM entries ORDER BY eventLocalMinute DESC"):
    print("  ", row)
conn.close()
print("db bytes =", os.path.getsize(db_path))
