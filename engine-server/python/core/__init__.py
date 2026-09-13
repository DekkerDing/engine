# -*- coding: utf-8 -*-
"""
core 包 —— Python 引擎的业务核心。

【教学注释 · 包结构】
两个入口（server_py4j.py / server_stdio.py）只做"协议适配"，
真正的向量化/分词逻辑全部在这个 core/ 包里——
这样新增一种通信协议（比如 gRPC）时，业务代码零改动，只加一个入口文件。

这就是"端口-适配器"思想在 Python 侧的镜像：
Java 侧有 PythonChannel 抽象，Python 侧有 core/ 与入口的分离。
"""
