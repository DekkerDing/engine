/**
 * 高亮片段渲染 —— 把后端 highlight 字段（含 <em>标记</em> 的纯文本）
 * 解析为 React 节点。
 *
 * 【教学注释 · 为什么不用 dangerouslySetInnerHTML】
 * 片段文本来自用户上传的文档内容——如果文档里藏了 <script>，直接
 * innerHTML 就是存储型 XSS。解析成 React 节点后，文本一律走 React 的
 * 自动转义，<em> 是我们自己重建的标记，不是原文里的。
 *
 * 【实现思路】按 <em> 与 </em> 切分字符串：偶数段是普通文本，奇数段是
 * 命中词——用最朴素的 split，不需要正则库。
 */
export function HighlightText({ highlight }: { highlight: string }) {
  const parts = highlight.split(/(<em>|<\/em>)/)
  let inEm = false
  const nodes: Array<{ text: string; hit: boolean }> = []
  for (const part of parts) {
    if (part === '<em>') {
      inEm = true
    } else if (part === '</em>') {
      inEm = false
    } else if (part) {
      nodes.push({ text: part, hit: inEm })
    }
  }

  return (
    <>
      {nodes.map((node, i) =>
        node.hit ? (
          <mark key={i} className="highlight-text__hit">{node.text}</mark>
        ) : (
          <span key={i}>{node.text}</span>
        ),
      )}
    </>
  )
}
