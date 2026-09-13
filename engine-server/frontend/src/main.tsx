// react-jsx 模式下无需 import React（编译器自动注入 JSX 运行时）
import ReactDOM from 'react-dom/client'
import App from './App'

// 【教学注释 · 为什么不用 StrictMode】
// React 18 严格模式在开发态会双重执行渲染/副作用（为帮助发现不纯代码），
// 与 AntD 5 部分组件的副作用存在已知摩擦；教学项目以"行为可预期"优先，
// 从非严格模式起步，docs/frontend-standards.md 记录了原因与后续升级路径。
ReactDOM.createRoot(document.getElementById('root')!).render(<App />)
