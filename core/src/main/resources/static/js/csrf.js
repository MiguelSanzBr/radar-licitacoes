// Token CSRF da sessao: o servidor grava o cookie XSRF-TOKEN e espera o valor em X-XSRF-TOKEN.
function csrfHeaders(headers) {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return Object.assign({ 'X-XSRF-TOKEN': match ? decodeURIComponent(match[1]) : '' }, headers);
}
