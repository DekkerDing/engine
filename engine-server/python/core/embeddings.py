# -*- coding: utf-8 -*-
"""
embeddings.py — 文本向量化核心（真实模型 + 确定性降级双实现）

【修复蓝本最严重缺陷】
蓝本 python_engine.py 的 get_text_embedding 在模型不可用时返回
np.random.seed(42); np.random.randn(100) —— 所有文本拿到同一个假向量，
且不告诉调用方。语义检索从此全是噪音，还很难被发现。

本模块的设计纪律：
  1. 一切向量都带 degraded 标志，真实模型不可用必须显式声明
  2. 降级实现是"确定性哈希向量"：同一文本永远同一向量（可检索、可测试），
     而不是随机数。随机种子只是哈希函数的实现细节，与"随机结果"有本质区别
  3. 任何分支禁止无标志的假数据
"""
from __future__ import annotations

import hashlib
import threading
from typing import NamedTuple

import numpy as np

from .registry import DEFAULT_TEXT_KEY, ModelSpec, get_spec

# 国内镜像：必须在 import sentence_transformers 之前设置（transformers 读此环境变量）
import os
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")


class EmbedBatch(NamedTuple):
    """一批向量的编码结果（NamedTuple：不可变 + 可解包 + 零开销）"""
    vectors: list[list[float]]   # 已 L2 归一化的向量
    dim: int
    model: str                   # 实际使用的模型名
    degraded: bool               # True = 降级哈希向量，语义精度受限


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    """
    按行 L2 归一化：每行除以自身模长，使 ||v||=1。
    归一化后「点积 == 余弦相似度」，检索侧可少做一次除法——
    这也是后面 FAISS 用 IndexFlatIP（内积）而不是 IndexFlatL2 的原因。
    """
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    # 1e-12 防除零：全零向量归一化后保持为零
    return matrix / np.maximum(norms, 1e-12)


class SentenceTransformerEmbedder:
    """
    真实 embedding 模型的懒加载封装。

    【教学注释 · 懒加载 + 双检锁】
    模型加载要 5~20 秒（读权重进内存），不能在进程启动时同步加载
    （否则 Java 侧健康检查等不到就绪）。首条请求触发加载，用锁保证
    并发首请求只加载一次；loaded 标志让后续请求直接走快路径。
    """

    def __init__(self, spec: ModelSpec):
        self._spec = spec
        self._model = None
        self._lock = threading.Lock()
        self.load_error: str | None = None

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def load(self) -> bool:
        """尝试加载模型。成功返回 True；失败记录原因并返回 False（不抛异常——降级策略由上层决定）"""
        if self._model is not None:
            return True
        with self._lock:
            if self._model is not None:  # 双检：另一线程可能刚加载完
                return True
            try:
                from sentence_transformers import SentenceTransformer
                self._model = SentenceTransformer(self._spec.model_name)
                return True
            except Exception as exc:  # 模型缺失/网络失败等一切异常
                self.load_error = f"{type(exc).__name__}: {exc}"
                return False

    def embed(self, texts: list[str]) -> np.ndarray:
        """批量编码。调用方必须先确认 loaded=True（用 load() 的返回值判断）"""
        embeddings = self._model.encode(
            texts,
            batch_size=32,           # 一次喂给模型的条数：太小吞吐低，太大占内存
            normalize_embeddings=True,  # 模型内部直接做 L2 归一化
            show_progress_bar=False,
        )
        return np.asarray(embeddings, dtype=np.float32)


