# -*- coding: utf-8 -*-
"""
tokenizer.py — 中文分词与关键词提取（jieba 封装）

【教学注释 · 为什么向量化引擎还需要分词】
两条用途：
  1. 关键词检索（Lucene 侧对照实验 + QA 关键词提取）
  2. 降级模式的哈希向量分词（见 embeddings.HashEmbedder）
蓝本 576 行的 jieba 封装裁剪为最常用的能力，教学聚焦。
"""
from __future__ import annotations

from typing import NamedTuple

# jieba 的词典构建有一次性开销（~0.8s），模块导入即触发首次构建，后续调用走缓存
import jieba
import jieba.analyse


class Keyword(NamedTuple):
    term: str
    weight: float


def tokenize(text: str, mode: str = "precise") -> list[str]:
    """
    中文分词。
    mode:
      - precise 精确模式（默认）：最适合搜索引擎建索引
      - search  搜索引擎模式：长词再细切，召回更高但索引更大
    停用词/空白过滤在 Java Lucene 侧还有一层，这里只做基本清洗。
    """
    if mode == "search":
        words = jieba.cut_for_search(text)
    else:
        words = jieba.lcut(text)
    return [w.strip() for w in words if w.strip()]


def keywords(text: str, top_k: int = 10) -> list[Keyword]:
    """
    TF-IDF 关键词提取（jieba.analyse.extract_tags）。
    【教学注释 · TF-IDF 一句话】一个词在这段文本里出现得越多（TF 高）、
    在通用语料里越罕见（IDF 高），它就越能代表这段文本。
    QA 模块用它把用户问题转成检索关键词。
    """
    pairs = jieba.analyse.extract_tags(text, topK=top_k, withWeight=True)
    return [Keyword(term=t, weight=round(float(w), 6)) for t, w in pairs]
