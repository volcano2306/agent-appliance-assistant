"""Upsert only synthetic demo IDs. No dotenv reads or collection deletion."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=Path(__file__).resolve().parents[1] / 'data/demo-manuals.json')
    parser.add_argument('--uri', default='mongodb://localhost:27017/jiadian')
    parser.add_argument('--database', default='jiadian')
    args = parser.parse_args()
    from pymongo import MongoClient
    items = json.loads(args.data.read_text(encoding='utf-8'))['manuals']
    if any(not item['id'].startswith('demo-') for item in items):
        raise ValueError('only_demo_ids_allowed')
    now = datetime.now(timezone.utc)
    with MongoClient(args.uri, serverSelectionTimeoutMS=5000) as client:
        database = client[args.database]
        for item in items:
            pages = item['pages']
            manual_id = item['id']
            record = {key: value for key, value in item.items() if key not in {'id', 'pages'}}
            record.update(productId=manual_id, pages=len(pages), manualContent='\n\n'.join(pages),
                          isParsing=False, isParsed=True, viewCount=0, addedCount=0, updatedAt=now)
            database.manuals.update_one({'_id': manual_id}, {'$set': record, '$setOnInsert': {'createdAt': now}}, upsert=True)
            for number, text in enumerate(pages, 1):
                fragment_id = f'{manual_id}-p{number}'
                database.fragments.update_one({'_id': fragment_id}, {'$set': {
                    'manualId': manual_id, 'productId': manual_id, 'page': number,
                    'chunkIndex': number - 1, 'text': text, 'source': 'synthetic_demo',
                }}, upsert=True)
    print(f'demo_manuals={len(items)}; demo_fragments={sum(len(item["pages"]) for item in items)}')


if __name__ == '__main__':
    main()