class HashEmbedder:
    """
    确定性降级编码器：真实模型不可用时的兜底。

    算法：分词 → 每个词用 sha256 哈希做种子生成 dim 维向量 → 求和 → L2 归一化。
    性质（这些性质让降级模式依然"可用"）：
      - 确定性：同一文本永远得到同一向量（重启/跨机一致）
      - 可检索：字面重叠的文本向量接近（共享词贡献相同分量）
      - 明确降级：degraded=True 必须透传到前端
    局限（这正是 embedding 模型的价值所在，可作教学对照实验）：
      - 近义词不同向量（"图片"≠"照片"），语义检索退化为字面检索
    """

    def __init__(self, dim: int):
        self._dim = dim

    @staticmethod
    def _words(text: str) -> list[str]:
        """分词：优先 jieba；jieba 不可用则按相邻双字切（中文检索里 bigram 是经典兜底）"""
        try:
            import jieba
            return [w for w in jieba.lcut(text) if w.strip()]
        except Exception:
            return [text[i:i + 2] for i in range(max(len(text) - 1, 1))]

    def _word_vector(self, word: str) -> np.ndarray:
        """由词哈希种子生成确定性向量（种子→RandomState 是固定的伪随机函数，同词同向量）"""
        # 【坑】RandomState 种子上限 2^32-1，sha256 前 8 字节是 64 位会超界——只取前 4 字节
        seed = int.from_bytes(hashlib.sha256(word.encode("utf-8")).digest()[:4], "little")
        rng = np.random.RandomState(seed)
        return rng.standard_normal(self._dim)

    def embed(self, texts: list[str]) -> np.ndarray:
        rows = []
        for text in texts:
            acc = np.zeros(self._dim, dtype=np.float32)
            for word in self._words(text):
                acc += self._word_vector(word)
            rows.append(acc)
        # all-zero 行（空文本）归一化会除零，_l2_normalize 内部有防护
        return _l2_normalize(np.vstack(rows)) if rows else np.zeros((0, self._dim), dtype=np.float32)


class EmbeddingService:
    """
    对外统一门面：自动选择 真实模型 → 降级哈希。

    【教学注释 · 策略模式的运行时切换】
    Embedder 不用继承体系，两个实现只要都有 embed(texts)->ndarray 即可
    （Python 的"鸭子类型"天然支持小接口）；选择逻辑集中在门面，入口层无感。
    """

    def __init__(self, model_key: str | None = None):
        # 模型键优先级：显式参数 > 环境变量（Java launcher 注入）> 注册表默认
        # 【教学注释】让"用哪个模型"成为部署配置而非代码改动——双模型切换的唯一入口
        key = model_key or os.environ.get("ENGINE_TEXT_MODEL_KEY") or DEFAULT_TEXT_KEY
        self._spec: ModelSpec = get_spec(key)
        self._real = SentenceTransformerEmbedder(self._spec)
        self._fallback = HashEmbedder(self._spec.dimension)
        self._load_attempted = False

    def _try_load_real(self) -> bool:
        """整个进程生命周期里，真实模型只尝试加载一次（失败通常是环境问题，重试无意义还拖慢请求）"""
        if not self._load_attempted:
            self._load_attempted = True
            self._real.load()
        return self._real.loaded

    def embed_texts(self, texts: list[str]) -> EmbedBatch:
        """批量编码入口（Java 主链路调用的就是它）"""
        texts = [t if t and t.strip() else " " for t in texts]  # 空文本统一为单空格，避免模型对空串报错
        if self._try_load_real():
            vectors = self._real.embed(texts)
            return EmbedBatch(
                vectors=vectors.astype(float).tolist(),
                dim=self._spec.dimension,
                model=self._spec.model_name,
                degraded=False,
            )
        vectors = self._fallback.embed(texts)
        return EmbedBatch(
            vectors=vectors.astype(float).tolist(),
            dim=self._spec.dimension,
            model=f"hash-fallback({self._real.load_error})",
            degraded=True,
        )

    def status(self) -> dict:
        """给 /system/health 的状态快照"""
        return {
            "model_key": self._spec.key,
            "model_name": self._spec.model_name,
            "dimension": self._spec.dimension,
            "real_model_loaded": self._real.loaded,
            "load_error": self._real.load_error,
            "degraded": not self._try_load_real(),
        }


# —— 模块级单例：两个入口共享同一个服务实例（模型只加载一份）——
service = EmbeddingService()
