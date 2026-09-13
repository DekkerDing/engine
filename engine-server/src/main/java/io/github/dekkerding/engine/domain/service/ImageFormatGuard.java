package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.exception.EngineException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 图片格式守卫 —— 扩展名白名单 + 魔数双重校验（spec：按魔数而非仅扩展名）。
 *
 * <p>【教学注释 · 为什么扩展名不可信】把 virus.exe 改名 virus.jpg 是最古老的
 * 上传攻击。魔数（文件头字节签名）是格式本身的指纹，改名字改不掉它。
 * 双重校验 = 白名单挡"没申报的格式"，魔数挡"申报了但撒谎的格式"。
 *
 * <p>【教学注释 · 为什么放 domain.service】纯字节判断、零基础设施依赖——
 * 这是业务规则（"什么算合法图片"），不是技术细节，单测无需任何 mock。
 */
public class ImageFormatGuard {

    /** 白名单：与前端 accept、Python PIL 可解码能力三方一致 */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "bmp", "gif"));

    /**
     * 校验并返回规范化扩展名（小写、不含点）——落盘文件名用它，
     * 避免"上传 a.JPG 存成 a.JPG、检索时找 a.jpg"的大小写裂缝。
     *
     * @throws EngineException 400：无扩展名 / 白名单外格式 / 魔数不符
     */
    public String validateAndNormalize(String filename, byte[] content) {
        if (filename == null || filename.trim().isEmpty()) {
            throw EngineException.badRequest("文件名不能为空");
        }
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw EngineException.badRequest(
                    "不支持的图片类型: " + filename + "（支持: jpg / jpeg / png / webp / bmp / gif）");
        }
        if (content == null || content.length < 4) {
            throw EngineException.badRequest("文件内容为空或过短，无法识别图片格式: " + filename);
        }
        if (!matchesMagic(ext, content)) {
            throw EngineException.badRequest(
                    "文件内容与扩展名不符（魔数校验失败）: " + filename + "——请确认是真实的 " + ext + " 图片");
        }
        return ext;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------- 魔数签名表 ----------
    // jpg: FF D8 FF（JPEG SOI + JFIF/Exif 起始）；png: 89 50 4E 47；
    // gif: "GIF8"（87a/89a 前缀共同部分）；bmp: 42 4D（"BM"）；
    // webp: "RIFF"...."WEBP"（RIFF 容器，第 8-11 字节标负载格式）

    private boolean matchesMagic(String ext, byte[] content) {
        switch (ext) {
            case "jpg":
            case "jpeg":
                return (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                        && (content[2] & 0xFF) == 0xFF;
            case "png":
                return (content[0] & 0xFF) == 0x89 && content[1] == 0x50
                        && content[2] == 0x4E && content[3] == 0x47;
            case "gif":
                return content[0] == 'G' && content[1] == 'I' && content[2] == 'F' && content[3] == '8';
            case "bmp":
                return content[0] == 0x42 && content[1] == 0x4D;
            case "webp":
                return content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                        && content.length >= 12
                        && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
            default:
                return false;
        }
    }
}
