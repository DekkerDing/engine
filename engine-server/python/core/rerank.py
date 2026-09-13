# -*- coding: utf-8 -*-
"""
rerank.py — 检索重排（cross-encoder：query × 候选文本 → 相关性分数）

设计纪律逐条对齐 vision.py / embeddings.py：
  1. 惰性加载 + 双检锁：纯导入/纯浏览场景不付出 ~1.1GB 权重内存
  2. 【与降级向量相反的错误语义】向量编码"模型不可用→降级哈希"是对的（服务仍可用）；
     重排不行——伪造的分数会把检索排序带向错误方向。模型不可用 MUST 抛显式错误，
     由 Java 调用方决定降级（reranked=false 直通），而不是返回假分数装作重排成功。
  3. 候选数硬上限：超限抛 ValueError（调用方分批），绝不静默截断——静默截断会让
     调用方误以为拿到了全量重排结果
  4. 整个进程生命周期只尝试加载一次（失败通常是环境问题，重试无意义还拖慢请求）

【为什么是 bge-reranker-base】CPU 单对约 50-100ms，检索默认候选 20 → 1-2s 预算内；
large 精度增益小、延迟×3，照片检索场景不划算（design D3 备选与否决记录）。
"""
from __future__ import annotations

import threading

from .registry import get_spec

RERANKER_KEY = "reranker"

# 单次重排调用的候选数硬上限（design D3：默认送排 20、封顶 50）
MAX_CANDIDATES = 50

# HF 镜像（与 vision.py 同款环境约定：国内网络优先走 hf-mirror）
import os
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")


class CrossEncoderReranker:
    """真实重排模型的懒加载封装（结构对齐 ChineseClipEmbedder）"""

    def __init__(self, spec):
        self._spec = spec
        self._model = None
        self._lock = threading.Lock()
        self.load_error: str | None = None

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def load(self) -> bool:
        """尝试加载模型。成功 True；失败记录原因并返回 False（错误语义由上层决定）"""
        if self._model is not None:
            return True
        with self._lock:
            if self._model is not None:  # 双检
                return True
            try:
                from sentence_transformers import CrossEncoder
                # apply_activation=False：bge-reranker 输出原始 logits，排序语义即单调，
                # 不做 sigmoid——排序不变、数值保序，且省一次全域归一的语义误导
                self._model = CrossEncoder(self._spec.model_name)
                return True
            except Exception as exc:
                self.load_error = f"{type(exc).__name__}: {exc}"
                return False


class RerankService:
    """对外统一门面（结构对齐 VisionService：一次加载判定 + 显式错误）"""

    def __init__(self, model_key: str = RERANKER_KEY):
        self._spec = get_spec(model_key)
        self._real = CrossEncoderReranker(self._spec)
        self._load_attempted = False

    def _try_load_real(self) -> bool:
        if not self._load_attempted:
            self._load_attempted = True
            self._real.load()
        return self._real.loaded

    def rerank(self, query: str, candidates: list[str]) -> list[float]:
        """
        对 (query, candidates) 逐对打分。返回与 candidates 等长的分数列表（顺序一致）。

        - 候选为空 → 空列表（合法：召回为空时 Java 侧不会走到这里，防御性支持）
        - 候选超上限 → ValueError（调用方分批）
        - 模型不可用 → RuntimeError（显式；调用方走"不重排"降级，绝不伪造分数）
        """
        if not candidates:
            return []
        if len(candidates) > MAX_CANDIDATES:
            raise ValueError(f"重排候选数 {len(candidates)} 超过上限 {MAX_CANDIDATES}（请分批）")
        if not self._try_load_real():
            raise RuntimeError(f"重排模型不可用: {self._real.load_error}")
        pairs = [(query, c if c and c.strip() else " ") for c in candidates]
        scores = self._real._model.predict(pairs)
        return [round(float(s), 6) for s in scores]

    def status(self) -> dict:
        """给 getStats / health 的状态快照（不触发加载——重排未用时不应付出加载成本）"""
        return {
            "model_key": self._spec.key,
            "model_name": self._spec.model_name,
            "max_candidates": MAX_CANDIDATES,
            "real_model_loaded": self._real.loaded,
            "load_error": self._real.load_error,
            # 注意：这里与 vision.status 不同——不主动触发 _try_load_real()，
            # 因为 health 探测不应引发 1.1GB 的意外加载；首次真实重排请求才加载。
            "available": None if not self._load_attempted else self._real.loaded,
        }


# —— 模块级单例：两个入口共享同一个服务实例（模型只加载一份）——
service = RerankService()
