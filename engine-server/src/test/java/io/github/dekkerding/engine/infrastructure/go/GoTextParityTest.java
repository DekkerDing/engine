package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.service.TextChunker;
import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java↔Go 对拍测试 —— 任务 3.3 的验收点（spec「分块与 Java 实现一致」+
 * 「hashing.generate 确定性」）：同参数下 Java {@link TextChunker} 与
 * Go text.chunk 的块数/块文本/序号逐条一致；Go hashing.generate 两次调用
 * 逐元素相等、normalize 模长 = 1。
 *
 * <p>【对拍策略】拉真实 Go 引擎（go run 开发轨）走完整协议链——
 * 对拍的意义就在"两个真实实现算出同一结果"，中间不能有假件。
 * 无 Go 工具链的机器 assume-skip（与 GoAssemblyGatingTest 同判定）。
 *
 * <p>【口径提醒】Java String.length() = UTF-16 单元，Go 判长已对齐
 * rune 口径（chunker.go 的 utf8.RuneCountInString）——BMP 内两者相等，
 * 对拍语料避开增补平面字符（emoji 等）即严格一致。
 */
class GoTextParityTest {

    private GoStdioChannel channel;
    private GoToolboxProvider toolbox;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(engineAvailable(), "本机无 Go 工具链/二进制，跳过对拍");
        GoProcessLauncher launcher = new GoProcessLauncher("", "go");
        channel = new GoStdioChannel(launcher::launch, 30);
        channel.start();
        toolbox = new GoToolboxProvider(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    private static boolean engineAvailable() {
        try {
            new GoProcessLauncher("", "go").resolve();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 对拍核心：Java TextChunker(400,1) vs Go text.chunk（引擎默认同参）。 */
    private void assertChunkParity(String label, String text) {
        List<TextChunk> javaChunks = new TextChunker(400, 1).chunk("parity-doc", text);
        List<GoProtocol.ChunkResult.ChunkItem> goChunks = toolbox.chunkText("parity-doc", text);

        assertEquals(javaChunks.size(), goChunks.size(),
                label + "：块数不一致（Java=" + javaChunks.size() + " Go=" + goChunks.size() + "）");
        for (int i = 0; i < javaChunks.size(); i++) {
            TextChunk jc = javaChunks.get(i);
            GoProtocol.ChunkResult.ChunkItem gc = goChunks.get(i);
            assertEquals(jc.getChunkIndex(), gc.index, label + "：第 " + i + " 块序号不一致");
            assertEquals(jc.getText(), gc.text, label + "：第 " + i + " 块文本不一致");
        }
    }

    @Test
    void 对拍_中文多句带标点() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第").append(i).append("句：红塔矗立在公园中央，塔影倒映湖面。");
        }
        assertChunkParity("多句标点", sb.toString());
    }

    @Test
    void 对拍_无标点长文硬切() {
        // 1000 汉字无标点——双端都走 appendChunk 硬切兜底（rune 修正的主验收场景）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("汉");
        }
        assertChunkParity("无标点硬切", sb.toString());
    }

    @Test
    void 对拍_中英混排() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Go language 与 Java 的 string 处理差异很大；rune 不是 byte。");
        }
        assertChunkParity("中英混排", sb.toString());
    }

    @Test
    void 对拍_短文本与单句() {
        assertChunkParity("单句", "红塔在公园。");
        assertChunkParity("两句", "第一句很短。第二句也不长。");
    }

    @Test
    void 哈希确定性_两次调用逐元素相等() {
        GoProtocol.HashResult a = toolbox.generateHash("对拍确定性样本", 128, true);
        GoProtocol.HashResult b = toolbox.generateHash("对拍确定性样本", 128, true);
        assertEquals(128, a.vector.size());
        assertEquals(a.vector.size(), b.vector.size());
        for (int i = 0; i < a.vector.size(); i++) {
            assertEquals(a.vector.get(i), b.vector.get(i), 0.0,
                    "第 " + i + " 元素两次调用不等（确定性破坏）");
        }
        assertTrue(a.degraded, "哈希向量必须带 degraded 标志");
    }

    @Test
    void 哈希归一_模长为1() {
        GoProtocol.HashResult hash = toolbox.generateHash("模长校验样本", 512, true);
        double sumSq = 0;
        for (double v : hash.vector) {
            sumSq += v * v;
        }
        assertEquals(1.0, Math.sqrt(sumSq), 1e-9, "normalize=true 后 L2 模长应为 1");
    }

    @Test
    void 哈希与embedBatch同参一致() {
        // spec：作为 EmbeddingProvider 时 embedBatch 与 hashing.generate 单条语义一致
        GoProtocol.HashResult single = toolbox.generateHash("一致性样本", 512, true);
        List<Embedding> batch = toolbox.embedBatch(
                Collections.singletonList(textResource("一致性样本")));
        assertEquals(1, batch.size());
        float[] vec = batch.get(0).getVector();
        assertEquals(single.vector.size(), vec.length);
        for (int i = 0; i < vec.length; i++) {
            assertEquals(single.vector.get(i), vec[i], 1e-7,
                    "embedBatch 与单条 hashing.generate 第 " + i + " 元素不等");
        }
    }

    /** 最小文本资源（embedBatch 只调 asText()）。 */
    private static VectorableResource textResource(String text) {
        return new VectorableResource() {
            @Override
            public String resourceId() {
                return "parity-res";
            }

            @Override
            public String sourceType() {
                return "text";
            }

            @Override
            public String asText() {
                return text;
            }
        };
    }
}
