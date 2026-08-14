---
name: "bytedance-network-proxy"
description: "Configures ByteDance external proxy and Python package index. Invoke when downloading external tools, uv/pyenv, Python runtimes, or packages."
---

# ByteDance Network Proxy

Use this skill whenever project work downloads external resources from a
ByteDance network environment, especially:

- installing or updating `uv`
- cloning or updating `pyenv`
- downloading `uv` managed Python runtimes
- installing Python packages with `uv pip`, `pip`, or a venv bootstrap
- writing deployment, probe, or bootstrap scripts that need repeatable network
  behavior

## Proxy Environment

Use a plain URL value. Do not wrap it in backticks; backticks perform shell
command substitution.

```bash
bytedance_external_proxy_env() {
  export http_proxy="${BYTEDANCE_EXTERNAL_PROXY:-http://sys-proxy-rd-relay.byted.org:8118}"
  export https_proxy="${BYTEDANCE_EXTERNAL_PROXY:-http://sys-proxy-rd-relay.byted.org:8118}"
  export HTTP_PROXY="${HTTP_PROXY:-$http_proxy}"
  export HTTPS_PROXY="${HTTPS_PROXY:-$https_proxy}"
  export no_proxy="${BYTEDANCE_NO_PROXY:-localhost,.byted.org,byted.org,.bytedance.net,bytedance.net,127.0.0.1,127.0.0.0/8,169.254.0.0/16,100.64.0.0/10,172.16.0.0/12,192.168.0.0/16,10.0.0.0/8,::1,fe80::/10,fd00::/8}"
  export NO_PROXY="${NO_PROXY:-$no_proxy}"
}
```

Call it before any external `curl`, `git clone`, `uv python install`, or package
operation:

```bash
bytedance_external_proxy_env
curl -LsSf https://astral.sh/uv/install.sh | sh

bytedance_external_proxy_env
git clone --depth 1 --branch v2.8.4 https://github.com/pyenv/pyenv.git "$target"

bytedance_external_proxy_env
uv python install 3 --managed-python --upgrade --compile-bytecode
```

## Python Package Index

Prefer the internal Python package index for package installs. Keep this
separate from the external proxy because Python packages and external source
downloads are different dependencies.

```bash
bytedance_python_package_env() {
  export PIP_INDEX_URL="${PIP_INDEX_URL:-https://bytedpypi.byted.org/simple}"
  export UV_DEFAULT_INDEX="${UV_DEFAULT_INDEX:-$PIP_INDEX_URL}"
  export UV_INDEX_URL="${UV_INDEX_URL:-$PIP_INDEX_URL}"
}
```

Use both functions when a command may need external access and Python packages:

```bash
bytedance_external_proxy_env
bytedance_python_package_env
uv pip install --python "$venv/bin/python" --upgrade pip setuptools wheel
```

## Rules

- Keep project runtimes isolated. Do not reuse another project's `.tmp`, venv,
  cache, `uv`, or `pyenv` directories.
- Put project-specific remote toolchains under that project's own durable temp
  root, for example `/data24/.tmp/pulse-agent-ops`.
- Make bootstrap failures explicit. Do not silently fall back to system Python
  or a different package source.
- Set both lowercase and uppercase proxy variables because different tools read
  different names.
- Preserve the full `no_proxy` list for ByteDance internal networks.
