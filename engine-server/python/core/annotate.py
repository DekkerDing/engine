# -*- coding: utf-8 -*-
"""
annotate.py — 图片语义拆分（结构化标注：主题 / 描述 / 标签）

【本期实现：确定性 mock】
设计纪律逐条对齐 vision.py / embeddings.py 的显式降级传统：
  1. 一切标注都带 mocked 标志——mock 实现必须显式声明，绝不伪装真实识别
  2. mock 是"确定性纯函数"：同输入（图片路径+caption）永远同输出（可测试、可复现）
  3. 零网络、零模型下载——不依赖 HF，不付任何权重代价
  4. 真实 VLM 到位后：registry 的 vlm 槽位换实现即可，本模块的输出契约
     （subject / description / tags / mocked 四字段）与双通道协议零改动

【mock 规则】（design D1）
  - caption 存在：description = caption 原文；subject/tags 由该文本确定性派生
  - caption 缺失：从文件名派生——清洗时间戳/设备前缀（IMG_20240101_123456、
    Screenshot_...、mmexport...、wx_camera... 等）后取剩余语义片段；
    无语义片段时 description = "未命名照片（{文件名}）"（检索价值有限，属预期行为）
  - subject：jieba 词性标注取名词性词（去重，最多 3 个、"、"连接）——中心词后置是
    中文常态，全名词保留比"猜最后一个"对演示语义更友好（"红塔与白云"→"红塔、白云"）
  - tags：复用 tokenizer.keywords（TF-IDF，top 5）
"""
from __future__ import annotations

import os
import re
from pathlib import Path

import jieba.posseg

from .registry import get_spec
from .tokenizer import keywords as extract_keywords

VLM_KEY = "vlm"

# 文件名里的"非语义"模式：设备/系统生成的前缀与时间戳（大小写不敏感）
_NOISE_PATTERNS = [
    re.compile(r"img[_\-]?\d{8}[_\-]?\d{6}", re.IGNORECASE),      # IMG_20240101_123456
    re.compile(r"dsc[_\-]?\d+", re.IGNORECASE),                    # DSC01234
    re.compile(r"screenshot[_\-]?\d{8}[\-_]\d{6}", re.IGNORECASE),  # Screenshot_20240101-123456
    re.compile(r"mmexport\d{10,}", re.IGNORECASE),                 # mmexport1700000000000
    re.compile(r"wx[_\-]?camera[_\-]?\d+", re.IGNORECASE),        # wx_camera_1700000000000
    re.compile(r"pexels[_\-]?\d+", re.IGNORECASE),                 # pexels-12345
    re.compile(r"\d{4}[_\-]?\d{2}[_\-]?\d{2}", re.IGNORECASE),      # 裸日期 2024-01-01 / 20240101
    re.compile(r"\d{2}[_\-:]?\d{2}[_\-:]?\d{2}", re.IGNORECASE),    # 裸时间 12-30-45
]

# 名词性词性前缀（jieba 词性标注约定：n 系名词、vn 动名词、ENG 英文词）
_NOUN_FLAGS = ("n", "vn", "eng")

# 输出契约的四字段（真实 VLM 替换时必须保持同名同义）
FIELDS = ("subject", "description", "tags", "mocked")


def _clean_filename(stem: str) -> str:
    """清洗文件名中的噪声模式，返回剩余语义片段（可能为空串）"""
    cleaned = stem
    for pattern in _NOISE_PATTERNS:
        cleaned = pattern.sub("", cleaned)
    # 分隔符归一为空格，压缩空白
    return re.sub(r"[_\-\.]+", " ", cleaned).strip()


def _derive_subject(text: str, limit: int = 3) -> str:
    """从描述文本提取主题：名词性词去重（保序），最多 limit 个、"、"连接"""
    seen: list[str] = []
    for word, flag in jieba.posseg.lcut(text):
        if len(word.strip()) < 2:  # 单字/标点不构成主题词
            continue
        if not flag.startswith(_NOUN_FLAGS):
            continue
        if word not in seen:
            seen.append(word)
        if len(seen) >= limit:
            break
    return "、".join(seen) if seen else text.strip()[:12]


def _derive_tags(text: str, top_k: int = 5) -> list[str]:
    """标签 = TF-IDF 关键词（复用 tokenizer 既有能力，确定性）"""
    return [k.term for k in extract_keywords(text, top_k)]


def annotate(path: str, caption: str | None = None, filename: str | None = None) -> dict:
    """
    对单张图片产出结构化标注。纯函数：同 (path, filename, caption) 永远同输出。

    path     : 图片存储路径（mock 只用文件名，不读像素——真实 VLM 才需要像素）
    caption  : 可选的调用方覆盖描述（上传入参）。空串/空白视为未提供。
    filename : 调用方的原始文件名（上传时的名字）。存储层的原件常以 uuid 重命名，
               文件名派生必须用它而非 path 的 stem——否则派生出的是无语义的
               uuid 片段（5.3 缩样实测暴露的缺陷）。缺省回退 path stem（兼容旧调用）。
    """
    spec = get_spec(VLM_KEY)  # 槽位未启用时在入口处爆发 KeyError（沿 registry 纪律）
    if caption is not None and caption.strip():
        description = caption.strip()
        subject, tags = _derive_subject(description), _derive_tags(description)
    else:
        stem = Path(filename).stem if filename and filename.strip() else Path(path).stem
        cleaned = _clean_filename(stem)
        if cleaned:
            description = cleaned
            subject, tags = _derive_subject(description), _derive_tags(description)
        else:
            # 文件名无语义片段：描述保留提示文案，主题/标签置空——
            # 对"未命名照片（IMG_20240101_123456）"这类文本做 TF-IDF 只会产出
            # 数字垃圾词，不如诚实地不给标签（检索价值有限是声明过的预期行为）
            description = f"未命名照片（{stem}）"
            subject, tags = "", []
    return {
        "subject": subject,
        "description": description,
        "tags": tags,
        "mocked": True,  # 本实现恒为 True；真实 VLM 替换后恒为 False
    }


def status() -> dict:
    """给 getStats / health 用的状态快照：如实报告自己是 mock 实现"""
    spec = get_spec(VLM_KEY)
    return {
        "model_key": spec.key,
        "implementation": spec.model_name,
        "mocked": True,
        "deterministic": True,
        "fields": list(FIELDS),
    }


# —— 模块级单例约定（与 vision.service 对齐：入口共享同一状态；本实现无状态，仅占位语义）——
service = type("MockAnnotationService", (), {
    "annotate": staticmethod(annotate),
    "status": staticmethod(status),
})()
