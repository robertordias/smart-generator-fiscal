export class ApiError extends Error {
  constructor(message, status, fields = {}) {
    super(message);
    this.status = status;
    this.fields = fields;
  }
}

export function createApi(csrfToken = null, onUnauthorized = () => {}) {
  return async function request(path, { body, headers, responseType, ...options } = {}) {
    const isForm = body instanceof FormData;
    let response;
    try {
      response = await fetch(`/api${path}`, {
        ...options,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'X-Requested-With': 'XMLHttpRequest', ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}), ...(body && !isForm ? { 'Content-Type': 'application/json' } : {}), ...headers },
        ...(body ? { body: isForm ? body : JSON.stringify(body) } : {}),
      });
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new ApiError('Não foi possível conectar ao servidor. Verifique se o backend Java está em execução.', 0);
    }
    if (response.ok && responseType === 'blob') return response.blob();
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401) onUnauthorized();
      const fallback = {
        401: 'Usuário ou senha incorretos.',
        403: 'Seu perfil não possui permissão para esta ação.',
        404: 'O registro não foi encontrado ou você não tem acesso a ele.',
        409: 'Este registro já existe. Revise os dados antes de tentar novamente.',
        413: 'O arquivo excede o limite de 1 MB.',
        422: 'Não existe configuração fiscal vigente para a competência selecionada.',
        500: 'O servidor encontrou um problema. Tente novamente em instantes.',
        502: 'O backend Java não está disponível. Inicie o servidor e tente novamente.',
        503: 'O servidor está indisponível. Tente novamente em instantes.',
      };
      throw new ApiError(data.error && !data.timestamp ? data.error : fallback[response.status] || 'Não foi possível concluir a operação.', response.status, data.fields);
    }
    return data;
  };
}

export function query(values) {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value !== '' && value !== undefined && value !== null) params.set(key, value); });
  return params.toString();
}
export const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
export const money = (value) => Number(value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
export const date = (value) => value ? new Intl.DateTimeFormat('pt-BR').format(new Date(`${value}T12:00:00`)) : '—';
export const cnpj = (value = '') => value.replace(/^(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})$/, '$1.$2.$3/$4-$5');
export const rates = [['issRate', 'ISS'], ['irrfRate', 'IRRF'], ['pisRate', 'PIS'], ['cofinsRate', 'COFINS'], ['csllRate', 'CSLL'], ['inssRate', 'INSS']];
