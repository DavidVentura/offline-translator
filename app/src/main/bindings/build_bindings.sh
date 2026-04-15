set -exu
cd $(dirname $0)
renice -n 19 $$
cargo build --target-dir bindings_tgt --no-default-features
./bindings_tgt/debug/uniffi-bindgen generate --library ./bindings_tgt/debug/libbindings.so --language kotlin --out-dir ../java/ --no-format
