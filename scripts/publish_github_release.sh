#!/usr/bin/env bash
set -euo pipefail

GITHUB_REPO="${GITHUB_REPO:-${OWNER_REPO:-clip-master-2/ClipMaster-Releases}}"
GITEE_REPO="${GITEE_REPO:-clip-master-2/clip-master-releases}"
CHANNEL="${CHANNEL:-internal}"
PACKAGE_NAME="${PACKAGE_NAME:-com.cla.clip.master}"
RELEASE_NOTES="${RELEASE_NOTES:-}"
FORCE_UPDATE="${FORCE_UPDATE:-false}"
MIN_SUPPORTED_VERSION_CODE="${MIN_SUPPORTED_VERSION_CODE:-}"
DRY_RUN="${DRY_RUN:-false}"
SKIP_GITHUB="${SKIP_GITHUB:-false}"
SKIP_GITEE="${SKIP_GITEE:-false}"
SELF_TEST_JSON_ERRORS="${SELF_TEST_JSON_ERRORS:-false}"
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${BASE_DIR}/build/github-gitee-release"
LOCAL_PROPERTIES_FILE="${LOCAL_PROPERTIES_FILE:-${BASE_DIR}/local.properties}"
USER_GRADLE_PROPERTIES_FILE="${USER_GRADLE_PROPERTIES_FILE:-${HOME}/.gradle/gradle.properties}"
# curl 建连超时时间；GitHub 在部分网络环境下可能卡在 TLS 握手，限制等待时间便于进入重试。
CURL_CONNECT_TIMEOUT_SECONDS="${CURL_CONNECT_TIMEOUT_SECONDS:-20}"
# curl 网络错误重试次数；只用于没有拿到 HTTP 响应的连接层失败，避免把接口 4xx 伪装成可重试问题。
CURL_RETRY_COUNT="${CURL_RETRY_COUNT:-3}"
# curl 网络错误重试间隔；给 GitHub/Gitee 短暂网络抖动留出恢复时间。
CURL_RETRY_DELAY_SECONDS="${CURL_RETRY_DELAY_SECONDS:-3}"

# 打印命令行帮助，避免发布脚本参数越来越多后使用者只能读源码。
usage() {
    cat <<'USAGE'
用法：
  scripts/publish_github_release.sh [选项]

选项：
  --github-repo owner/repo           GitHub 发布仓库，默认 clip-master-2/ClipMaster-Releases
  --gitee-repo owner/repo            Gitee 发布仓库，默认 clip-master-2/clip-master-releases
  --repo owner/repo                  兼容旧参数，等同 --github-repo
  --channel internal                 update.json 渠道，默认 internal
  --package-name com.example.app     update.json packageName，默认 com.cla.clip.master
  --notes "发布说明"                  Release notes；不传则用 changelog 生成
  --force-update true|false          update.json forceUpdate，默认 false
  --min-supported-version-code N     update.json minSupportedVersionCode；不传则等于本次 versionCode
  --skip-github                      只发布到 Gitee
  --skip-gitee                       只发布到 GitHub
  --dry-run                          只构建并生成文件，不调用 GitHub/Gitee API
  --self-test-json-errors            仅测试脚本 JSON 错误提示，不构建、不上传
  -h, --help                         显示帮助

环境变量：
  GITHUB_TOKEN                       GitHub token，需要 Contents: Read and write 权限；优先级最高
  GITEE_TOKEN                        Gitee 私人令牌，需要目标仓库 Release/附件写入权限；优先级最高
  GITHUB_REPO                        等同 --github-repo
  OWNER_REPO                         兼容旧变量，未设置 GITHUB_REPO 时等同 --github-repo
  GITEE_REPO                         等同 --gitee-repo
  CHANNEL                            等同 --channel
  PACKAGE_NAME                       等同 --package-name
  RELEASE_NOTES                      等同 --notes
  FORCE_UPDATE                       等同 --force-update
  MIN_SUPPORTED_VERSION_CODE         等同 --min-supported-version-code
  SKIP_GITHUB=true                   等同 --skip-github
  SKIP_GITEE=true                    等同 --skip-gitee
  DRY_RUN=true                       等同 --dry-run
  CURL_RETRY_COUNT=3                 curl 网络错误重试次数，默认 3
  CURL_RETRY_DELAY_SECONDS=3         curl 网络错误重试间隔秒数，默认 3
  CURL_CONNECT_TIMEOUT_SECONDS=20    curl 建连超时秒数，默认 20

本地配置：
  推荐在用户级 ~/.gradle/gradle.properties 中添加：
  githubToken=<token>
  giteeToken=<token>
  脚本也兼容读取项目 local.properties。
  不要把真实 token 放进项目 gradle.properties，因为它会随代码上传。

脚本会生成并上传：
  app/build/outputs/apk/release/ClipMaster-v版本.apk
  build/github-gitee-release/update.json
  build/github-gitee-release/sha256.txt
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --github-repo)
            GITHUB_REPO="$2"
            shift 2
            ;;
        --gitee-repo)
            GITEE_REPO="$2"
            shift 2
            ;;
        --repo)
            GITHUB_REPO="$2"
            shift 2
            ;;
        --channel)
            CHANNEL="$2"
            shift 2
            ;;
        --package-name)
            PACKAGE_NAME="$2"
            shift 2
            ;;
        --notes)
            RELEASE_NOTES="$2"
            shift 2
            ;;
        --force-update)
            FORCE_UPDATE="$2"
            shift 2
            ;;
        --min-supported-version-code)
            MIN_SUPPORTED_VERSION_CODE="$2"
            shift 2
            ;;
        --skip-github)
            SKIP_GITHUB="true"
            shift
            ;;
        --skip-gitee)
            SKIP_GITEE="true"
            shift
            ;;
        --dry-run)
            DRY_RUN="true"
            shift
            ;;
        --self-test-json-errors)
            SELF_TEST_JSON_ERRORS="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# 检查必需命令是否可用，缺失时尽早失败，避免走到发布中途才报错。
