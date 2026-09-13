# -*- coding: utf-8 -*-
"""
vision.py — CLIP 跨模态编码核心（图片向量 + 查询文本向量，图文共空间）

设计纪律逐条对齐 embeddings.py（文本引擎）：
  1. 一切向量都带 degraded 标志，真实模型不可用必须显式声明
  2. 降级实现是"确定性哈希向量"：图片按字节块哈希、文本按词哈希——
     同输入永远同向量（可测试、可检索），而不是随机数
  3. 任何分支禁止无标志的假数据
  4. 惰性加载 + 双检锁：纯文本使用场景不付出 CLIP 模型（~1GB）的内存代价

与文本引擎的一个关键差异——**数据错误与模型不可用是两类故障**：
  模型不可用 → 降级哈希向量 + degraded=True（服务仍可用）
  图片损坏/不可解码 → 抛明确异常（数据坏了编码出来的向量毫无意义，装作成功才是坑）
"""
from __future__ import annotations

import hashlib
import threading
from typing import NamedTuple

import numpy as np

from .embeddings import EmbedBatch, _l2_normalize
from .registry import ModelSpec, get_spec

import os
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

CLIP_KEY = "clip"


def _features_to_numpy(features) -> np.ndarray:
    """
    CLIP 特征输出 → numpy（兼容 transformers 4.x / 5.x 两种返回形态）。
    4.x 的 get_*_features 返回裸 tensor（直接 detach）；
    5.x 返回 BaseModelOutputWithPooling——投影后的特征在 pooler_output。
    """
    if hasattr(features, "detach"):  # 裸 tensor
        return features.detach().numpy()
    tensor = getattr(features, "pooler_output", None)
    if tensor is None:  # 结构再变时的兜底：取 CLS token（pooler 无投影，语义近似）
        tensor = features.last_hidden_state[:, 0, :]
    return tensor.detach().numpy()


class ChineseClipEmbedder:
    """
    真实 CLIP 模型的懒加载封装（结构对齐 SentenceTransformerEmbedder）。

    ChineseCLIP 是"双塔"结构：图像塔与文本塔把两种模态投影进**同一个**向量空间
    ——这正是"红塔与小花"文本搜图的原理：查询文本走文本塔，得到的向量可以
    直接与图片塔产出的图片向量算余弦。
    """

    def __init__(self, spec: ModelSpec):
        self._spec = spec
        self._model = None
        self._processor = None
        self._lock = threading.Lock()
        self.load_error: str | None = None

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def load(self) -> bool:
        """尝试加载模型。成功返回 True；失败记录原因并返回 False（降级策略由上层决定）"""
        if self._model is not None:
            return True
        with self._lock:
            if self._model is not None:  # 双检：另一线程可能刚加载完
                return True
            try:
                from PIL import Image  # noqa: F401  （解码依赖存在性随模型一起验证）
                from transformers import ChineseCLIPModel, ChineseCLIPProcessor
                # 【原子性 · e2e 实测教训】先在局部变量里把两个对象都加载完再一起提交——
                # 逐个赋值时 processor 加载失败（如网络抖动）会留下"model 有、processor 无"
                # 的半初始化状态，而 loaded 只检查 _model，随后 embed_images 里
                # self._processor(...) 直接 TypeError 崩掉整次调用（连锁触发 Java 侧
                # 通道回退）。要么都在，要么都不在。
                model = ChineseCLIPModel.from_pretrained(self._spec.model_name)
                processor = ChineseCLIPProcessor.from_pretrained(self._spec.model_name)
                self._model = model
                self._processor = processor
                return True
            except Exception as exc:  # 模型缺失/网络失败/依赖缺失等一切异常
                self.load_error = f"{type(exc).__name__}: {exc}"
                return False

    @staticmethod
    def _read_image(path: str):
        """读取并校验图片。损坏/不存在 → 抛 ValueError（数据错误，不属于降级范畴）"""
        from PIL import Image
        try:
            return Image.open(path).convert("RGB")
        except Exception as exc:
            raise ValueError(f"图片无法解码: {path} ({type(exc).__name__}: {exc})") from exc

    def embed_images(self, paths: list[str]) -> np.ndarray:
        """批量图片编码。调用方必须先确认 loaded=True（用 load() 的返回值判断）"""
        images = [self._read_image(p) for p in paths]
        inputs = self._processor(images=images, return_tensors="pt")
        features = self._model.get_image_features(**inputs)
        # CLIP 原始输出未归一化；归一化后「点积 == 余弦」，与文本引擎约定一致
        return _l2_normalize(_features_to_numpy(features).astype(np.float32))

    def embed_texts(self, texts: list[str]) -> np.ndarray:
        """批量查询文本编码（文本塔）——输出与图片向量同空间，跨模态检索的查询侧入口"""
        inputs = self._processor(text=texts, return_tensors="pt", padding=True, truncation=True)
        features = self._model.get_text_features(**inputs)
        return _l2_normalize(_features_to_numpy(features).astype(np.float32))


