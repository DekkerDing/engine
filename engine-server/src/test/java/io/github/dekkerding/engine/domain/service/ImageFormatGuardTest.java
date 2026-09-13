package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片格式守卫单测 —— 任务 3.2 的校验面：
 * 白名单、魔数双重校验、扩展名规范化。
 *
 * <p>【测试字节设计】只造魔数头 + 少量填充——校验逻辑只看文件头，
 * 测试数据也不该假装自己是完整图片。
 */
class ImageFormatGuardTest {

    private final ImageFormatGuard guard = new ImageFormatGuard();

    private static byte[] bytes(int... unsigned) {
        byte[] result = new byte[unsigned.length];
        for (int i = 0; i < unsigned.length; i++) {
            result[i] = (byte) unsigned[i];
        }
        return result;
    }

    /** 在魔数头后补零到 32 字节（过"内容过短"防线） */
    private static byte[] padded(int... head) {
        byte[] result = new byte[32];
        for (int i = 0; i < head.length; i++) {
            result[i] = (byte) head[i];
        }
        return result;
    }

    // ---------- 合法格式 ----------

    @Test
    void jpg魔数通过_大写扩展名规范化为小写() {
        String ext = guard.validateAndNormalize("照片.JPG", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F'));
        assertEquals("jpg", ext, ".JPG 应规范化为小写（落盘文件名一致性）");
    }

    @Test
    void jpeg扩展名与jpg同一魔数() {
        assertEquals("jpeg", guard.validateAndNormalize("照片.jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE1)));
    }

    @Test
    void png魔数通过() {
        assertEquals("png", guard.validateAndNormalize("风景.png", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)));
    }

    @Test
    void gif魔数通过() {
        assertEquals("gif", guard.validateAndNormalize("动图.gif", bytes('G', 'I', 'F', '8', '9', 'a')));
    }

    @Test
    void bmp魔数通过() {
        assertEquals("bmp", guard.validateAndNormalize("位图.bmp", bytes(0x42, 0x4D, 0, 0, 0, 0)));
    }

    @Test
    void webp魔数通过() {
        // RIFF 容器：前 4 字节 "RIFF"，第 8-11 字节 "WEBP"
        byte[] webp = padded('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
        assertEquals("webp", guard.validateAndNormalize("新格式.webp", webp));
    }

    // ---------- 拒绝路径（spec：假扩展名场景） ----------

    @Test
    void 假扩展名被魔数校验拒绝() {
        // spec 场景：把 .exe 改名为 .jpg——MZ 头（Windows PE）过不了 JPEG 魔数
        EngineException e = assertThrows(EngineException.class,
                () -> guard.validateAndNormalize("病毒.jpg", bytes('M', 'Z', 0x90, 0x00, 0x03, 0x00)));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("魔数"), "错误信息应指出魔数校验失败: " + e.getMessage());
    }

    @Test
    void 白名单外格式被拒() {
        EngineException e = assertThrows(EngineException.class,
                () -> guard.validateAndNormalize("电影.mp4", bytes(0x00, 0x00, 0x00, 0x18)));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("不支持"), "错误信息应说明格式不支持: " + e.getMessage());
    }

    @Test
    void 无扩展名与空文件名被拒() {
        assertEquals(400, assertThrows(EngineException.class,
                () -> guard.validateAndNormalize("无扩展名", bytes(0xFF, 0xD8, 0xFF))).getCode());
        assertEquals(400, assertThrows(EngineException.class,
                () -> guard.validateAndNormalize(null, bytes(0xFF, 0xD8, 0xFF))).getCode());
    }

    @Test
    void 内容过短被拒() {
        // 4 字节是最小可判定长度防线：魔数都放不下的文件谈不上是图片
        assertEquals(400, assertThrows(EngineException.class,
                () -> guard.validateAndNormalize("太短.jpg", bytes(0xFF, 0xD8))).getCode());
    }

    @Test
    void 扩展名与内容互换也被拒() {
        // png 内容起 jpg 名：白名单放行扩展名，魔数揭穿谎言
        assertEquals(400, assertThrows(EngineException.class,
                () -> guard.validateAndNormalize("撒谎.jpg", bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).getCode());
    }
}