require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "缺少命令：$1" >&2
        exit 1
    fi
}

# 从 Java properties 风格文件中读取指定 key，兼容 =、: 和空白分隔三种写法。
read_property_file() {
    local path="$1"
    local key="$2"
    if [[ ! -f "$path" ]]; then
        return 0
    fi
    python3 - "$path" "$key" <<'PY'
import sys

path = sys.argv[1]
target = sys.argv[2]

with open(path, encoding="utf-8") as f:
    for raw_line in f:
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        key = None
        value = None
        for separator in ("=", ":"):
            if separator in line:
                key, value = line.split(separator, 1)
                break
        if key is None:
            parts = line.split(None, 1)
            if len(parts) == 2:
                key, value = parts
        if key is not None and key.strip() == target:
            print(value.strip())
            break
PY
}

# 判断某个 properties 文件里是否声明了指定 key。
property_exists() {
    local path="$1"
    local key="$2"
    [[ -n "$(read_property_file "$path" "$key")" ]]
}

# 按用户级 gradle.properties 优先、项目 local.properties 兜底的顺序读取 token。
read_token_from_properties() {
    local key="$1"
    local token
    for file in "$USER_GRADLE_PROPERTIES_FILE" "$LOCAL_PROPERTIES_FILE"; do
        token="$(read_property_file "$file" "$key")"
        if [[ -n "$token" ]]; then
            printf '%s\n' "$token"
            return 0
        fi
    done
}

