document.getElementById('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const out = document.getElementById('resultado');
  try {
    const resposta = await fetch('/assinatura', {
      method: 'POST',
      headers: csrfHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
      body: new URLSearchParams(new FormData(event.target)),
    });
    out.textContent = resposta.ok
      ? await resposta.text()
      : ((await resposta.json()).detail ?? 'Não foi possível cadastrar.');
  } catch (erro) {
    out.textContent = 'Não foi possível falar com o servidor. Tente de novo em instantes.';
  }
});
