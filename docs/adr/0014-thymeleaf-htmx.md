# ADR-0014: Interface renderizada no servidor com Thymeleaf e htmx, sem SPA

- Status: aceita
- Data: 2026-10-23

## Contexto

O produto precisa de uma interface pequena: buscar licitações com filtros, abrir o detalhe com o histórico de situação e, mais adiante, salvar uma busca. Há uma pessoa mantendo o código, a API REST já existe e os mesmos casos de uso servem os dois lados. A política de segurança de conteúdo (CSP) do projeto não aceita script nem estilo inline (`default-src 'self'; script-src 'self'; style-src 'self'`). O texto das licitações vem do portal e não é confiável: o objeto, o órgão e o trecho destacado da busca chegam com qualquer conteúdo.

## Decisão

Páginas renderizadas no servidor com Thymeleaf e atualizações parciais com htmx, sem etapa de build de JavaScript.

- As páginas ficam em `search.adapter.in.web`. Os controladores chamam os casos de uso (`SearchUseCase`, `DetailUseCase`), não a API por HTTP, e não têm `@Transactional`.
- Rotas: `GET /` (busca), `GET /fragments/results` (só a lista, para o htmx) e `GET /licitacoes/{id}` (detalhe). `/buscas/nova` já existe no contexto de alertas e continua lá.
- A página `/` também renderiza a lista no servidor. Assim um endereço com `?q=...` funciona ao abrir ou recarregar, e o formulário funciona sem JavaScript (é um `GET` comum).
- O formulário usa `hx-get="/fragments/results"` com `hx-trigger="submit, change from:#uf, change from:#modality, change from:#open"` e `hx-push-url="true"`; o campo de texto tem o próprio gatilho `keyup changed delay:400ms` (com `hx-include` do formulário). O endereço de um fragmento mostraria uma lista nua ao recarregar, então a resposta traz o endereço da página completa em `HX-Push-Url` (envio e filtros) ou em `HX-Replace-Url` (digitação, pelo cabeçalho `HX-Trigger: q`), para cada pausa na digitação não encher o histórico. Resposta de erro traz `HX-Push-Url: false`.
- "Carregar mais" usa o cursor keyset da API. É um link (`<a id="more-button" href="/?...&cursor=...">`) que o htmx melhora com `hx-get`, `hx-target="#results-list"` e `hx-swap="beforeend"`; sem JavaScript o link abre a página seguinte (`GET /` aceita `cursor`). A resposta traz os itens e o link seguinte por `hx-swap-oob`; na última página o mesmo `id` passa a um parágrafo "Fim dos resultados." com `tabindex="-1"`, e o htmx devolve o foco a ele.
- htmx 2.0.11 vem do pacote npm `htmx.org` (`dist/htmx.min.js`), é servido de `/js/htmx-2.0.11.min.js` e fica no repositório. SHA-256: `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`. Sem CDN em tempo de execução. Subir a versão é trocar o arquivo, o nome, o comentário do `layout.html` e a soma no teste.
- A CSP é a da cadeia de segurança (ADR-0019), a mesma das páginas atuais. Os templates a respeitam: nenhum `<script>` inline, nenhum `style=`, nenhum atributo `on...`. O htmx é configurado pela meta `htmx-config`: `includeIndicatorStyles:false` (senão ele injetaria um `<style>`), `allowEval:false`, `allowScriptTags:false` e `responseHandling` que troca também respostas 4xx e 5xx, para a mensagem de erro aparecer em vez de ficar a lista antiga.
- O trecho da busca vem com `<b>` em volta dos termos. Ele é cortado nesses dois marcadores no servidor (`Snippets`), cada pedaço sai por `th:text` e só os pedaços marcados ganham `<mark>`. Não existe `th:utext` nos templates.
- Acessibilidade mínima: `lang="pt-BR"`, HTML semântico (`header`, `nav`, `main`, `footer`, `section`), `label` ligado a todo campo, link "Ir para o conteúdo", foco visível em links, botões e campos, região de resultados com `aria-live="polite"`, tabela do histórico com `th scope` e layout que funciona em tela estreita.
- Falhas viram uma frase em português com caminho de volta (400 para filtro, UF, cursor e texto inválidos; 404 para licitação inexistente; 503 com `Retry-After` quando a consulta estoura o tempo; 500 sem expor a causa). Dentro do htmx, a frase vai para a área de resultados (`HX-Retarget`).
- As rotas de página não passam pelo limitador de requisições, que só atua em `/api/**`. Se a interface virar alvo de abuso, o limite entra na borda (ADR-0027).
- Estilo em `static/css/app.css`, à mão, sem framework. O link da folha de estilo leva o hash do conteúdo (`/css/app-<hash>.css`, estratégia de conteúdo da cadeia de recursos do Spring) e os estáticos têm `Cache-Control: max-age=3600`, então uma edição não fica velha no cache. O htmx já tem a versão no nome.
- Erros fora dos controladores (endereço desconhecido, falha no contêiner) usam `templates/error.html`: só o status aparece, nunca a mensagem, o caminho ou a pilha.

## Alternativas rejeitadas (e por quê)

- SPA (React, Vue ou Svelte): exige etapa de build, gerenciador de pacotes no repositório, tratamento de CSP para o empacotamento e uma segunda cópia do contrato no cliente. Para três telas, o custo supera o ganho.
- htmx por CDN: coloca um terceiro no caminho de toda página em produção e obriga a manter SRI e a liberar o domínio na CSP. O arquivo local custa 51 KB.
- Alpine.js ou JavaScript próprio para as trocas: reimplementa o que o htmx já faz (indicador, histórico, troca de pedaços) e, no caso do Alpine, precisa de `unsafe-eval` na CSP.
- htmx 4 (tag `next` do npm): ainda em desenvolvimento no dia da decisão.
- Renderizar o trecho com `th:utext` e sanitizar depois: um erro na sanitização vira XSS. Cortar nos marcadores e escapar tudo não tem esse modo de falha.

## Consequências

- Uma interface sem build de front e sem JavaScript próprio: o único script é o htmx.
- A interface e a API compartilham os casos de uso e a paginação por cursor; mudar um filtro é mudar os dois controladores.
- O nome das modalidades fica numa tabela no adaptador web (`Labels`), copiada da tabela de domínio do PNCP; uma modalidade nova aparece como "Modalidade N" até a tabela ser atualizada.
- O teste de navegador (Playwright) roda só com `-Pe2e` (`make e2e-ui`) e baixa o Chromium na primeira execução.

## Prova

- `SearchPagesTest`, `DetailPageTest` e `WebErrorsTest` (`@WebMvcTest`): página completa com formulário e área de resultados, fragmento sem `<html>`, botão com o próximo cursor, histórico, 404, XSS (objeto com `<script>` escapado e `<mark>` preservado) e as mensagens de erro.
- `SnippetsTest`: o corte nos marcadores.
- `PageHygieneTest`: CSP e demais cabeçalhos presentes, nenhuma página com script, estilo ou atributo de evento inline, configuração do htmx, e a soma SHA-256 do arquivo servido igual à registrada.
- `SearchUiE2EIT` (Playwright, `-Pe2e`): digita uma busca no navegador, confere a lista, o endereço e que o histórico não cresce ao digitar, abre o detalhe e carrega mais páginas.