# 把 owner/repo 形式的仓库标识拆成两个变量；格式不合法时立即终止。
split_owner_repo() {
    local value="$1"
    local owner_var="$2"
    local repo_var="$3"
    if [[ "$value" != */* || "$value" == */ || "$value" == /* ]]; then
        echo "仓库格式必须是 owner/repo：${value}" >&2
        exit 2
    fi
    printf -v "$owner_var" '%s' "${value%%/*}"
    printf -v "$repo_var" '%s' "${value#*/}"
}

# 规范化输出 GitHub API 错误，方便从 CI 或本地终端快速定位权限、重复附件和 tag 问题。
print_github_error() {
    local context="$1"
    local status="$2"
    local body="$3"
    python3 - "$context" "$status" "$body" <<'PY'
import json
import sys

context = sys.argv[1]
status = sys.argv[2]
body = sys.argv[3].strip()

print(f"GitHub API 请求失败：{context}", file=sys.stderr)
print(f"HTTP 状态码：{status}", file=sys.stderr)
if not body:
    print("响应体为空", file=sys.stderr)
    sys.exit(0)
try:
    payload = json.loads(body)
except json.JSONDecodeError:
    print("响应体：", file=sys.stderr)
    print(body[:2000], file=sys.stderr)
    sys.exit(0)
message = payload.get("message")
if message:
    print(f"message: {message}", file=sys.stderr)
for error in payload.get("errors") or []:
    if isinstance(error, dict):
        parts = []
        for key in ("resource", "field", "code", "message"):
            value = error.get(key)
            if value:
                parts.append(f"{key}={value}")
        if parts:
            print("error: " + ", ".join(parts), file=sys.stderr)
    else:
        print(f"error: {error}", file=sys.stderr)
documentation_url = payload.get("documentation_url")
if documentation_url:
    print(f"documentation_url: {documentation_url}", file=sys.stderr)
PY
    if [[ "$status" == "422" ]]; then
        cat >&2 <<'HINT'
常见 422 原因：
  - 当前 tag 对应的 Release 已存在，但创建接口没有复用到它。
  - Release 里已有同名附件，或上一次上传残留了同名附件。
  - fine-grained token 没有选中目标仓库，或缺少 Contents: Read and write 权限。
  - 仓库为空、默认分支、tag 或 release 状态与本次请求不匹配。
HINT
    fi
}

# 规范化输出 Gitee API 错误，尽量把 message 和字段错误提取成人能直接处理的信息。
print_gitee_error() {
    local context="$1"
    local status="$2"
    local body="$3"
    python3 - "$context" "$status" "$body" <<'PY'
import json
import sys

context = sys.argv[1]
status = sys.argv[2]
body = sys.argv[3].strip()

print(f"Gitee API 请求失败：{context}", file=sys.stderr)
print(f"HTTP 状态码：{status}", file=sys.stderr)
if not body:
    print("响应体为空", file=sys.stderr)
    sys.exit(0)
try:
    payload = json.loads(body)
except json.JSONDecodeError:
    print("响应体：", file=sys.stderr)
    print(body[:2000], file=sys.stderr)
    sys.exit(0)
for key in ("message", "error", "error_description"):
    value = payload.get(key)
    if value:
        print(f"{key}: {value}", file=sys.stderr)
PY
}

# 判断 curl 退出码是否属于可安全重试的连接层错误。
curl_exit_code_should_retry() {
    # 当前 curl 退出码；只对 DNS、连接、超时和 TLS 握手阶段失败做自动重试。
    local exit_code="$1"

    case "$exit_code" in
        5|6|7|28|35)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# 执行 curl 并对连接层失败做有限重试，保持 stdout 仍只返回 curl 的原始输出。
run_curl_with_retry() {
    # 当前尝试次数；第一次执行不算重试，用于日志展示当前进度。
    local attempt=1
    # 最大尝试次数；等于首次请求加配置的重试次数。
    local max_attempts=$((CURL_RETRY_COUNT + 1))
    # 本次 curl 退出码；上层依赖该值判断是否属于网络请求失败。
    local exit_code=0
    # curl stdout 内容；当前脚本用它接收 HTTP 状态码，不能混入重试日志。
    local output=""

    while true; do
        output="$("$@")"
        exit_code=$?
        if [[ "$exit_code" -eq 0 ]]; then
            printf '%s' "$output"
            return 0
        fi
        if (( attempt >= max_attempts )) || ! curl_exit_code_should_retry "$exit_code"; then
            printf '%s' "$output"
            return "$exit_code"
        fi
        echo "curl 网络请求失败，${CURL_RETRY_DELAY_SECONDS}s 后重试（${attempt}/${CURL_RETRY_COUNT}），退出码：${exit_code}" >&2
        sleep "$CURL_RETRY_DELAY_SECONDS"
        attempt=$((attempt + 1))
    done
}

# 发送 GitHub API 请求并把响应体/状态码写回全局变量，便于上层统一判断成功或失败。
github_request() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local body_file
    local status
    local curl_status

    body_file="$(mktemp)"
    if [[ -n "$data" ]]; then
        # GitHub JSON 请求体临时文件；Windows Git Bash 调用 curl.exe 时，中文 JSON 经 argv 传递可能被转码。
        local data_file
        data_file="$(mktemp)"
        printf '%s' "$data" > "$data_file"
        set +e
        status="$(
            run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X "$method" \
            -H "Accept: application/vnd.github+json" \
            -H "Authorization: Bearer ${GITHUB_TOKEN}" \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            -H "Content-Type: application/json" \
            "$url" \
            --data-binary "@${data_file}"
        )"
        curl_status=$?
        set -e
        rm -f "$data_file"
    else
        set +e
        status="$(
            run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X "$method" \
            -H "Accept: application/vnd.github+json" \
            -H "Authorization: Bearer ${GITHUB_TOKEN}" \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            "$url"
        )"
        curl_status=$?
        set -e
    fi

    GITHUB_RESPONSE_BODY="$(cat "$body_file")"
    GITHUB_RESPONSE_STATUS="$status"
    rm -f "$body_file"

    if [[ "$curl_status" -ne 0 ]]; then
        echo "GitHub API 网络请求失败：${method} ${url}" >&2
        return "$curl_status"
    fi
}

# 调用 GitHub API 并在非 2xx 时输出格式化错误后返回失败。
github_api() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    github_request "$method" "$url" "$data"
    if [[ "$GITHUB_RESPONSE_STATUS" -lt 200 || "$GITHUB_RESPONSE_STATUS" -ge 300 ]]; then
        print_github_error "${method} ${url}" "$GITHUB_RESPONSE_STATUS" "$GITHUB_RESPONSE_BODY"
        return 1
    fi
    printf '%s' "$GITHUB_RESPONSE_BODY"
}

# 发送 Gitee API 请求并把响应体/状态码写回全局变量，兼容普通查询和 multipart 表单上传。
gitee_request() {
    local method="$1"
    local path="$2"
    local data="${3:-}"
    local body_file
    local status
    local curl_status
    local url="https://gitee.com/api/v5${path}"
    local token_separator

    body_file="$(mktemp)"
    if [[ -n "$data" ]]; then
        # 表单字段临时目录；Windows Git Bash 调用 curl 时，中文参数经 argv 传递可能被转成本机代码页，
        # 因此把字段值写成 UTF-8 文件，再让 curl 以 `name=<file` 读取原始字节。
        local form_dir
        local -a form_args
        form_dir="$(mktemp -d)"
        form_args=(-F "access_token=${GITEE_TOKEN}")
        while IFS= read -r form_field; do
            form_args+=(-F "$form_field")
        done < <(
            python3 - "$data" "$form_dir" <<'PY'
import json
import os
import sys

payload = json.loads(sys.argv[1])
form_dir = sys.argv[2]
for index, (key, value) in enumerate(payload.items()):
    if isinstance(value, bool):
        value = str(value).lower()
    elif value is None:
        value = ""
    else:
        value = str(value)
    path = os.path.join(form_dir, f"{index}.txt")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(value)
    print(f"{key}=<{path}")
PY
        )
        set +e
        status="$(
            run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X "$method" \
            -H "Accept: application/json" \
            "${form_args[@]}" \
            "$url"
        )"
        curl_status=$?
        set -e
        rm -rf "$form_dir"
    else
        if [[ "$url" == *\?* ]]; then
            token_separator="&"
        else
            token_separator="?"
        fi
        set +e
        status="$(
            run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X "$method" \
            -H "Accept: application/json" \
            "${url}${token_separator}access_token=${GITEE_TOKEN}"
        )"
        curl_status=$?
        set -e
    fi

    GITEE_RESPONSE_BODY="$(cat "$body_file")"
    GITEE_RESPONSE_STATUS="$status"
    rm -f "$body_file"

    if [[ "$curl_status" -ne 0 ]]; then
        echo "Gitee API 网络请求失败：${method} ${url}" >&2
        return "$curl_status"
    fi
}

