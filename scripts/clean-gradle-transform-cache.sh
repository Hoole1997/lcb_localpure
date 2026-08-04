#!/usr/bin/env bash

set -euo pipefail

# Gradle transform 只包含可重新生成的派生产物。Chartboost Mediation 5.3.0 会在 R8 期间
# 把 mapping 写回这里，破坏 immutable workspace；构建前统一清理，不能复用已污染目录。
gradle_user_home="${GRADLE_USER_HOME:-${HOME:?HOME is required}/.gradle}"
gradle_caches_dir="$gradle_user_home/caches"

if [[ ! -d "$gradle_caches_dir" ]]; then
    echo "No Gradle caches directory exists yet: $gradle_caches_dir"
    exit 0
fi

removed_count=0
while IFS= read -r -d '' transforms_dir; do
    # 防止未来修改 find 条件时扩大删除范围；只允许 caches/<Gradle version>/transforms。
    case "$transforms_dir" in
        "$gradle_caches_dir"/*/transforms)
            echo "Removing unsafe Gradle transform cache: $transforms_dir"
            rm -rf -- "$transforms_dir"
            removed_count=$((removed_count + 1))
            ;;
        *)
            echo "Refusing to remove unexpected path: $transforms_dir" >&2
            exit 1
            ;;
    esac
done < <(
    find "$gradle_caches_dir" \
        -mindepth 2 \
        -maxdepth 2 \
        -type d \
        -name transforms \
        -print0
)

echo "Removed $removed_count Gradle transform cache director$(
    if [[ "$removed_count" -eq 1 ]]; then
        printf 'y'
    else
        printf 'ies'
    fi
)."
