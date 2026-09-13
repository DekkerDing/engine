# -*- coding: utf-8 -*-
"""
env_check.py — Python 环境自检脚本（开发环境"体检表"）

【教学注释】多语言项目最常见的翻车点就是环境。这个脚本把"能不能跑"变成
一眼可见的绿灯/红灯清单，每次拉取代码后先跑它：

    python env_check.py

检查项：
  1. Python 版本（>= 3.9）
  2. 核心依赖是否可导入（numpy / sentence_transformers / jieba / py4j）
  3. HF 镜像配置（国内网络必须，否则模型下载超时）
  4. embedding 模型本地缓存是否已下载（首次需联网）

设计要点（Python 工程习惯）：
  - 每个检查独立成函数，输出统一 [PASS]/[FAIL]/[WARN] 前缀
  - FAIL 不抛异常，跑完所有项再汇总退出码（一次看全所有问题）
  - 只用标准库做框架部分，业务检查按需 import（缺包时优雅降级为 FAIL 而非崩溃）
"""
from __future__ import annotations

import importlib.util  # 【坑】import importlib 不会自动加载 .util 子模块，必须显式导入
import os
import shutil
import sys

# ------------------------------------------------------------
# 1. 环境变量预设：国内 HF 镜像（必须在 import sentence_transformers 之前设置）
# ------------------------------------------------------------
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
# Windows 控制台中文输出保险
if sys.platform == "win32":
    os.system("")  # 激活 ANSI 转义支持

# 默认文本 embedding 模型（与 core/registry.py、Java application.yml 保持一致）
DEFAULT_TEXT_MODEL = "paraphrase-multilingual-MiniLM-L12-v2"

PASS, FAIL, WARN = "[PASS]", "[FAIL]", "[WARN]"


def check_python() -> bool:
    """检查 Python 版本 >= 3.9（f-string/类型注解等现代语法的下限）"""
    v = sys.version_info
    ok = v >= (3, 9)
    mark = PASS if ok else FAIL
    print(f"{mark} Python 版本: {v.major}.{v.minor}.{v.micro} (要求 >= 3.9)")
    return ok


def check_package(module: str, pip_name: str | None = None) -> bool:
    """
    检查单个第三方包是否可导入。

    importlib.util.find_spec 只探测不导入——快，且不会因包的副作用报错。
    pip_name 用于给出准确的安装命令（import 名与包名常不一致）。
    """
    try:
        spec = importlib.util.find_spec(module)
    except (ImportError, ValueError):
        spec = None
    if spec is not None:
        print(f"{PASS} 依赖 {module} 已安装")
        return True
    print(f"{FAIL} 依赖 {module} 未安装")
    print(f"      修复: pip install {pip_name or module} "
          f"-i https://pypi.tuna.tsinghua.edu.cn/simple")
    return False


def check_hf_mirror() -> bool:
    """检查 HF 镜像端点（国内网络不配镜像，模型下载几乎必超时）"""
    endpoint = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
    if "hf-mirror.com" in endpoint:
        print(f"{PASS} HF 镜像已配置: {endpoint}")
        return True
    print(f"{WARN} HF_ENDPOINT={endpoint}（非镜像，国内可能下载超时）")
    print(f"      修复: set HF_ENDPOINT=https://hf-mirror.com")
    return True  # 境外网络或已有代理时可用，降级为 WARN


def check_model_cache() -> bool:
    """
    检查默认模型是否已有本地缓存。

    sentence-transformers 模型缓存在 ~/.cache/huggingface/hub/ 下，
    目录名形如 models--sentence-transformers--paraphrase-multilingual-MiniLM-L12-v2。
    未缓存不算错误（首次运行会自动下载），但给出明确的体量提示。
    """
    hub_root = os.path.join(os.path.expanduser("~"), ".cache", "huggingface", "hub")
    marker = DEFAULT_TEXT_MODEL.replace("/", "--")
    expected = f"models--sentence-transformers--{marker}"
    if any(expected in name for name in os.listdir(hub_root) if os.path.isdir(os.path.join(hub_root, name))) if os.path.isdir(hub_root) else False:
        print(f"{PASS} 模型已缓存: {DEFAULT_TEXT_MODEL}")
        return True
    print(f"{WARN} 模型未缓存: {DEFAULT_TEXT_MODEL}")
    print(f"      首次调用会自动下载（约 470MB，已配置 hf-mirror 加速）")
    print(f"      也可预下载: python -c \"from sentence_transformers import SentenceTransformer as m; m('{DEFAULT_TEXT_MODEL}')\"")
    return True  # WARN 不算失败


def check_interpreter_command() -> bool:
    """提示 Java 侧将使用的解释器命令（application.yml engine.python.command）"""
    exe = shutil.which("python")
    if exe:
        print(f"{PASS} python 命令可用: {exe}")
        return True
    print(f"{FAIL} PATH 中找不到 python 命令（Java ProcessBuilder 将无法拉起子进程）")
    return False


def main() -> int:
    print("=" * 60)
    print("engine Python 环境自检")
    print("=" * 60)

    results = [check_python()]
    print("-" * 60)
    # (导入名, pip 包名) 映射：sentence_transformers 的 import 名下划线、包名连字符
    for module, pip_name in [
        ("numpy", None),
        ("sentence_transformers", "sentence-transformers"),
        ("jieba", None),
        ("py4j", None),
    ]:
        results.append(check_package(module, pip_name))
    print("-" * 60)
    results.append(check_hf_mirror())
    results.append(check_model_cache())
    results.append(check_interpreter_command())

    print("=" * 60)
    if all(results):
        print("结论: 环境就绪，可以启动服务")
        return 0
    print("结论: 存在未通过项，按上面 [FAIL] 行的提示修复后重试")
    return 1


if __name__ == "__main__":
    sys.exit(main())