# 调用 Gitee API 并在非 2xx 时输出格式化错误后返回失败。
gitee_api() {
    local method="$1"
    local path="$2"
    local data="${3:-}"
    gitee_request "$method" "$path" "$data"
    if [[ "$GITEE_RESPONSE_STATUS" -lt 200 || "$GITEE_RESPONSE_STATUS" -ge 300 ]]; then
        print_gitee_error "${method} ${path}" "$GITEE_RESPONSE_STATUS" "$GITEE_RESPONSE_BODY"
        return 1
    fi
    printf '%s' "$GITEE_RESPONSE_BODY"
}

# 对文件名做 URL 编码，避免 GitHub 上传附件时因空格或特殊字符导致 4xx。
url_encode() {
    python3 - "$1" <<'PY'
import sys
from urllib.parse import quote

print(quote(sys.argv[1], safe=""))
PY
}

# 直接把二进制文件上传到 GitHub release 资产接口。
github_upload_binary_asset() {
    local release_id="$1"
    local file_path="$2"
    local content_type="$3"
    local file_name
    local encoded_file_name
    local body_file
    local status
    local curl_status

    file_name="$(basename "$file_path")"
    encoded_file_name="$(url_encode "$file_name")"
    body_file="$(mktemp)"

    set +e
    status="$(
        run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X POST \
            -H "Accept: application/vnd.github+json" \
            -H "Authorization: Bearer ${GITHUB_TOKEN}" \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            -H "Content-Type: ${content_type}" \
            "https://uploads.github.com/repos/${GITHUB_REPO}/releases/${release_id}/assets?name=${encoded_file_name}" \
            --data-binary "@${file_path}"
    )"
    curl_status=$?
    set -e

    GITHUB_RESPONSE_BODY="$(cat "$body_file")"
    GITHUB_RESPONSE_STATUS="$status"
    rm -f "$body_file"

    if [[ "$curl_status" -ne 0 ]]; then
        echo "GitHub 附件上传网络请求失败：${file_name}" >&2
        return "$curl_status"
    fi
    if [[ "$GITHUB_RESPONSE_STATUS" -lt 200 || "$GITHUB_RESPONSE_STATUS" -ge 300 ]]; then
        print_github_error "POST upload asset ${file_name}" "$GITHUB_RESPONSE_STATUS" "$GITHUB_RESPONSE_BODY"
        return 1
    fi
}

# 上传 GitHub 附件前先删除同名旧文件，避免 422 和重复附件残留。
github_upload_asset() {
    local release_id="$1"
    local file_path="$2"
    local content_type="$3"
    local file_name
    file_name="$(basename "$file_path")"

    local existing_asset_id
    existing_asset_id="$(
        github_api GET "https://api.github.com/repos/${GITHUB_REPO}/releases/${release_id}/assets?per_page=100" \
            | extract_asset_id_by_name "$file_name" "GitHub" "GET release assets"
    )"
    if [[ -n "$existing_asset_id" ]]; then
        echo "GitHub 删除已有附件：${file_name}"
        github_api DELETE "https://api.github.com/repos/${GITHUB_REPO}/releases/assets/${existing_asset_id}" >/dev/null
    fi

    echo "GitHub 上传附件：${file_name}"
    github_upload_binary_asset "$release_id" "$file_path" "$content_type" >/dev/null
}

