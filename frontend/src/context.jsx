import { createContext, useContext, useMemo, useState, useEffect, useCallback, useRef } from 'react';
import { createApi } from './api';

const AuthContext = createContext(null);
const sessionApi = createApi();
export function AuthProvider({ children }) {
  const [session, setSession] = useState(null);
  const [initializing, setInitializing] = useState(true);
  const [restoreError, setRestoreError] = useState(null);
  const [expired, setExpired] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const operation = useRef(0);
  const channel = useRef(null);
  const expire = useCallback(() => { operation.current++; setSession(null); setExpired(true); setInitializing(false); setRestoreError(null); }, []);
  const api = useMemo(() => session ? createApi(session.csrfToken, expire) : null, [session?.csrfToken, expire]);

  const restore = useCallback(async (signal) => {
    const current = ++operation.current;
    setInitializing(true); setRestoreError(null);
    try {
      const result = await sessionApi('/auth/session', { signal });
      if (current === operation.current && !signal?.aborted) {
        setSession(Date.parse(result.expiresAt) > Date.now() ? result : null);
      }
    } catch (error) {
      if (current !== operation.current || signal?.aborted) return;
      if (error.status === 401) setSession(null);
      else setRestoreError(error);
    } finally {
      if (current === operation.current && !signal?.aborted) setInitializing(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    restore(controller.signal);
    return () => controller.abort();
  }, [restore]);

  useEffect(() => {
    if (!('BroadcastChannel' in window)) return;
    const current = new BroadcastChannel('sa-trindade-session');
    channel.current = current;
    current.onmessage = () => restore();
    return () => { current.close(); channel.current = null; };
  }, [restore]);

  useEffect(() => {
    if (!session) return;
    const deadline = Date.parse(session.expiresAt);
    const check = () => { if (Date.now() >= deadline) expire(); };
    const timer = setTimeout(check, Math.max(0, deadline - Date.now()));
    window.addEventListener('focus', check);
    window.addEventListener('pageshow', check);
    document.addEventListener('visibilitychange', check);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('focus', check);
      window.removeEventListener('pageshow', check);
      document.removeEventListener('visibilitychange', check);
    };
  }, [session, expire]);

  async function login(username, password) {
    const current = ++operation.current;
    const result = await sessionApi('/auth/login', { method: 'POST', body: { username: username.trim(), password } });
    if (current === operation.current) { setSession(result); setExpired(false); setRestoreError(null); setInitializing(false); }
    channel.current?.postMessage('changed');
  }
  async function logout() {
    if (!api || loggingOut) return;
    setLoggingOut(true);
    try {
      await api('/auth/logout', { method: 'POST' });
      operation.current++; setSession(null); setExpired(false);
      channel.current?.postMessage('changed');
    } catch (error) {
      if (error.status !== 401) throw error;
    } finally { setLoggingOut(false); }
  }
  return <AuthContext.Provider value={{ user: session?.user, api, login, logout, initializing, restoreError, restore: () => restore(), expired, loggingOut }}>{children}</AuthContext.Provider>;
}
export const useAuth = () => useContext(AuthContext);
export const WorkspaceContext = createContext(null);
export const useWorkspace = () => useContext(WorkspaceContext);

export function useResource(path) {
  const { api } = useAuth();
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [version, setVersion] = useState(0);
  const reload = useCallback(() => setVersion(v => v + 1), []);
  useEffect(() => {
    if (!api || path === null) { setData(null); setError(null); setLoading(false); return; }
    const controller = new AbortController();
    setLoading(true); setError(null); setData(null);
    api(path, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setData(result); })
      .catch(e => { if (!controller.signal.aborted) setError(e); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, path, version]);
  return { data, error, loading, reload };
}
