# -*- coding: utf-8 -*-
"""
server_py4j.py — Py4J 通道入口（默认通道）

【架构】本进程由 Java 侧 Py4jChannel 用 ProcessBuilder 拉起：
  Java(8081) ──TCP 环回 127.0.0.1:25335──> 本进程（流量不出机器、不走 HTTP）

【边界设计 · 只传 JSON 字符串】
蓝本在 Py4J 上直接传 double[][] / dict / 自定义包装对象，导致：
  - 类型映射复杂（_py4j_wrap/_py4j_unwrap 几十行胶水）
  - Python 端吞异常返回 {'error': ...} 字典 → Java 端 ClassCastException
本工程规定：跨语言边界只传 String（内容是 JSON），两端各自用 Jackson/json
反序列化成自己的强类型。协议自描述、类型安全、排查方便（肉眼可读）。

【教学注释 · 为什么 camelCase】
Py4J 的 Java 侧按"方法名精确匹配"调用 Python 对象。Java 规范是 camelCase，
所以 facade 层用 camelCase（本文件仅此一层，core/ 内部保持 snake_case）。
"""
from __future__ import annotations

import json
import logging
import os
import sys
import threading
import time

# 【版本坑】py4j 0.10.9.x 里 JavaParameters/PythonParameters 在 py4j.clientserver，
# 不在旧教程/蓝本写的 py4j.java_gateway（那里 import 会直接 ImportError）
from py4j.clientserver import ClientServer, JavaParameters, PythonParameters
from py4j.protocol import Py4JNetworkError

from core.annotate import service as annotation_service
from core.embeddings import service as embedding_service
from core.rerank import service as rerank_service
from core.registry import describe as registry_describe
from core.tokenizer import keywords as extract_keywords
from core.tokenizer import tokenize as tokenize_text
from core.vision import service as vision_service

# 日志走 stderr + 文件（stdout 在 Py4J 模式下虽不是协议专线，但也保持干净）
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger("server_py4j")


class EngineFacade:
    """
    暴露给 Java 的唯一入口对象。
    每个方法：接收简单类型/JSON 字符串 → 调 core → 返回 JSON 字符串。
    异常直接 raise（Py4J 会把它包装成 Java 侧 Py4JException——错误不吞，Java 能感知）。
    """

    # ---------- 健康与状态 ----------

    def ping(self) -> str:
        return json.dumps({"alive": True, "degraded": embedding_service.status()["degraded"]})

    def getStats(self) -> str:
        return json.dumps({
            "registry": registry_describe(),
            "embedding": embedding_service.status(),
            "vision": vision_service.status(),
            "annotation": annotation_service.status(),
            "reranker": rerank_service.status(),
        }, ensure_ascii=False)

    # ---------- 主链路：批量文本向量化 ----------

    def embedTexts(self, texts) -> str:
        # Java List<String> 经 Py4J 传入；list() 强转防御代理对象
        batch = embedding_service.embed_texts(list(texts))
        return json.dumps({
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }, ensure_ascii=False)

    # ---------- 跨模态主链路：CLIP 图片/查询文本向量化 ----------

    def embedImages(self, paths) -> str:
        batch = vision_service.embed_images(list(paths))
        return json.dumps({
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }, ensure_ascii=False)

    def embedClipQuery(self, texts) -> str:
        # 查询文本经 CLIP 文本塔编码，输出与图片向量同空间（跨模态检索的查询侧）
        batch = vision_service.embed_query_texts(list(texts))
        return json.dumps({
            "vectors": batch.vectors,
            "dim": batch.dim,
            "model": batch.model,
            "degraded": batch.degraded,
        }, ensure_ascii=False)

    # ---------- 图片语义拆分（mock 先行，真实 VLM 换槽位实现零协议改动） ----------

    def annotateImage(self, path: str, filename, caption) -> str:
        # filename: 原始文件名（mock 标注的文件名派生用它——存储路径是 uuid 命名，
        #           真实 VLM 读像素用 path，两者各取所需）
        # caption : 空串/null 表示未提供覆盖描述（Py4J null → Python None）
        cap = caption if caption and str(caption).strip() else None
        fname = filename if filename and str(filename).strip() else None
        return json.dumps(annotation_service.annotate(path, cap, fname), ensure_ascii=False)

    # ---------- 检索重排（cross-encoder，模型不可用时显式报错由 Java 侧降级） ----------

    def rerank(self, query: str, candidates) -> str:
        scores = rerank_service.rerank(str(query), [str(c) for c in list(candidates)])
        return json.dumps({"scores": scores}, ensure_ascii=False)

    # ---------- 辅助能力 ----------

    def tokenize(self, text: str, mode: str) -> str:
        return json.dumps({"tokens": tokenize_text(text, mode)}, ensure_ascii=False)

    def keywords(self, text: str, top_k: int) -> str:
        ks = extract_keywords(text, int(top_k))
        return json.dumps(
            {"keywords": [{"term": k.term, "weight": k.weight} for k in ks]},
            ensure_ascii=False,
        )


class JavaEntryPoint:
    """Py4J 约定：Java 侧 getPythonServerEntryPoint 拿到的对象（这里直接暴露 facade）"""

    def __init__(self, facade: EngineFacade):
        self._facade = facade

    def getFacade(self) -> EngineFacade:
        return self._facade


def main() -> int:
    facade = EngineFacade()
    # 监听端口由 Java 侧注入（engine.python.py4j-port → 环境变量），
    # 默认 25335——两端必须一致，端口从此真正可配置
    port = int(os.environ.get("ENGINE_PY4J_PORT", "25335"))

    # 【Windows 坑 · TIME_WAIT】前一个同名进程刚死时，其监听端口的衍生连接可能
    # 仍处 TIME_WAIT（Windows 默认数十秒），立即 bind 报 WSAEADDRINUSE（Linux 无此问题）。
    # Java 侧的健康自愈/故障切回会立刻重启本进程，若无重试会被这个窗口卡住——
    # bind 失败等 2 秒重试，最多 60 秒，与 Java 侧 startup-timeout 对齐。
    deadline = time.time() + 60
    while True:
        try:
            # 【协议细节】与蓝本一致并经其验证：
            #  - Java 侧 ClientServer 显式指定 pythonPort（默认 25334 会连不上）
            #  - auto_convert=True：Java 集合自动转 Python list
            #  - ClientServer 构造即启动监听，无 start() 方法；对不存在属性的访问会被
            #    误代理为远程调用，所以这里用 threading.Event().wait() 阻塞保活
            ClientServer(
                java_parameters=JavaParameters(auto_convert=True),
                python_parameters=PythonParameters(port=port),
                python_server_entry_point=facade,
            )
            break
        except Py4JNetworkError:
            if time.time() > deadline:
                raise
            logger.warning("端口 %d 暂不可用（TIME_WAIT？），2 秒后重试...", port)
            time.sleep(2)
    logger.info("Py4J python engine listening on 127.0.0.1:%d", port)
    threading.Event().wait()
    return 0


if __name__ == "__main__":
    sys.exit(main())