# 直接把文件上传到 Gitee release 附件接口。
gitee_upload_binary_asset() {
    local release_id="$1"
    local file_path="$2"
    local file_name
    local body_file
    local status
    local curl_status

    file_name="$(basename "$file_path")"
    body_file="$(mktemp)"

    set +e
    status="$(
        run_curl_with_retry curl --silent --show-error \
            --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
            --output "$body_file" \
            --write-out "%{http_code}" \
            -X POST \
            -H "Accept: application/json" \
            -F "access_token=${GITEE_TOKEN}" \
            -F "file=@${file_path}" \
            "https://gitee.com/api/v5/repos/${GITEE_OWNER}/${GITEE_REPO_NAME}/releases/${release_id}/attach_files"
    )"
    curl_status=$?
    set -e

    GITEE_RESPONSE_BODY="$(cat "$body_file")"
    GITEE_RESPONSE_STATUS="$status"
    rm -f "$body_file"

    if [[ "$curl_status" -ne 0 ]]; then
        echo "Gitee 附件上传网络请求失败：${file_name}" >&2
        return "$curl_status"
    fi
    if [[ "$GITEE_RESPONSE_STATUS" -lt 200 || "$GITEE_RESPONSE_STATUS" -ge 300 ]]; then
        print_gitee_error "POST upload attach_file ${file_name}" "$GITEE_RESPONSE_STATUS" "$GITEE_RESPONSE_BODY"
        return 1
    fi
    printf '%s' "$GITEE_RESPONSE_BODY"
}

# 上传 Gitee 附件前先删除同名旧文件，保持 release 附件列表干净。
gitee_upload_asset() {
    local release_id="$1"
    local file_path="$2"
    local file_name
    local existing_asset_id
    local response
    file_name="$(basename "$file_path")"

    existing_asset_id="$(
        gitee_api GET "/repos/${GITEE_OWNER}/${GITEE_REPO_NAME}/releases/${release_id}/attach_files?per_page=100" \
            | extract_asset_id_by_name "$file_name" "Gitee" "GET release attach_files"
    )"
    if [[ -n "$existing_asset_id" ]]; then
        echo "Gitee 删除已有附件：${file_name}" >&2
        gitee_api DELETE "/repos/${GITEE_OWNER}/${GITEE_REPO_NAME}/releases/${release_id}/attach_files/${existing_asset_id}" >/dev/null
    fi

    echo "Gitee 上传附件：${file_name}" >&2
    response="$(gitee_upload_binary_asset "$release_id" "$file_path")"
    printf '%s' "$response"
}

