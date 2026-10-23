python3 generate_index.py
python3 download_bucket.py --output ../bucket/
python3 generate_index.py --mode public --base-url https://offline-translator.davidv.dev
cp app/src/main/assets/index_v7.json ../bucket/index_v7.json
echo 'sync bucket now'
