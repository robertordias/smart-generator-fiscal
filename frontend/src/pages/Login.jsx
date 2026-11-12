import { useState } from 'react';
import { Alert, Box, Button, CircularProgress, IconButton, InputAdornment, TextField, Typography } from '@mui/material';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined';
import CheckRounded from '@mui/icons-material/CheckRounded';
import LockOutlined from '@mui/icons-material/LockOutlined';
import { useAuth } from '../context';
import { Brand } from '../App';

export default function Login() {
  const { login, expired } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError(null);
    try { await login(username, password); } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <div className="login-page"><section className="login-presentation"><Brand light /><div className="login-copy"><div className="login-eyebrow"><span /> O PRÓXIMO PASSO DO SEU ESCRITÓRIO</div><h1>Menos burocracia.<br />Mais <em>clareza.</em></h1><p>Sua operação fiscal em um só lugar.<br />Organização para o contador, simplicidade para o cliente.</p><div className="login-benefits">{['Empresas organizadas em um único painel', 'Padrões fiscais que acompanham cada emitente', 'Mais agilidade na preparação de documentos'].map(item => <div key={item}><CheckRounded sx={{ fontSize: 18 }} />{item}</div>)}</div></div><div className="login-bottom">PRECISÃO EM CADA DETALHE. <span>SA Trindade</span></div><div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="orbit orbit-three" /></section><section className="login-form-section"><div className="login-form"><div className="login-mobile-brand"><Brand /></div><Typography variant="overline" color="primary">BEM-VINDO AO SEU ESPAÇO</Typography><Typography variant="h4" component="h2" sx={{ mt: 1 }}>Acesse seu escritório</Typography><Typography color="text.secondary" sx={{ mt: 1, mb: 4 }}>Entre com suas credenciais para continuar.</Typography>{expired && !error && <Alert severity="info" sx={{ mb: 3 }}>Sua sessão foi encerrada. Entre novamente para continuar.</Alert>}{error && <Alert severity="error" sx={{ mb: 3 }}>{error.message}</Alert>}<Box component="form" onSubmit={submit} className="form-stack"><TextField label="Usuário" name="username" autoComplete="username" autoFocus required value={username} onChange={e => setUsername(e.target.value)} disabled={busy} /><TextField label="Senha" name="password" autoComplete="current-password" required type={show ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)} disabled={busy} slotProps={{ input: { endAdornment: <InputAdornment position="end"><IconButton aria-label={show ? 'Ocultar senha' : 'Mostrar senha'} onClick={() => setShow(v => !v)} edge="end">{show ? <VisibilityOffOutlined /> : <VisibilityOutlined />}</IconButton></InputAdornment> } }} /><Button type="submit" variant="contained" size="large" disabled={busy} endIcon={busy ? <CircularProgress size={18} /> : <ArrowForwardRounded />} sx={{ py: 1.5 }}>Entrar no sistema</Button></Box><div className="login-security"><LockOutlined sx={{ fontSize: 16 }} /><span>Seu acesso permanece ativo por 8 horas após o login.</span></div><Typography variant="body2" color="text.secondary" sx={{ mt: 5, fontSize: '.75rem' }}>Primeiro acesso? Utilize o usuário <strong>contador</strong> e a senha configurada ao iniciar o servidor.</Typography></div><div className="login-form-footer">SA Trindade <span>© {new Date().getFullYear()}</span></div></section></div>;
}
