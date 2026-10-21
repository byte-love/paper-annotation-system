#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 DataHub 平台的数据库目录导入 journal-system 的 datahub_databases 集合。

源：203.0.113.10 的 DataHub.SystemList（227 条，只读）
目标：127.0.0.1 的 journal_papers / journal_papers_test 的 datahub_databases

用法：
    python import-datahub-catalog.py                  # 试算，不写入
    python import-datahub-catalog.py --test           # 只写测试库
    python import-datahub-catalog.py --prod           # 只写正式库
    python import-datahub-catalog.py --test --prod    # 两个都写
    python import-datahub-catalog.py --prod --published-only   # 只导已发布的

安全设计：
  - 源库只读，绝不写入 DataHub 平台
  - 目标用 _id=数据库ID 做 upsert，不清空集合。重复跑不产副本，
    也不会出现"清空后写入失败导致目录留空"的窗口
  - 不复制 SystemList 里的 ConString / ConStringTest（含明文数据库密码）
  - 导完拿论文实际在用的 ID 反向校验目录完整性
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '.pmlegacy'))
sys.stdout.reconfigure(encoding='utf-8')

import pymongo  # noqa: E402  (pymongo 3.13，兼容源库的 MongoDB 4.0)

# 源库连接串从环境变量读取，不入代码库：
#   DEMO_SRC_URI='mongodb://用户:密码@203.0.113.10:27017/?authSource=admin'
SRC_URI = os.environ.get('DEMO_SRC_URI', '')
SRC_DB, SRC_COLL = 'DataHub', 'SystemList'

DST_URI = 'mongodb://127.0.0.1:27017/'
DST_COLL = 'datahub_databases'
DST_DBS = {'test': 'journal_papers_test', 'prod': 'journal_papers'}

# 绝不复制到目标库的字段（含明文数据库连接密码）
SECRET_FIELDS = {'ConString', 'ConStringTest'}


def fetch_source(published_only):
    """只读拉取目录，转成 journal-system 的 DataHubDatabase 结构"""
    if not SRC_URI:
        print('错误: 未设置环境变量 DEMO_SRC_URI')
        print("用法: DEMO_SRC_URI='mongodb://用户:密码@主机:27017/?authSource=admin' \\")
        print('        python import-datahub-catalog.py --prod')
        sys.exit(1)
    src = pymongo.MongoClient(SRC_URI, serverSelectionTimeoutMS=20000)
    docs = list(src[SRC_DB][SRC_COLL].find())
    print(f'源 {SRC_DB}.{SRC_COLL}: 读到 {len(docs)} 条')

    pub_true = sum(1 for d in docs if d.get('IsPublish') in (True, 'True', 'true'))
    print(f'  IsPublish=True {pub_true} 条，其余 {len(docs) - pub_true} 条')

    rows, skipped = [], []
    for d in docs:
        db_id = (d.get('ID') or d.get('_id') or '')
        db_id = str(db_id).strip()
        name = (d.get('Name') or '').strip()
        if not db_id or not name:
            skipped.append(db_id or '<无ID>')
            continue
        if published_only and d.get('IsPublish') not in (True, 'True', 'true'):
            skipped.append(db_id + '(未发布)')
            continue

        orders = d.get('Orders')
        try:
            orders = int(orders) if orders is not None else None
        except (TypeError, ValueError):
            orders = None

        # 只取展示需要的字段，SECRET_FIELDS 一律不带
        rows.append({
            '_id': db_id,
            'partition': (d.get('Partition') or '未分组').strip(),
            'name': name,
            'orders': orders,
            'nameEn': (d.get('NameEn') or '').strip() or None,
            'type': (d.get('Type') or '').strip() or None,
        })

    if skipped:
        print(f'  跳过 {len(skipped)} 条: {skipped[:8]}')

    parts = {}
    for r in rows:
        parts[r['partition']] = parts.get(r['partition'], 0) + 1
    print(f'  待导入 {len(rows)} 条，分属 {len(parts)} 个板块:')
    for p, n in sorted(parts.items(), key=lambda kv: -kv[1]):
        print(f'      {p} : {n}')

    assert not any(k in r for r in rows for k in SECRET_FIELDS), '出现了敏感字段，已中止'
    return rows


def load_inuse_ids():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        '.idscan', 'ids_in_use.txt')
    if not os.path.exists(path):
        return set()
    with open(path, encoding='utf-8') as f:
        return {x.strip() for x in f.read().split('\n') if x.strip()}


def verify(rows):
    """拿论文实际在用的 ID 反向校验目录完整性"""
    inuse = load_inuse_ids()
    if not inuse:
        print('\n[跳过校验] 没找到 .idscan/ids_in_use.txt')
        return
    ids = {r['_id'] for r in rows}
    missing = sorted(inuse - ids)
    unused = sorted(ids - inuse)
    print(f'\n完整性校验：论文在用 {len(inuse)} 个 ID')
    if missing:
        print(f'  [警告] {len(missing)} 个在用 ID 不在目录里: {missing}')
        print('         这些论文的关联数据库仍会显示原始 ID，需要查源库为什么缺')
    else:
        print('  在用 ID 全部能在目录里找到')
    print(f'  目录里另有 {len(unused)} 个尚未被任何论文用到（正常，标注时可选）')


def write_target(env, rows, dry_run):
    db_name = DST_DBS[env]
    dst = pymongo.MongoClient(DST_URI, serverSelectionTimeoutMS=20000)
    col = dst[db_name][DST_COLL]
    before = col.count_documents({})

    if dry_run:
        print(f'  [试算] {db_name}.{DST_COLL} 现有 {before} 条，将 upsert {len(rows)} 条')
        return

    inserted = updated = 0
    for r in rows:
        res = col.update_one({'_id': r['_id']}, {'$set': r}, upsert=True)
        if res.upserted_id is not None:
            inserted += 1
        elif res.modified_count:
            updated += 1

    after = col.count_documents({})
    print(f'  {db_name}.{DST_COLL}: {before} -> {after} 条'
          f'（新增 {inserted}，更新 {updated}）')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--test', action='store_true', help='写入测试库')
    ap.add_argument('--prod', action='store_true', help='写入正式库')
    ap.add_argument('--published-only', action='store_true',
                    help='只导 IsPublish=True 的（默认全导）')
    args = ap.parse_args()

    targets = [e for e in ('test', 'prod') if getattr(args, e)]
    dry_run = not targets

    print('=' * 64)
    print('DataHub 数据库目录导入' + ('（试算，不写入）' if dry_run else ''))
    print('=' * 64)

    rows = fetch_source(args.published_only)
    if not rows:
        print('没有可导入的数据，中止')
        return 1

    verify(rows)

    print()
    for env in (targets or ['test', 'prod']):
        write_target(env, rows, dry_run)

    if dry_run:
        print('\n以上为试算。加 --test 写测试库，加 --prod 写正式库。')
    else:
        print('\n完成。刷新论文列表，关联数据库列应显示中文名。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