# 生成最终 `update.json`；下载源和 fallback 发布页都由传入参数动态拼装。
write_manifest() {
    local output="$1"
    shift
    python3 - "$output" "$CHANNEL" "$PACKAGE_NAME" "$VERSION_CODE" "$VERSION_NAME" "$MIN_SUPPORTED_VERSION_CODE" "$FORCE_UPDATE" "$PUBLISHED_AT" "$SHA256" "$RELEASE_NOTES" "$@" <<'PY'
import json
import sys

output = sys.argv[1]
channel = sys.argv[2]
package_name = sys.argv[3]
version_code = int(sys.argv[4])
version_name = sys.argv[5]
min_supported_version_code = int(sys.argv[6])
force_update = sys.argv[7].lower() == "true"
published_at = sys.argv[8]
sha256 = sys.argv[9]
release_notes = sys.argv[10]
raw_downloads = sys.argv[11:]
changelog = [line.strip() for line in release_notes.splitlines() if line.strip()]
if not changelog:
    changelog = [f"发布 {version_name}"]

downloads = []
fallback = None
for raw in raw_downloads:
    if not raw:
        continue
    item = json.loads(raw)
    downloads.append(item)
    if fallback is None and item.get("recommendedForChina"):
        fallback = {"name": item["name"], "url": item["url"]}
if fallback is None and downloads:
    fallback = {"name": downloads[0]["name"], "url": downloads[0]["url"]}

manifest = {
    "schemaVersion": 1,
    "channel": channel,
    "packageName": package_name,
    "versionCode": version_code,
    "versionName": version_name,
    "minSupportedVersionCode": min_supported_version_code,
    "forceUpdate": force_update,
    "publishedAt": published_at,
    "sha256": sha256,
    "changelog": changelog,
    "downloads": downloads,
}
if fallback is not None:
    manifest["fallbackReleasePage"] = fallback

with open(output, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
}

# 构造单个下载源 JSON 片段，供 update.json 的 downloads 数组复用。
json_download() {
    local id="$1"
    local name="$2"
    local url="$3"
    local recommended="$4"
    python3 - "$id" "$name" "$url" "$recommended" <<'PY'
import json
import sys

print(json.dumps({
    "id": sys.argv[1],
    "name": sys.argv[2],
    "url": sys.argv[3],
    "recommendedForChina": sys.argv[4].lower() == "true",
}, ensure_ascii=False))
PY
}

# 从 JSON 对象响应里提取必填字段；缺字段或结构错误时直接失败。
extract_json_field() {
    local field="$1"
    local source="${2:-API}"
    local context="${3:-parse response}"
    python3 -c '
import json
import sys

field = sys.argv[1]
source = sys.argv[2]
context = sys.argv[3]
raw = sys.stdin.read()

def fail(message):
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print(message, file=sys.stderr)
    sys.exit(1)

if not raw.strip():
    fail("响应体为空，无法解析 JSON。")
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print("响应体不是 JSON，前 1000 字符：", file=sys.stderr)
    print(raw[:1000], file=sys.stderr)
    sys.exit(1)
if not isinstance(data, dict):
    fail(f"响应 JSON 类型不是对象：{type(data).__name__}")
value = data.get(field, "")
if value is None:
    value = ""
if isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
else:
    print(value)
' "$field" "$source" "$context"
}

# 从 JSON 对象响应里提取可选字段；空响应或 null 允许回退为空串。
extract_optional_json_field() {
    local field="$1"
    local source="${2:-API}"
    local context="${3:-parse response}"
    python3 -c '
import json
import sys

field = sys.argv[1]
source = sys.argv[2]
context = sys.argv[3]
raw = sys.stdin.read()

def fail(message):
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print(message, file=sys.stderr)
    sys.exit(1)

if not raw.strip():
    print("")
    sys.exit(0)
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print("响应体不是 JSON，前 1000 字符：", file=sys.stderr)
    print(raw[:1000], file=sys.stderr)
    sys.exit(1)
if data is None:
    print("")
    sys.exit(0)
if not isinstance(data, dict):
    fail(f"响应 JSON 类型不是对象：{type(data).__name__}")
value = data.get(field, "")
if value is None:
    value = ""
if isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
else:
    print(value)
' "$field" "$source" "$context"
}

# 从附件列表 JSON 中按名字提取附件 id，便于删除同名旧附件。
extract_asset_id_by_name() {
    local target_name="$1"
    local source="$2"
    local context="$3"
    python3 -c '
import json
import sys

target_name = sys.argv[1]
source = sys.argv[2]
context = sys.argv[3]
raw = sys.stdin.read()

def fail(message):
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print(message, file=sys.stderr)
    sys.exit(1)

if not raw.strip():
    fail("响应体为空，无法解析附件列表。")
try:
    assets = json.loads(raw)
except json.JSONDecodeError:
    print(f"{source} API 响应解析失败：{context}", file=sys.stderr)
    print("响应体不是 JSON，前 1000 字符：", file=sys.stderr)
    print(raw[:1000], file=sys.stderr)
    sys.exit(1)
if not isinstance(assets, list):
    fail(f"附件列表 JSON 类型不是数组：{type(assets).__name__}")
for asset in assets:
    if isinstance(asset, dict) and asset.get("name") == target_name:
        print(asset.get("id", ""))
        break
' "$target_name" "$source" "$context"
}

# 自测 JSON 解析错误提示是否足够明确，避免发布现场只看到模糊的脚本异常。
self_test_json_errors() {
    local output
    set +e
    output="$(printf '' | extract_json_field id "Gitee" "GET release by tag v0.0.0" 2>&1)"
    local status=$?
    set -e
    if [[ "$status" -eq 0 ]]; then
        echo "JSON 错误提示自检失败：空响应解析没有失败。" >&2
        return 1
    fi
    if [[ "$output" != *"Gitee API 响应解析失败：GET release by tag v0.0.0"* || "$output" != *"响应体为空，无法解析 JSON。"* ]]; then
        echo "JSON 错误提示自检失败：输出不符合预期。" >&2
        printf '%s\n' "$output" >&2
        return 1
    fi

    set +e
    output="$(printf '<html>login</html>' | extract_asset_id_by_name "update.json" "Gitee" "GET release attach_files" 2>&1)"
    status=$?
    set -e
    if [[ "$status" -eq 0 ]]; then
        echo "JSON 错误提示自检失败：非 JSON 附件列表解析没有失败。" >&2
        return 1
    fi
    if [[ "$output" != *"Gitee API 响应解析失败：GET release attach_files"* || "$output" != *"响应体不是 JSON"* ]]; then
        echo "JSON 错误提示自检失败：附件列表输出不符合预期。" >&2
        printf '%s\n' "$output" >&2
        return 1
    fi

    set +e
    output="$(printf 'null' | extract_optional_json_field id "Gitee" "GET release by tag v0.0.0" 2>&1)"
    status=$?
    set -e
    if [[ "$status" -ne 0 || -n "$output" ]]; then
        echo "JSON 错误提示自检失败：Gitee release null 没有按不存在处理。" >&2
        printf '%s\n' "$output" >&2
        return 1
    fi

    echo "JSON 错误提示自检通过。"
}

require_command curl
require_command python3
require_command shasum

if [[ "$SELF_TEST_JSON_ERRORS" == "true" ]]; then
    self_test_json_errors
    exit 0
fi

if [[ "$SKIP_GITHUB" == "true" && "$SKIP_GITEE" == "true" ]]; then
    echo "不能同时跳过 GitHub 和 Gitee。" >&2
    exit 2
fi

for forbidden_key in githubToken giteeToken; do
    if property_exists "${BASE_DIR}/gradle.properties" "$forbidden_key"; then
        cat >&2 <<ERROR
检测到项目 gradle.properties 中配置了 ${forbidden_key}。
该文件会随源码上传，不能保存真实 token。
请把 ${forbidden_key} 移到 ~/.gradle/gradle.properties 或 local.properties 后再发布。
ERROR
        exit 1
    fi
done

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    GITHUB_TOKEN="$(read_token_from_properties githubToken)"
fi
if [[ -z "${GITEE_TOKEN:-}" ]]; then
    GITEE_TOKEN="$(read_token_from_properties giteeToken)"
fi

if [[ "$DRY_RUN" != "true" && "$SKIP_GITHUB" != "true" && -z "${GITHUB_TOKEN:-}" ]]; then
    echo "请先设置 GITHUB_TOKEN，或在 ~/.gradle/gradle.properties 添加 githubToken；也可以传 --skip-github 或 --dry-run。" >&2
    exit 1
fi
if [[ "$DRY_RUN" != "true" && "$SKIP_GITEE" != "true" && -z "${GITEE_TOKEN:-}" ]]; then
    echo "请先设置 GITEE_TOKEN，或在 ~/.gradle/gradle.properties 添加 giteeToken；也可以传 --skip-gitee 或 --dry-run。" >&2
    exit 1
fi

split_owner_repo "$GITHUB_REPO" GITHUB_OWNER GITHUB_REPO_NAME
split_owner_repo "$GITEE_REPO" GITEE_OWNER GITEE_REPO_NAME

if [[ "$SKIP_GITHUB" != "true" && "$SKIP_GITEE" != "true" && "$GITHUB_REPO" != "$GITEE_REPO" ]]; then
    echo "提示：GitHub 仓库为 ${GITHUB_REPO}，Gitee 仓库为 ${GITEE_REPO}。"
fi

echo "构建 release APK..."
"${BASE_DIR}/gradlew" -p "$BASE_DIR" :app:assembleRelease

METADATA_FILE="${BASE_DIR}/app/build/outputs/apk/release/output-metadata.json"
if [[ ! -f "$METADATA_FILE" ]]; then
    echo "找不到 APK metadata：${METADATA_FILE}" >&2
    exit 1
fi

read -r VERSION_CODE VERSION_NAME APK_FILE_NAME < <(
    python3 - "$METADATA_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as f:
    metadata = json.load(f)

element = metadata["elements"][0]
print(element["versionCode"], element["versionName"], element["outputFile"])
PY
)

APK_PATH="${BASE_DIR}/app/build/outputs/apk/release/${APK_FILE_NAME}"
if [[ ! -f "$APK_PATH" ]]; then
    echo "找不到 APK：${APK_PATH}" >&2
    exit 1
fi

if [[ -z "$MIN_SUPPORTED_VERSION_CODE" ]]; then
    MIN_SUPPORTED_VERSION_CODE="$VERSION_CODE"
fi

TAG_NAME="v${VERSION_NAME}"
GITHUB_DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${TAG_NAME}/${APK_FILE_NAME}"
GITEE_RELEASE_PAGE_URL="https://gitee.com/${GITEE_REPO}/releases/tag/${TAG_NAME}"
GITEE_APK_DOWNLOAD_URL="${GITEE_RELEASE_PAGE_URL}"
SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
PUBLISHED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
mkdir -p "$WORK_DIR"

if [[ -z "$RELEASE_NOTES" ]]; then
    RELEASE_NOTES="发布 ${VERSION_NAME}"
fi

printf '%s  %s\n' "$SHA256" "$APK_FILE_NAME" > "${WORK_DIR}/sha256.txt"

DRY_RUN_DOWNLOADS=()
if [[ "$SKIP_GITEE" != "true" ]]; then
    DRY_RUN_DOWNLOADS+=("$(json_download gitee "Gitee Release" "$GITEE_RELEASE_PAGE_URL" true)")
fi
if [[ "$SKIP_GITHUB" != "true" ]]; then
    DRY_RUN_DOWNLOADS+=("$(json_download github "GitHub Release" "$GITHUB_DOWNLOAD_URL" false)")
fi
write_manifest "${WORK_DIR}/update.json" "${DRY_RUN_DOWNLOADS[@]}"

MANIFEST_FILE="${WORK_DIR}/update.json"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "发布文件已生成："
    echo "  APK: ${APK_PATH}"
    echo "  update.json: ${WORK_DIR}/update.json"
    echo "  sha256.txt: ${WORK_DIR}/sha256.txt"
    echo "  tag: ${TAG_NAME}"
    echo "  GitHub repo: ${GITHUB_REPO}"
    echo "  Gitee repo: ${GITEE_REPO}"
    echo "dry-run 已结束，未调用 GitHub/Gitee API。"
    exit 0
fi

GITEE_RELEASE_ID=""
if [[ "$SKIP_GITEE" != "true" ]]; then
    echo "查询 Gitee Release：${TAG_NAME}"
    gitee_request GET "/repos/${GITEE_OWNER}/${GITEE_REPO_NAME}/releases/tags/${TAG_NAME}"
    if [[ "$GITEE_RESPONSE_STATUS" == "404" ]]; then
        GITEE_RELEASE_ID=""
    elif [[ "$GITEE_RESPONSE_STATUS" -ge 200 && "$GITEE_RESPONSE_STATUS" -lt 300 ]]; then
        GITEE_RELEASE_ID="$(printf '%s' "$GITEE_RESPONSE_BODY" | extract_optional_json_field id "Gitee" "GET release by tag ${TAG_NAME}")"
    else
        print_gitee_error "GET release by tag ${TAG_NAME}" "$GITEE_RESPONSE_STATUS" "$GITEE_RESPONSE_BODY"
        exit 1
    fi

    if [[ -z "$GITEE_RELEASE_ID" ]]; then
        echo "创建 Gitee Release：${TAG_NAME}"
        GITEE_RELEASE_BODY="$(
            python3 - "$TAG_NAME" "$RELEASE_NOTES" <<'PY'
import json
import sys

print(json.dumps({
    "tag_name": sys.argv[1],
    "target_commitish": "master",
    "name": sys.argv[1],
    "body": sys.argv[2],
    "prerelease": False,
}, ensure_ascii=False))
PY
        )"
        CREATE_RESPONSE="$(gitee_api POST "/repos/${GITEE_OWNER}/${GITEE_REPO_NAME}/releases" "$GITEE_RELEASE_BODY")"
        GITEE_RELEASE_ID="$(printf '%s' "$CREATE_RESPONSE" | extract_json_field id "Gitee" "POST create release ${TAG_NAME}")"
    else
        echo "复用已有 Gitee Release：${TAG_NAME}"
    fi

    GITEE_APK_RESPONSE="$(gitee_upload_asset "$GITEE_RELEASE_ID" "$APK_PATH")"
    gitee_upload_asset "$GITEE_RELEASE_ID" "${WORK_DIR}/sha256.txt" >/dev/null
    GITEE_APK_DOWNLOAD_URL="$(printf '%s' "$GITEE_APK_RESPONSE" | extract_json_field browser_download_url "Gitee" "POST upload attach_file ${APK_FILE_NAME}")"
    if [[ -z "$GITEE_APK_DOWNLOAD_URL" ]]; then
        GITEE_APK_DOWNLOAD_URL="$GITEE_RELEASE_PAGE_URL"
    fi
