// ============================================================
// go.mod — Go 工具箱引擎的"户口本"（对齐 Gradle 的 settings.gradle 角色）
// ------------------------------------------------------------
// 【教学注释 · Go 模块 vs Gradle 工程】
//   Gradle: settings.gradle 声明模块、build.gradle 声明依赖坐标
//   Go    : go.mod 一站式——module 路径是本工程的全局唯一名，
//           require 区块列第三方依赖（本工程刻意为零，见下）
//
// 【零三方依赖策略（design D8）】标准库已覆盖本引擎全部需要：
//   crypto/sha256、bufio、encoding/json、encoding/base64、sync、os、path/filepath
//   → go build 完全离线可跑（无 go.sum、无网络下载），
//     与仓库"不稳定网络下断点续传"的工作约束对齐。
//
// 【版本下限】go 1.21：需要 slices 包与泛型教学价值（design 开放问题 Q2 落定：
//   下限钉 1.21——开发机实测 1.27，下限放宽到 1.21 兼顾旧节点）。
// ============================================================
module engine/gotoolbox

go 1.21
