document.getElementById('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const out = document.getElementById('resultado');
  out.textContent = 'Salvando...';
  const f = new FormData(event.target);
  const filters = {};
  for (const k of ['uf', 'minValue', 'maxValue']) if (f.get(k)) filters[k] = f.get(k);
  if (f.get('open')) filters.open = true;
  try {
    const resposta = await fetch('/buscas', {
      method: 'POST',
      headers: csrfHeaders({ 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }),
      body: JSON.stringify({ name: f.get('name'), query: f.get('query'), frequency: f.get('frequency'), filters }),
    });
    const corpo = await resposta.json();
    out.textContent = resposta.ok
      ? 'Busca salva: ' + corpo.name
      : (corpo.detail ?? 'Não foi possível salvar a busca.');
  } catch (erro) {
    out.textContent = 'Não foi possível falar com o servidor. Tente de novo em instantes.';
  }
});
