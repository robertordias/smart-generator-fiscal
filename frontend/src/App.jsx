import { useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { Alert, Avatar, Box, Button, Chip, Divider, Drawer, IconButton, MenuItem, TextField, Typography } from '@mui/material';
import DashboardOutlined from '@mui/icons-material/DashboardOutlined';
import BusinessOutlined from '@mui/icons-material/BusinessOutlined';
import TuneOutlined from '@mui/icons-material/TuneOutlined';
import NoteAddOutlined from '@mui/icons-material/NoteAddOutlined';
import DescriptionOutlined from '@mui/icons-material/DescriptionOutlined';
import LogoutRounded from '@mui/icons-material/LogoutRounded';
import MenuRounded from '@mui/icons-material/MenuRounded';
import ShieldOutlined from '@mui/icons-material/ShieldOutlined';
import ArrowOutwardRounded from '@mui/icons-material/ArrowOutwardRounded';
import { useAuth, useResource, WorkspaceContext } from './context';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Companies from './pages/Companies';
import Recipients from './pages/Recipients';
import Accounting from './pages/Accounting';
import FolderZipOutlined from '@mui/icons-material/FolderZipOutlined';
import PeopleOutlineRounded from '@mui/icons-material/PeopleOutlineRounded';
import FiscalSettings from './pages/FiscalSettings';
import NewDraft from './pages/NewDraft';
import Drafts from './pages/Drafts';
import FiscalCredentials from './pages/FiscalCredentials';
import NfseNotes from './pages/NfseNotes';
import VerifiedUserOutlined from '@mui/icons-material/VerifiedUserOutlined';
import ReceiptLongOutlined from '@mui/icons-material/ReceiptLongOutlined';
import { ErrorBox, Loading } from './components/Common';

const navigation = [
  { path: '/', label: 'Visão geral', icon: DashboardOutlined },
  { path: '/empresas', label: 'Empresas', icon: BusinessOutlined },
  { path: '/tomadores', label: 'Tomadores', icon: PeopleOutlineRounded },
  { path: '/configuracoes-fiscais', label: 'Padrões fiscais', icon: TuneOutlined },
  { path: '/nova-nota', label: 'Nova nota', icon: NoteAddOutlined },
  { path: '/rascunhos', label: 'Rascunhos', icon: DescriptionOutlined },
  { path: '/notas-fiscais', label: 'Notas fiscais', icon: ReceiptLongOutlined },
  { path: '/contabil', label: 'Contábil · XML', icon: FolderZipOutlined, managerOnly: true },
  { path: '/certificados', label: 'A1 e procurações', icon: VerifiedUserOutlined },
];

export function Brand({ light = false }) {
  return <div className={`brand ${light ? 'brand-light' : ''}`}><div className="brand-symbol"><span /><span /><span /></div><div><div className="brand-title">SA<span> Trindade</span></div><div className="brand-subtitle">GESTÃO PARA CONTABILIDADES</div></div></div>;
}

function Workspace() {
  const { user, logout, loggingOut } = useAuth();
  const [logoutError, setLogoutError] = useState(null);
  async function handleLogout() {
    setLogoutError(null);
    try { await logout(); } catch (error) { setLogoutError(error.message); }
  }
  const companies = useResource('/issuers');
  const [issuerId, setIssuerId] = useState('');
  const [mobile, setMobile] = useState(false);
  const location = useLocation();
  const title = navigation.find(item => item.path === location.pathname)?.label || 'Visão geral';
  const selectedIssuer = companies.data?.find(company => company.id === issuerId);
  const sidebar = <div className="sidebar-content"><Brand /><div className="workspace-label"><span className="workspace-avatar">EC</span><div><strong>Escritório contábil</strong><span>Seu espaço de trabalho</span></div></div><Typography variant="overline" className="nav-label">PRINCIPAL</Typography><nav aria-label="Navegação principal">{navigation.filter(item => !item.managerOnly || user.role === 'GESTOR').map(({ path, label, icon: Icon }) => <NavLink key={path} end={path === '/'} to={path} onClick={() => setMobile(false)} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}><Icon sx={{ fontSize: 21 }} /><span>{label}</span>{path === '/nova-nota' && <span className="nav-plus">+</span>}</NavLink>)}</nav><div className="sidebar-bottom"><div className="sidebar-help"><ShieldOutlined sx={{ color: 'primary.main', fontSize: 23 }} /><Typography variant="subtitle2" sx={{ mt: 1 }}>Uma base para crescer.</Typography><Typography variant="body2" color="text.secondary" sx={{ mt: .6, fontSize: '.75rem' }}>Organize seus emitentes e prepare documentos com os padrões do seu escritório.</Typography></div><Divider sx={{ mt: 2, mb: 1 }} /><Button fullWidth color="secondary" startIcon={<LogoutRounded />} onClick={handleLogout} disabled={loggingOut} sx={{ justifyContent: 'flex-start' }}>Sair da conta</Button></div></div>;
  return <WorkspaceContext.Provider value={{ companies, issuerId, setIssuerId, selectedIssuer, isManager: user.role === 'GESTOR' }}>
    <div className="app-shell"><aside className="sidebar">{sidebar}</aside><Drawer open={mobile} onClose={() => setMobile(false)} sx={{ '& .MuiDrawer-paper': { width: 264 } }}>{sidebar}</Drawer><div className="main-shell"><header className="topbar"><div className="breadcrumb"><IconButton className="mobile-menu" aria-label="Abrir menu" onClick={() => setMobile(true)}><MenuRounded /></IconButton><Typography variant="body2" color="text.secondary" className="breadcrumb-root">Meu escritório</Typography><span className="breadcrumb-slash">/</span><Typography variant="body2" fontWeight={600}>{title}</Typography></div><div className="header-actions"><TextField select size="small" label="Empresa ativa" slotProps={{ inputLabel: { shrink: true }, select: { displayEmpty: true } }} value={issuerId} onChange={e => setIssuerId(e.target.value)} sx={{ minWidth: { xs: 135, sm: 220 }, maxWidth: 290 }}><MenuItem value="">Todas as empresas</MenuItem>{companies.data?.map(company => <MenuItem key={company.id} value={company.id}>{company.name}</MenuItem>)}</TextField><Divider orientation="vertical" flexItem sx={{ mx: .5 }} /><Avatar sx={{ width: 36, height: 36, fontSize: '.8rem', bgcolor: '#f1e9d7', color: '#896b2f', fontWeight: 700 }}>{user.username.slice(0, 2).toUpperCase()}</Avatar><div className="user-info"><strong>{user.username}</strong><span>{user.role === 'GESTOR' ? 'Gestor do escritório' : 'Cliente / Empresa'}</span></div></div></header>
      <main className="page-content" id="conteudo">{logoutError && <Alert severity="error" sx={{ mb: 2 }}>Não foi possível encerrar a sessão: {logoutError}</Alert>}<Routes><Route path="/" element={<Dashboard />} /><Route path="/empresas" element={<Companies />} /><Route path="/tomadores" element={<Recipients />} /><Route path="/configuracoes-fiscais" element={<FiscalSettings />} /><Route path="/nova-nota" element={<NewDraft />} /><Route path="/rascunhos" element={<Drafts />} /><Route path="/notas-fiscais" element={<NfseNotes />} /><Route path="/contabil" element={<Accounting />} /><Route path="/certificados" element={<FiscalCredentials />} /><Route path="*" element={<Navigate to="/" replace />} /></Routes><footer className="page-footer"><Typography variant="caption">SA Trindade <span>·</span> Gestão com clareza.</Typography><Chip icon={<ArrowOutwardRounded sx={{ fontSize: '14px !important' }} />} label="NFS-e Nacional" size="small" variant="outlined" /></footer></main>
    </div></div>
  </WorkspaceContext.Provider>;
}

export default function App() {
  const { user, initializing, restoreError, restore } = useAuth();
  if (initializing) return <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><Loading /></Box>;
  if (restoreError) return <Box sx={{ maxWidth: 560, mx: 'auto', mt: 10, p: 3 }}><Brand /><Box sx={{ mt: 4 }}><ErrorBox error={restoreError} onRetry={restore} /></Box></Box>;
  return user ? <Workspace /> : <Login />;
}
