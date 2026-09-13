import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import DashboardPage from './pages/dashboard/DashboardPage'
import DocumentsPage from './pages/documents/DocumentsPage'
import ImagesPage from './pages/images/ImagesPage'
import SearchPage from './pages/search/SearchPage'
import ImageSearchPage from './pages/search/ImageSearchPage'
import RequirementListPage from './pages/requirements/RequirementListPage'
import RequirementFormPage from './pages/requirements/RequirementFormPage'
import RequirementWorkshopPage from './pages/requirements/RequirementWorkshopPage'
import RequirementExportPage from './pages/requirements/RequirementExportPage'

/**
 * 应用根组件 —— 路由表唯一登记处。
 *
 * 【教学注释 · 嵌套路由的写法】
 * <Route path="/" element={<AppLayout />}> 把布局挂在根路径，
 * 子路由渲染进布局的 <Outlet />——布局只挂载一次，切页面不闪不跳。
 * 未知路径重定向到仪表盘（Navigate 组件是"声明式跳转"）。
 */
function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<AppLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="documents" element={<DocumentsPage />} />
          <Route path="images" element={<ImagesPage />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="search/images" element={<ImageSearchPage />} />
          <Route path="requirements" element={<RequirementListPage />} />
          <Route path="requirements/new" element={<RequirementFormPage />} />
          <Route path="requirements/:id" element={<RequirementWorkshopPage />} />
          <Route path="requirements/:id/edit" element={<RequirementFormPage />} />
          <Route path="requirements/:id/export" element={<RequirementExportPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