fi

FINAL_DOWNLOADS=()
if [[ "$SKIP_GITEE" != "true" ]]; then
    FINAL_DOWNLOADS+=("$(json_download gitee "Gitee Release" "$GITEE_APK_DOWNLOAD_URL" true)")
fi
if [[ "$SKIP_GITHUB" != "true" ]]; then
    FINAL_DOWNLOADS+=("$(json_download github "GitHub Release" "$GITHUB_DOWNLOAD_URL" false)")
fi
write_manifest "$MANIFEST_FILE" "${FINAL_DOWNLOADS[@]}"

if [[ "$SKIP_GITEE" != "true" ]]; then
    gitee_upload_asset "$GITEE_RELEASE_ID" "$MANIFEST_FILE" >/dev/null
fi

GITHUB_RELEASE_ID=""
if [[ "$SKIP_GITHUB" != "true" ]]; then
    echo "查询 GitHub Release：${TAG_NAME}"
    github_request GET "https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${TAG_NAME}"
    if [[ "$GITHUB_RESPONSE_STATUS" == "404" ]]; then
        GITHUB_RELEASE_ID=""
    elif [[ "$GITHUB_RESPONSE_STATUS" -ge 200 && "$GITHUB_RESPONSE_STATUS" -lt 300 ]]; then
        GITHUB_RELEASE_ID="$(printf '%s' "$GITHUB_RESPONSE_BODY" | extract_json_field id "GitHub" "GET release by tag ${TAG_NAME}")"
    else
        print_github_error "GET release by tag ${TAG_NAME}" "$GITHUB_RESPONSE_STATUS" "$GITHUB_RESPONSE_BODY"
        exit 1
    fi

    if [[ -z "$GITHUB_RELEASE_ID" ]]; then
        echo "创建 GitHub Release：${TAG_NAME}"
        GITHUB_RELEASE_BODY="$(
            python3 - "$TAG_NAME" "$RELEASE_NOTES" <<'PY'
