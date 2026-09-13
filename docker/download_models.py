#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
download_models.py — 构建期模型预下载（烘进镜像层）
------------------------------------------------------------
【用途】在 docker build 阶段把全部模型下载到 /app/models，
使运行期完全离线（spec: container-deployment/模型权重内置与离线运行）。

【与运行期的衔接 · 两个缓存根，两种布局，各自自洽】
    sentence-transformers：构建期 SentenceTransformer(name, cache_folder=/app/models)
                           与运行期 SENTENCE_TRANSFORMERS_HOME=/app/models 走同一
                           cache_dir → 布局 /app/models/models--BAAI--bge-small-zh-v1.5/...
    transformers（CLIP/重排器）：构建期 HF_HOME=/app/models 下 from_pretrained(name)
                           与运行期同一 HF_HOME → HF_HUB_CACHE=/app/models/hub
                           → 布局 /app/models/hub/models--OFA-Sys--chinese-clip-.../...
两种库的目录布局不同（ST 在顶层、transformers 在 hub/ 子目录），互不干扰；
关键 invariant 是"构建期下载的落点 = 运行期查找的起点"，两边各自成立。

【模型清单】与 engine-server/python/core/registry.py 保持一致——
这里是"构建期需要下载什么"，registry 是"运行期注册什么"，两处同步维护。
用法：python download_models.py [目标目录] [--dry-run]
"""
import os
import sys

# 国内加速：构建期可经环境变量覆盖（HF_ENDPOINT 已在 Dockerfile 中默认设为 hf-mirror）
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from sentence_transformers import SentenceTransformer  # noqa: E402

# registry.py 模型槽位（text-embedding-zh 默认 / text-embedding-multilingual 备选 /
# clip 跨模态 / reranker 交叉编码器——photo-semantic-search）
TEXT_MODELS = [
    "BAAI/bge-small-zh-v1.5",                   # ~90MB   中文优先（默认）
    "paraphrase-multilingual-MiniLM-L12-v2",    # ~470MB  多语备选
]
CLIP_MODEL = "OFA-Sys/chinese-clip-vit-base-patch16"   # ~400MB  中文跨模态（双塔）
RERANKER_MODEL = "BAAI/bge-reranker-base"              # ~1.1GB  交叉编码器（运行期惰性加载）


def dry_run() -> int:
    print("[download_models] dry-run：将下载以下模型")
    for name in TEXT_MODELS:
        print("  - {}  (~SentenceTransformer/文本嵌入)".format(name))
    print("  - {}  (~transformers/CLIP 跨模态)".format(CLIP_MODEL))
    print("  - {}  (~CrossEncoder/重排，惰性加载)".format(RERANKER_MODEL))
    return 0


def main() -> int:
    if "--dry-run" in sys.argv:
        return dry_run()
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    target = args[0] if args else "/app/models"
    print("[download_models] 目标目录: {}".format(target))

    # 文本模型：sentence-transformers 走自己的缓存根（ST_HOME/hub）
    for name in TEXT_MODELS:
        print("[download_models] 下载/校验: {} ...".format(name))
        # 实例化即完成"下载到缓存 + 加载进内存验证完整性"两件事，
        # 构建期提前暴露权重损坏问题，而不是等到运行期才降级
        model = SentenceTransformer(name, cache_folder=target)
        dim = model.get_sentence_embedding_dimension()
        print("[download_models] 完成: {} (dim={})".format(name, dim))

    # CLIP 与重排器：transformers 系走 HF_HOME/hub 布局——先设 HF_HOME 再加载，
    # 下载落点与运行期 from_pretrained / CrossEncoder 的查找点一致
    os.environ["HF_HOME"] = target
    print("[download_models] 下载/校验: {} ...".format(CLIP_MODEL))
    from transformers import ChineseCLIPModel, ChineseCLIPProcessor  # noqa: E402
    clip = ChineseCLIPModel.from_pretrained(CLIP_MODEL)
    ChineseCLIPProcessor.from_pretrained(CLIP_MODEL)
    print("[download_models] 完成: {} (projection_dim={})".format(
        CLIP_MODEL, clip.projection_dim))

    # 重排器（photo-semantic-search）：CrossEncoder 不收 cache_folder，靠 HF_HOME 定位；
    # 运行期 rerank.py 惰性加载（首次重排请求才进内存），构建期这里只负责
    # "下载落盘 + 实例化校验完整性"，不给运行内存留负担
    print("[download_models] 下载/校验: {} ...".format(RERANKER_MODEL))
    from sentence_transformers import CrossEncoder  # noqa: E402
    CrossEncoder(RERANKER_MODEL, max_length=512)
    print("[download_models] 完成: {} (CrossEncoder)".format(RERANKER_MODEL))

    print("[download_models] 全部模型就绪")
    return 0


if __name__ == "__main__":
    sys.exit(main())
