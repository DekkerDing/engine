# -*- coding: utf-8 -*-
"""
registry.py — 模型注册表（整个引擎的"能力清单"）

【设计意图 · 图片扩展的唯一改动点】
后期做图片识别+跨模态检索时，只需要：
  1. 在这里填上 image-embedding / clip 的模型名与维度
  2. 新增 core/vision.py 实现图片向量化
  3. 两个入口各加一个 op
Java 侧 domain 层（EmbeddingProvider/VectorStore.sourceType）已经预留好，零改动。
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ModelSpec:
    """一个可加载模型的技术规格（frozen=True → 不可变值对象，防止运行期被篡改）"""
    key: str            # 注册表键：text-embedding / image-embedding / clip
    model_name: str     # HuggingFace 模型名（下载/加载用）
    dimension: int      # 输出向量维度
    modality: str       # 模态：text / image / cross（图文同空间）


# 当前启用的模型。None 表示"槽位已预留、尚未启用"
# 【双模型决策】bge-small-zh-v1.5 为默认（中文检索基准显著优于多语 MiniLM，
# 直接回应"检索不准确"的痛点）；MiniLM 保留为多语备选。切换只改 Java 侧
# engine.python.model-key 配置（经环境变量 ENGINE_TEXT_MODEL_KEY 传入本进程），
# 注意：切换维度后库内旧向量不可比，检索侧有维度闸门拦截并提示重新摄取。
REGISTRY: dict[str, ModelSpec | None] = {
    "text-embedding-zh": ModelSpec(
        key="text-embedding-zh",
        model_name="BAAI/bge-small-zh-v1.5",
        dimension=512,
        modality="text",
    ),
    "text-embedding-multilingual": ModelSpec(
        key="text-embedding-multilingual",
        model_name="paraphrase-multilingual-MiniLM-L12-v2",
        dimension=384,
        modality="text",
    ),
    # ---- 图片跨模态检索（图文共空间，支持"红塔+小花"文本搜图）----
    # 惰性加载：注册表只声明规格，模型在首次图片/跨模态请求时才加载（core/vision.py），
    # 纯文本使用场景不付出 ~1GB 额外内存。
    "image-embedding": None,
    "clip": ModelSpec(
        key="clip",
        model_name="OFA-Sys/chinese-clip-vit-base-patch16",
        dimension=512,
        modality="cross",
    ),
    # ---- 图片语义拆分（结构化标注：主题/描述/标签）----
    # vlm 槽位本期填 mock 实现（core/annotate.py）：确定性、零网络、零模型下载，
    # 输出显式携带 mocked=true（对齐 vision.py 的 degraded 显式标志纪律）。
    # 真实 VLM（GLM-4V / qwen-vl 等）到位后只换实现，协议/字段/槽位零改动。
    # dimension=0：标注器产出文本而非向量，维度概念不适用（describe 快照如实展示）。
    "vlm": ModelSpec(
        key="vlm",
        model_name="mock-deterministic-annotator",
        dimension=0,
        modality="annotate",
    ),
    # ---- 检索重排（cross-encoder，query × 候选文本 打分）----
    # 惰性加载（core/rerank.py）：纯导入/纯浏览场景不付出 ~1.1GB 权重内存。
    # dimension=1：每个候选产出一个相关性分数。
    "reranker": ModelSpec(
        key="reranker",
        model_name="BAAI/bge-reranker-base",
        dimension=1,
        modality="rerank",
    ),
}

# 默认文本模型键（Java application.yml 的 engine.python.model-key 与此对应；
# 环境变量 ENGINE_TEXT_MODEL_KEY 可覆盖，见 embeddings.EmbeddingService）
DEFAULT_TEXT_KEY = "text-embedding-zh"


def get_spec(key: str) -> ModelSpec:
    """
    取模型规格；键不存在或槽位未启用时抛 KeyError。
    【工程习惯】"找不到就抛错"比"找不到返回 None 让调用方猜"更安全——
    错误在入口处爆发，而不是传播三层后变成难查的 NoneType 错误。
    """
    spec = REGISTRY.get(key)
    if spec is None:
        raise KeyError(f"模型槽位 '{key}' 未启用（注册表：{list(REGISTRY)}）")
    return spec


def describe() -> dict:
    """给 /system/health 用的注册表快照：已启用的给规格，未启用的给 null"""
    return {k: (v.__dict__ if v else None) for k, v in REGISTRY.items()}