import json
import sys

print(json.dumps({
    "tag_name": sys.argv[1],
    "name": sys.argv[1],
    "body": sys.argv[2],
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY
        )"
        CREATE_RESPONSE="$(github_api POST "https://api.github.com/repos/${GITHUB_REPO}/releases" "$GITHUB_RELEASE_BODY")"
        GITHUB_RELEASE_ID="$(printf '%s' "$CREATE_RESPONSE" | extract_json_field id "GitHub" "POST create release ${TAG_NAME}")"
    else
        echo "复用已有 GitHub Release：${TAG_NAME}"
    fi

    github_upload_asset "$GITHUB_RELEASE_ID" "$APK_PATH" "application/vnd.android.package-archive"
    github_upload_asset "$GITHUB_RELEASE_ID" "$MANIFEST_FILE" "application/json"
    github_upload_asset "$GITHUB_RELEASE_ID" "${WORK_DIR}/sha256.txt" "text/plain"
fi

echo "上传完成："
if [[ "$SKIP_GITEE" != "true" ]]; then
    echo "  Gitee Release: ${GITEE_RELEASE_PAGE_URL}"
    echo "  Gitee update API: https://gitee.com/api/v5/repos/${GITEE_REPO}/releases/latest"
fi
if [[ "$SKIP_GITHUB" != "true" ]]; then
    echo "  GitHub Release: https://github.com/${GITHUB_REPO}/releases/tag/${TAG_NAME}"
    echo "  GitHub update.json: https://github.com/${GITHUB_REPO}/releases/latest/download/update.json"
fi