class HashCrossEmbedder:
    """
    确定性降级编码器：CLIP 模型不可用时的兜底。

    - 图片：文件字节按 4KB 块 → 每块 sha256 种子生成向量 → 求和 → L2 归一化
    - 文本：分词 → 每词 sha256 种子生成向量 → 求和 → L2 归一化（与文本引擎 HashEmbedder 同族）

    性质：确定性（同图/同文永远同向量）；明确降级（degraded=True 必须透传到前端）。
    局限：图片与文本的哈希向量近乎正交——降级模式下跨模态检索基本无命中，
    这正是 CLIP 模型的价值所在；但服务不崩溃、行为可预测。
    """

    def __init__(self, dim: int):
        self._dim = dim

    def _seed_vector(self, seed_bytes: bytes) -> np.ndarray:
        """由哈希字节生成确定性向量（同种子同向量；种子取 sha256 前 4 字节防超界）"""
        seed = int.from_bytes(seed_bytes, "little")
        rng = np.random.RandomState(seed)
        return rng.standard_normal(self._dim)

    def embed_image_bytes(self, payload: bytes) -> np.ndarray:
        acc = np.zeros(self._dim, dtype=np.float32)
        for offset in range(0, max(len(payload), 1), 4096):
            chunk = payload[offset:offset + 4096]
            acc += self._seed_vector(hashlib.sha256(chunk).digest()[:4])
        return acc

    def embed_images(self, paths: list[str]) -> np.ndarray:
        rows = []
        for path in paths:
            with open(path, "rb") as f:  # 读失败自然抛 OSError——数据错误不属于降级范畴
                rows.append(self.embed_image_bytes(f.read()))
        return _l2_normalize(np.vstack(rows)) if rows else np.zeros((0, self._dim), dtype=np.float32)

    def embed_texts(self, texts: list[str]) -> np.ndarray:
        # 复用文本引擎的确定性哈希（同 dim 下文本侧行为与 HashEmbedder 一致）
        from .embeddings import HashEmbedder
        return HashEmbedder(self._dim).embed(texts)


class VisionService:
    """
    对外统一门面：自动选择 真实 CLIP 模型 → 降级哈希（结构对齐 EmbeddingService）。

    图片编码与查询文本编码共用同一套加载/降级状态——它们本就是同一个模型的
    两座塔，分开管理会出现"图片塔降级了文本塔还装作正常"的荒谬状态。
    """

    def __init__(self, model_key: str = CLIP_KEY):
        self._spec: ModelSpec = get_spec(model_key)
        self._real = ChineseClipEmbedder(self._spec)
        self._fallback = HashCrossEmbedder(self._spec.dimension)
        self._load_attempted = False

    def _try_load_real(self) -> bool:
        """整个进程生命周期里，真实模型只尝试加载一次（失败通常是环境问题，重试无意义还拖慢请求）"""
        if not self._load_attempted:
            self._load_attempted = True
            self._real.load()
        return self._real.loaded

    def _wrap(self, vectors: np.ndarray, degraded: bool) -> EmbedBatch:
        return EmbedBatch(
            vectors=vectors.astype(float).tolist(),
            dim=self._spec.dimension,
            model=self._spec.model_name if not degraded else f"hash-fallback({self._real.load_error})",
            degraded=degraded,
        )

    def embed_images(self, paths: list[str]) -> EmbedBatch:
        """批量图片编码（Java 摄取主链路调用的入口）。图片不可解码会抛 ValueError"""
        if not paths:
            raise ValueError("paths 不能为空")
        if self._try_load_real():
            return self._wrap(self._real.embed_images(paths), degraded=False)
        return self._wrap(self._fallback.embed_images(paths), degraded=True)

    def embed_query_texts(self, texts: list[str]) -> EmbedBatch:
        """批量查询文本编码（跨模态检索的查询侧入口）——输出与图片向量同空间"""
        texts = [t if t and t.strip() else " " for t in texts]
        if self._try_load_real():
            return self._wrap(self._real.embed_texts(texts), degraded=False)
        return self._wrap(self._fallback.embed_texts(texts), degraded=True)

    def status(self) -> dict:
        """给 /system/health 的状态快照（结构对齐文本引擎，前端可同构渲染）"""
        # 先触发加载判定再取 loaded 快照——否则首次 status 会显示
        # real_model_loaded=False 但 degraded=False 的自相矛盾（字段求值顺序坑）
        degraded = not self._try_load_real()
        return {
            "model_key": self._spec.key,
            "model_name": self._spec.model_name,
            "dimension": self._spec.dimension,
            "real_model_loaded": self._real.loaded,
            "load_error": self._real.load_error,
            "degraded": degraded,
        }


# —— 模块级单例：两个入口共享同一个服务实例（模型只加载一份）——
service = VisionService()
