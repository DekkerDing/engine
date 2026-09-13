import { Menu } from 'antd'
import {
  DashboardOutlined,
  FileTextOutlined,
  PictureOutlined,
  ProfileOutlined,
  SearchOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { DegradedBanner } from '../components/DegradedBanner'
import './AppLayout.css'

/**
 * 全站布局骨架 —— 顶栏 + 侧边导航 + 内容区（CSS Grid 实现，见 AppLayout.css）。
 *
 * 【教学注释 · Outlet 是什么】
 * <Outlet /> 是 React Router 的"占位符"：布局组件包住它，路由匹配到的
 * 子页面（仪表盘/文档/检索）就渲染在占位处。这就是"切换页面时顶栏
 * 侧边不动、只有内容区变"的实现原理——布局根本不重新挂载。
 *
 * 【教学注释 · 导航状态与路由同步】
 * 菜单选中项不能自己维护 state，要以 location.pathname 为唯一事实源
 * （浏览器后退/前进时 pathname 变了，菜单高亮必须跟着变）。
 */
function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()

  return (
    <div className="app-layout">
      <header className="app-topbar">
        <h1 className="app-topbar__title">文本向量化检索系统</h1>
        <div className="app-topbar__spacer" />
        {/* 全局状态区：5.6 的降级横幅、健康点将挂在这里附近 */}
      </header>

      <aside className="app-sider">
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          style={{ borderInlineEnd: 'none' }}
          items={[
            { key: '/', icon: <DashboardOutlined />, label: '仪表盘' },
            { key: '/documents', icon: <FileTextOutlined />, label: '文档管理' },
            { key: '/images', icon: <PictureOutlined />, label: '图片管理' },
            { key: '/search', icon: <SearchOutlined />, label: '语义检索' },
            { key: '/search/images', icon: <PictureOutlined />, label: '图片检索' },
            { type: 'group', label: '需求工厂' },
            { key: '/requirements', icon: <ProfileOutlined />, label: '需求列表' },
            { key: '/requirements/new', icon: <ThunderboltOutlined />, label: '提交需求' },
          ]}
          onClick={({ key }) => navigate(key)}
        />
      </aside>

      <main className="app-main">
        {/* 降级横幅：全站可见（引擎降级期间持续提示，恢复后自动消失） */}
        <DegradedBanner />
        <div className="app-main__inner">
          <Outlet />
        </div>
      </main>
    </div>
  )
}

export default AppLayout
