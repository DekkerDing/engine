# -*- coding: utf-8 -*-
"""
server_stdio.py — stdio 通道入口（JSON 行协议）

【协议规范】（Java StdioChannel.java 与本文件是同一协议的两端，改动必须同步）
  请求: {"id": 1, "op": "embed", "payload": {...}}          （一行 UTF-8 JSON + \\n）
  响应: {"id": 1, "ok": true, "payload": {...}}             （一行 UTF-8 JSON + \\n）
  错误: {"id": 1, "ok": false, "error": {"type": "...", "message": "...", "retryable": false}}

铁律：
  1. stdout 只许输出协议行——任何日志/打印一律走 stderr（混入即协议损坏）
  2. 未知 op 必须回错误信封，禁止静默吞掉（否则 Java 侧等到超时）
  3. 每行处理完立即 flush（管道是全缓冲的，不 flush 对端永远读不到）

【教学价值】这个通道可以用命令行直接手测，不需要写一行 Java：
  echo {"id":1,"op":"ping","payload":{}} | python server_stdio.py

【为什么一问一答足够】JDK 8 没有虚拟线程，Java 侧用互斥锁串行化请求即可；
批量接口（embed 一次传一批文本）已把吞吐压力消化在单请求内部。
"""
from __future__ import annotations

import json
import sys

from core.annotate import service as annotation_service
from core.embeddings import service as embedding_service
from core.rerank import service as rerank_service
from core.registry import describe as registry_describe
from core.tokenizer import keywords as extract_keywords
from core.tokenizer import tokenize as tokenize_text
from core.vision import service as vision_service


def _err(exc_type: str, message: str, retryable: bool = False) -> dict:
    return {"ok": False, "error": {"type": exc_type, "message": message, "retryable": retryable}}


def dispatch(op: str, payload: dict) -> dict:
    """op 分发器：纯函数（同请求同响应），返回 payload 部分或抛异常"""
    if op == "ping":
        return {"alive": True}

    if op == "stats":
        return {
            "registry": registry_describe(),
            "embedding": embedding_service.status(),
            "vision": vision_service.status(),
            "annotation": annotation_service.status(),
            "reranker": rerank_service.status(),
        }

    if op == "embed":
        texts = list(payload.get("texts", []))
        if not texts:
            raise ValueError("payload.texts 不能为空")
        batch = embedding_service.embed_texts(texts)
        return {
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }

    if op == "embed_images":
        paths = list(payload.get("paths", []))
        if not paths:
            raise ValueError("payload.paths 不能为空")
        batch = vision_service.embed_images(paths)
        return {
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }

    if op == "embed_clip_query":
        # 跨模态检索的查询侧：文本经 CLIP 文本塔编码，输出与图片向量同空间
        texts = list(payload.get("texts", []))
        if not texts:
            raise ValueError("payload.texts 不能为空")
        batch = vision_service.embed_query_texts(texts)
        return {
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }

    if op == "annotate":
        # 图片语义拆分（mock 先行）：path 必填；filename 原始文件名（mock 派生用，
        # 存储路径是 uuid 命名）；caption 可选（空/缺省 = 未提供覆盖描述）
        path = payload.get("path", "")
        if not path:
            raise ValueError("payload.path 不能为空")
        filename = payload.get("filename")
        caption = payload.get("caption")
        cap = caption if caption and str(caption).strip() else None
        fname = filename if filename and str(filename).strip() else None
        return annotation_service.annotate(path, cap, fname)

    if op == "rerank":
        # 检索重排：query × candidates 逐对打分，返回等长分数列表（顺序一致）
        query = payload.get("query", "")
        candidates = list(payload.get("candidates", []))
        if not query:
            raise ValueError("payload.query 不能为空")
        if not candidates:
            raise ValueError("payload.candidates 不能为空")
        return {"scores": rerank_service.rerank(query, [str(c) for c in candidates])}

    if op == "tokenize":
        text = payload.get("text", "")
        mode = payload.get("mode", "precise")
        return {"tokens": tokenize_text(text, mode)}

    if op == "keywords":
        text = payload.get("text", "")
        top_k = int(payload.get("top_k", 10))
        return {"keywords": [{"term": k.term, "weight": k.weight} for k in extract_keywords(text, top_k)]}

    raise LookupError(f"未知 op: {op}")


def main() -> int:
    # stderr 打日志（stdout 是协议专线）
    print("[stdio] python engine ready, waiting for requests...", file=sys.stderr, flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            req_id = request.get("id")
            op = request.get("op", "")
            payload = request.get("payload") or {}
            response = {"id": req_id, "ok": True, "payload": dispatch(op, payload)}
        except json.JSONDecodeError as exc:
            # 行损坏也要回信封（id 未知给 null），Java 侧才能立刻失败而不是等超时
            response = {"id": None, **_err("BadRequest", f"JSON 解析失败: {exc}")}
        except Exception as exc:
            response = {"id": request.get("id") if isinstance(request, dict) else None,
                        **_err(type(exc).__name__, str(exc))}
        # ensure_ascii=False 让中文原样输出（体积更小、可读）；flush 立即送达
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
